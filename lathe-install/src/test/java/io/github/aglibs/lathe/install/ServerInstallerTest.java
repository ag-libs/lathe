package io.github.aglibs.lathe.install;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    assertThat(ServerInstaller.newestVersionDir(servers)).isEqualTo(servers.resolve("0.1.16"));
  }

  @Test
  void newestVersionDir_missingServersDir_returnsNull(@TempDir final Path root) {
    assertThat(ServerInstaller.newestVersionDir(root.resolve("absent"))).isNull();
  }
}
