/*
 * Copyright 2018 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.gradle;

import com.google.cloud.tools.jib.builder.BuildConfiguration;
import com.google.cloud.tools.jib.cache.CacheDirectoryCreationException;
import com.google.cloud.tools.jib.configuration.CacheConfiguration;
import com.google.cloud.tools.jib.configuration.LayerConfiguration;
import com.google.cloud.tools.jib.frontend.BuildStepsExecutionException;
import com.google.cloud.tools.jib.frontend.BuildStepsRunner;
import com.google.cloud.tools.jib.frontend.ExposedPortsParser;
import com.google.cloud.tools.jib.frontend.HelpfulSuggestions;
import com.google.cloud.tools.jib.http.Authorization;
import com.google.cloud.tools.jib.image.ImageReference;
import com.google.cloud.tools.jib.image.InvalidImageReferenceException;
import com.google.cloud.tools.jib.registry.RegistryClient;
import com.google.cloud.tools.jib.registry.credentials.RegistryCredentials;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

/** Builds a container image to a tarball. */
public class BuildTarTask extends DefaultTask {

  /** {@code User-Agent} header suffix to send to the registry. */
  private static final String USER_AGENT_SUFFIX = "jib-gradle-plugin";

  private static final HelpfulSuggestions HELPFUL_SUGGESTIONS =
      HelpfulSuggestionsProvider.get("Building image tarball failed");

  @Nullable private JibExtension jibExtension;

  /**
   * This will call the property {@code "jib"} so that it is the same name as the extension. This
   * way, the user would see error messages for missing configuration with the prefix {@code jib.}.
   *
   * @return the {@link JibExtension}.
   */
  @Nested
  @Nullable
  public JibExtension getJib() {
    return jibExtension;
  }

  /**
   * The target image can be overridden with the {@code --image} command line option.
   *
   * @param targetImage the name of the 'to' image.
   */
  @Option(option = "image", description = "The image reference for the target image")
  public void setTargetImage(String targetImage) {
    Preconditions.checkNotNull(jibExtension).getTo().setImage(targetImage);
  }

  /**
   * @return the input files to this task are all the output files for all the dependencies of the
   *     {@code classes} task.
   */
  @InputFiles
  public FileCollection getInputFiles() {
    return GradleProjectProperties.getInputFiles(
        Preconditions.checkNotNull(jibExtension).getExtraDirectory(), getProject());
  }

  /**
   * The output file to check for task up-to-date.
   *
   * @return the output path
   */
  @OutputFile
  public String getOutputFile() {
    return getTargetPath();
  }

  /**
   * Returns the output directory for the tarball. By default, it is {@code build/jib-image.tar}.
   *
   * @return the output directory
   */
  private String getTargetPath() {
    return getProject().getBuildDir().toPath().resolve("jib-image.tar").toString();
  }

  @TaskAction
  public void buildTar() throws InvalidImageReferenceException, IOException {
    // Asserts required @Input parameters are not null.
    Preconditions.checkNotNull(jibExtension);
    GradleBuildLogger gradleBuildLogger = new GradleBuildLogger(getLogger());
    jibExtension.handleDeprecatedParameters(gradleBuildLogger);

    RegistryCredentials knownBaseRegistryCredentials = null;
    Authorization fromAuthorization = jibExtension.getFrom().getImageAuthorization();
    if (fromAuthorization != null) {
      knownBaseRegistryCredentials = new RegistryCredentials("jib.from.auth", fromAuthorization);
    }

    GradleProjectProperties gradleProjectProperties =
        GradleProjectProperties.getForProject(getProject(), gradleBuildLogger);
    String mainClass = gradleProjectProperties.getMainClass(jibExtension);
    ImageReference targetImage =
        gradleProjectProperties.getGeneratedTargetDockerTag(jibExtension, gradleBuildLogger);

    // Builds the BuildConfiguration.
    // TODO: Consolidate with BuildImageTask/BuildDockerTask.
    BuildConfiguration.Builder buildConfigurationBuilder =
        BuildConfiguration.builder(gradleBuildLogger)
            .setBaseImage(ImageReference.parse(jibExtension.getBaseImage()))
            .setTargetImage(targetImage)
            .setBaseImageCredentialHelperName(jibExtension.getFrom().getCredHelper())
            .setKnownBaseRegistryCredentials(knownBaseRegistryCredentials)
            .setMainClass(mainClass)
            .setJavaArguments(jibExtension.getArgs())
            .setJvmFlags(jibExtension.getJvmFlags())
            .setExposedPorts(ExposedPortsParser.parse(jibExtension.getExposedPorts()))
            .setAllowHttp(jibExtension.getAllowInsecureRegistries());
    if (Files.exists(jibExtension.getExtraDirectory().toPath())) {
      try (Stream<Path> extraFilesLayerDirectoryFiles =
          Files.list(jibExtension.getExtraDirectory().toPath())) {
        buildConfigurationBuilder.setExtraFilesLayerConfiguration(
            LayerConfiguration.builder()
                .addEntry(extraFilesLayerDirectoryFiles.collect(Collectors.toList()), "/")
                .build());
      }
    }
    CacheConfiguration applicationLayersCacheConfiguration =
        CacheConfiguration.forPath(gradleProjectProperties.getCacheDirectory());
    buildConfigurationBuilder.setApplicationLayersCacheConfiguration(
        applicationLayersCacheConfiguration);
    if (jibExtension.getUseOnlyProjectCache()) {
      buildConfigurationBuilder.setBaseImageLayersCacheConfiguration(
          applicationLayersCacheConfiguration);
    }
    BuildConfiguration buildConfiguration = buildConfigurationBuilder.build();

    // TODO: Instead of disabling logging, have authentication credentials be provided
    GradleBuildLogger.disableHttpLogging();

    RegistryClient.setUserAgentSuffix(USER_AGENT_SUFFIX);

    // Uses a directory in the Gradle build cache as the Jib cache.
    try {
      BuildStepsRunner.forBuildTar(
              Paths.get(getTargetPath()),
              buildConfiguration,
              gradleProjectProperties.getSourceFilesConfiguration())
          .build(HELPFUL_SUGGESTIONS);

    } catch (CacheDirectoryCreationException | BuildStepsExecutionException ex) {
      throw new GradleException(ex.getMessage(), ex.getCause());
    }
  }

  BuildTarTask setJibExtension(JibExtension jibExtension) {
    this.jibExtension = jibExtension;
    return this;
  }
}
