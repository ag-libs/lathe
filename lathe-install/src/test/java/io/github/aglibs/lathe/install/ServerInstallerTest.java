package io.github.aglibs.lathe.install;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
            "--module-path",
            joined,
            "-m",
            "io.github.aglibs.lathe.server/io.github.aglibs.lathe.server.LatheServer")
        .noneMatch(arg -> arg.contains("ALL-UNNAMED"));
    assertThat(ServerInstaller.mcpArgs(jars))
        .contains("jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED")
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
        return Path.of("%s.jar".formatted(coordinates));
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
