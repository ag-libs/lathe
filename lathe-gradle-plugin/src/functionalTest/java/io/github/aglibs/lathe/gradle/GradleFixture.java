package io.github.aglibs.lathe.gradle;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import org.gradle.testkit.runner.GradleRunner;

// A fixture build on the Gradle (and JDK) the build passes in: the one that builds the plugin, or
// an older one in the older-version CI jobs.
final class GradleFixture {

  static final String JAVA = "plugins { java }\n";

  // A sync installs the server and extracts the JDK sources into Lathe's cache: one for every
  // fixture build, under the build directory, not the user's.
  private static final String LATHE_CACHE =
      "-Dlathe.cache=%s".formatted(System.getProperty("lathe.gradle.fixturesCache"));

  // The fixture JDK, and a short idle timeout: on Windows a daemon inherits the outer build's
  // output pipe, so one idling after the tests keeps Maven waiting on it.
  private static final Map<String, String> DAEMON_PROPERTIES = daemonProperties();

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

  // Lathe registered in the settings, as a team commits it.
  static String latheSettings(final String rootName) {
    return """
        plugins { id("io.github.ag-libs.lathe") }
        rootProject.name = "%s"
        """
        .formatted(rootName);
  }

  // Settings-level repositories that resolve Lathe's server from the repository the reactor fills.
  static String latheRepositories() {
    return """
        dependencyResolutionManagement {
          repositories {
            maven { url = uri("%s") }
            mavenCentral()
          }
        }
        """
        .formatted(repoUri("lathe.gradle.latheRepo"));
  }

  private static Map<String, String> daemonProperties() {
    final var idle = Map.entry("org.gradle.daemon.idletimeout", "10000");
    return Optional.ofNullable(System.getProperty("lathe.gradle.fixturesJavaHome"))
        .map(javaHome -> Map.ofEntries(idle, Map.entry("org.gradle.java.home", javaHome)))
        .orElseGet(() -> Map.ofEntries(idle));
  }

  private static String repoUri(final String property) {
    return Path.of(System.getProperty(property)).toUri().toString();
  }

  // The daemon settings go in the build's gradle.properties, where Gradle reads them, written only
  // when they change: a rewritten file (Properties stamps the time) would stop the configuration
  // cache from being reused. Properties escapes a Windows path.
  GradleRunner runner(final String... args) throws IOException {
    final var file = dir.resolve("gradle.properties");
    final var properties = new Properties();
    if (Files.exists(file)) {
      try (final BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        properties.load(reader);
      }
    }

    if (!DAEMON_PROPERTIES.entrySet().stream()
        .allMatch(entry -> entry.getValue().equals(properties.getProperty(entry.getKey())))) {
      properties.putAll(DAEMON_PROPERTIES);
      try (final BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
        properties.store(writer, null);
      }
    }

    return GradleRunner.create()
        .withGradleInstallation(new File(System.getProperty("lathe.gradle.fixturesHome")))
        .withProjectDir(dir.toFile())
        .withPluginClasspath()
        .withArguments(Stream.concat(Stream.of(LATHE_CACHE), Stream.of(args)).toList());
  }
}
