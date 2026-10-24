package io.github.aglibs.lathe.gradle;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.gradle.testkit.runner.GradleRunner;

// A fixture build on the Gradle (and JDK) the build passes in: the one that builds the plugin, or
// an older one in the older-version CI jobs.
final class GradleFixture {

  private final Path dir;

  GradleFixture(final Path dir) {
    this.dir = dir;
  }

  GradleFixture file(final String relative, final String content) throws IOException {
    final var path = dir.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content, StandardCharsets.UTF_8);
    return this;
  }

  // Registers Lathe the personal way, as a user's init script does: from a repository, applied
  // only when the settings script has not applied it already.
  GradleFixture latheInitScript(final String relative) throws IOException {
    return file(
        relative,
        """
        initscript {
          repositories {
            maven { url = uri("%s") }
            maven { url = uri("%s") }
            mavenCentral()
          }
          dependencies { classpath("io.github.ag-libs:lathe-gradle-plugin:%s") }
        }

        settingsEvaluated {
          if (!pluginManager.hasPlugin("io.github.ag-libs.lathe")) {
            pluginManager.apply(io.github.aglibs.lathe.gradle.LatheSettingsPlugin::class.java)
          }
        }
        """
            .formatted(
                repoUri("lathe.gradle.fixturesRepo"),
                repoUri("lathe.gradle.latheRepo"),
                System.getProperty("lathe.version")));
  }

  private static String repoUri(final String property) {
    return Path.of(System.getProperty(property)).toUri().toString();
  }

  // The fixture JDK goes in the build's gradle.properties, where Gradle reads the daemon's JDK;
  // Properties escapes a Windows path. Written once: a rewritten file (Properties stamps the time)
  // would stop the configuration cache from being reused.
  GradleRunner runner(final String... args) throws IOException {
    final var javaHome = System.getProperty("lathe.gradle.fixturesJavaHome");
    final var file = dir.resolve("gradle.properties");
    final var properties = new Properties();
    if (Files.exists(file)) {
      try (final BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        properties.load(reader);
      }
    }

    if (javaHome != null && !javaHome.equals(properties.getProperty("org.gradle.java.home"))) {
      properties.setProperty("org.gradle.java.home", javaHome);
      try (final BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
        properties.store(writer, null);
      }
    }

    return GradleRunner.create()
        .withGradleInstallation(new File(System.getProperty("lathe.gradle.fixturesHome")))
        .withProjectDir(dir.toFile())
        .withPluginClasspath()
        .withArguments(args);
  }
}
