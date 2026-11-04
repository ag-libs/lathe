package io.github.aglibs.lathe.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import org.gradle.util.GradleVersion;
import org.junit.jupiter.api.Test;

class LatheSettingsPluginTest {

  @Test
  void unmet_olderGradle_namesTheVersionAndTheFix() {
    assertThat(LatheSettingsPlugin.unmet(GradleVersion.version("8.7")))
        .containsExactly(
            "Gradle 8.7 is older than 8.8, the oldest Lathe supports: upgrade Gradle, or the project's wrapper (./gradlew wrapper --gradle-version 8.8)");
  }

  // A release candidate of the oldest supported version has its API, so it passes.
  @Test
  void unmet_supportedGradle_none() {
    assertThat(LatheSettingsPlugin.unmet(GradleVersion.version("8.8-rc-1"))).isEmpty();
    assertThat(LatheSettingsPlugin.unmet(GradleVersion.version("9.8.2"))).isEmpty();
  }
}
