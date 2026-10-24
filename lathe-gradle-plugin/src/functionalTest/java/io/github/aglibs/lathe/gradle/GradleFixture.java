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

  // The fixture JDK goes in the build's gradle.properties, where Gradle reads the daemon's JDK;
  // Properties escapes a Windows path.
  GradleRunner runner(final String... args) throws IOException {
    final var javaHome = System.getProperty("lathe.gradle.fixturesJavaHome");
    if (javaHome != null) {
      final var file = dir.resolve("gradle.properties");
      final var properties = new Properties();
      if (Files.exists(file)) {
        try (final BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
          properties.load(reader);
        }
      }

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
