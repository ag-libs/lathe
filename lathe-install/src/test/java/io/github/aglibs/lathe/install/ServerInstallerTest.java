package io.github.aglibs.lathe.install;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerInstallerTest {

  // The server is a named module and grants nothing itself; only the classpath MCP server exports
  // javac internals to the unnamed module. Each ends with its entry point, after the jar path.
  @Test
  void serverAndMcpArgs_joinJarsWithThePathSeparator_endWithTheEntryPoint() {
    final List<Path> jars = List.of(Path.of("/m2/a.jar"), Path.of("/m2 dir/b.jar"));
    final String joined =
        jars.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));

    assertThat(ServerInstaller.serverArgs(jars))
        .containsExactly(
            "--add-modules",
            "java.net.http",
            "-Dsun.misc.unsafe.memory.access=allow",
            "--module-path",
            joined,
            "-m",
            "io.github.aglibs.lathe.server/io.github.aglibs.lathe.server.LatheServer")
        .noneMatch(arg -> arg.contains("ALL-UNNAMED"));
    assertThat(ServerInstaller.mcpArgs(jars))
        .contains(
            "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "-Dsun.misc.unsafe.memory.access=allow")
        .endsWith("-cp", joined, "io.github.aglibs.lathe.mcp.LatheMcpServer");
  }

  @Test
  void newestVersionDir_releaseBeatsOlderSnapshot_picksHighestVersion(@TempDir final Path servers)
      throws IOException {
    Files.createDirectories(servers.resolve("0.1.9"));
    Files.createDirectories(servers.resolve("0.1.16-SNAPSHOT"));
    Files.createDirectories(servers.resolve("0.1.15"));
    Files.createDirectories(servers.resolve("0.1.16"));
    // Not a server install, and would sort last as plain text.
    Files.createDirectories(servers.resolve("backup"));

    assertThat(ServerInstaller.newestVersionDir(servers)).isEqualTo(servers.resolve("0.1.16"));
  }

  // The launcher and engine follow the versions the project resolved; a look-alike artifact
  // (junit-platform-commons-extra) is not mistaken for the platform.
  @Test
  void resolveRunnerClasspath_projectUsesJupiter_addsLauncherAndEngineAtItsVersions() {
    final var resolved = new ArrayList<String>();
    final var installer = new ServerInstaller(recording(resolved));

    installer.resolveRunnerClasspath(
        List.of(
            "org.junit.platform:junit-platform-commons-extra:9.9",
            "org.junit.platform:junit-platform-commons:1.11.4",
            "org.junit.jupiter:junit-jupiter-api:5.11.4"));

    assertThat(resolved)
        .endsWith(
            "org.junit.platform:junit-platform-launcher:1.11.4",
            "org.junit.jupiter:junit-jupiter-engine:5.11.4");
  }

  // TE-21: a JUnit 6 reactor that also holds JUnit 5 (a module, or a dependency, on it) replays on
  // the JUnit 6 runner. The coordinates come sorted by name, so 1.10.3 is listed before 6.1.3.
  @Test
  void resolveRunnerClasspath_severalJUnitVersions_resolvesTheHighest() {
    final var resolved = new ArrayList<String>();

    new ServerInstaller(recording(resolved))
        .resolveRunnerClasspath(
            List.of(
                "org.junit.jupiter:junit-jupiter-api:5.10.3",
                "org.junit.jupiter:junit-jupiter-api:6.1.3",
                "org.junit.platform:junit-platform-commons:1.10.3",
                "org.junit.platform:junit-platform-commons:6.1.3"));

    assertThat(resolved)
        .endsWith(
            "org.junit.platform:junit-platform-launcher:6.1.3",
            "org.junit.jupiter:junit-jupiter-engine:6.1.3");
  }

  // A platform 6 a dependency brings (a suite, say) does not run a JUnit 5 Jupiter: the launcher
  // stays with the newest platform 1.x, the one that Jupiter runs on.
  @Test
  void resolveRunnerClasspath_newerPlatformThanJupiter_launcherOfJupitersGeneration() {
    final var resolved = new ArrayList<String>();

    new ServerInstaller(recording(resolved))
        .resolveRunnerClasspath(
            List.of(
                "org.junit.jupiter:junit-jupiter-api:5.11.4",
                "org.junit.platform:junit-platform-commons:1.11.4",
                "org.junit.platform:junit-platform-commons:6.1.3"));

    assertThat(resolved)
        .endsWith(
            "org.junit.platform:junit-platform-launcher:1.11.4",
            "org.junit.jupiter:junit-jupiter-engine:5.11.4");
  }

  // A version that does not parse is still used when it is the only one.
  @Test
  void resolveRunnerClasspath_versionThatDoesNotParse_stillResolved() {
    final var resolved = new ArrayList<String>();

    new ServerInstaller(recording(resolved))
        .resolveRunnerClasspath(
            List.of(
                "org.junit.jupiter:junit-jupiter-api:custom",
                "org.junit.platform:junit-platform-commons:custom"));

    assertThat(resolved)
        .endsWith(
            "org.junit.platform:junit-platform-launcher:custom",
            "org.junit.jupiter:junit-jupiter-engine:custom");
  }

  // TE-21: a reactor on JUnit 5 and 6 at once gets a runner for each platform, with the engine of
  // its generation; a platform with no Jupiter of its generation (JUnit 4 on the vintage engine)
  // gets the launcher alone, as its vintage engine is on the module's own test classpath.
  @Test
  void resolveRunnerClasspaths_junit5And6AndVintage_aRunnerForEachPlatform() {
    final var resolved = new ArrayList<String>();

    final Map<String, List<Path>> runners =
        new ServerInstaller(recording(resolved))
            .resolveRunnerClasspaths(
                List.of(
                    "org.junit.jupiter:junit-jupiter-api:5.14.4",
                    "org.junit.jupiter:junit-jupiter-api:6.1.3",
                    "org.junit.platform:junit-platform-commons:1.14.4",
                    "org.junit.platform:junit-platform-commons:1.9.3",
                    "org.junit.platform:junit-platform-commons:6.1.3"));

    assertThat(runners).containsOnlyKeys("1.14.4", "1.9.3", "6.1.3");
    assertThat(names(runners.get("6.1.3")))
        .contains(
            "org.junit.platform_junit-platform-launcher_6.1.3.jar",
            "org.junit.jupiter_junit-jupiter-engine_6.1.3.jar")
        .anyMatch(name -> name.contains("lathe-test-runner"));
    assertThat(names(runners.get("1.14.4")))
        .contains(
            "org.junit.platform_junit-platform-launcher_1.14.4.jar",
            "org.junit.jupiter_junit-jupiter-engine_5.14.4.jar");
    assertThat(names(runners.get("1.9.3")))
        .contains(
            "org.junit.platform_junit-platform-launcher_1.9.3.jar",
            "org.junit.jupiter_junit-jupiter-engine_5.14.4.jar");
  }

  @Test
  void resolveRunnerClasspaths_platformWithoutJupiter_launcherAlone() {
    final var resolved = new ArrayList<String>();

    final Map<String, List<Path>> runners =
        new ServerInstaller(recording(resolved))
            .resolveRunnerClasspaths(List.of("org.junit.platform:junit-platform-commons:6.1.3"));

    assertThat(names(runners.get("6.1.3")))
        .contains("org.junit.platform_junit-platform-launcher_6.1.3.jar")
        .noneMatch(name -> name.contains("engine"));
  }

  private static List<String> names(final List<Path> classpath) {
    return classpath.stream().map(path -> path.getFileName().toString()).toList();
  }

  @Test
  void resolveRunnerClasspath_noJupiter_resolvesOnlyTheRunner() {
    final var resolved = new ArrayList<String>();

    new ServerInstaller(recording(resolved))
        .resolveRunnerClasspath(List.of("org.junit.platform:junit-platform-commons:1.11.4"));

    assertThat(resolved).singleElement().asString().contains(":lathe-test-runner:");
  }

  // Records each requested coordinate and resolves it to a jar of the same name.
  private static ArtifactResolver recording(final List<String> resolved) {
    return new ArtifactResolver() {
      @Override
      public Path resolve(final String coordinates) {
        resolved.add(coordinates);
        // ':' is not allowed in a Windows file name.
        return Path.of("%s.jar".formatted(coordinates.replace(':', '_')));
      }

      @Override
      public List<Path> resolveTransitive(final String coordinates) {
        return List.of(resolve(coordinates));
      }
    };
  }

  @Test
  void newestVersionDir_missingServersDir_returnsNull(@TempDir final Path root) {
    assertThat(ServerInstaller.newestVersionDir(root.resolve("absent"))).isNull();
  }
}
