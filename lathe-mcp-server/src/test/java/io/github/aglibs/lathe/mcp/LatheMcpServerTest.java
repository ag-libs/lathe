package io.github.aglibs.lathe.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class LatheMcpServerTest {

  @TempDir private Path logs;

  // A server killed outright leaves its log's lock behind; a live session's lock has its log.
  @Test
  void isOrphanLock_sessionLocks_trueOnlyForALockWhoseLogIsGone() throws IOException {
    final Path live = Files.writeString(logs.resolve("mcp-20261024-090000-1.log.lck"), "");
    Files.writeString(logs.resolve("mcp-20261024-090000-1.log"), "");
    final Path orphan = Files.writeString(logs.resolve("mcp-20261001-090000-2.log.lck"), "");
    final Path foreign = Files.writeString(logs.resolve("other.log.lck"), "");

    assertThat(LatheMcpServer.isOrphanLock(orphan)).isTrue();
    assertThat(LatheMcpServer.isOrphanLock(live)).isFalse();
    assertThat(LatheMcpServer.isOrphanLock(foreign)).isFalse();
    assertThat(LatheMcpServer.isOrphanLock(logs.resolve("mcp-20261024-090000-1.log"))).isFalse();
  }

  // A client ends a stdio session by closing stdin, and one that crashes sends no signal at all:
  // the
  // server must exit then, or each such session leaves a JVM running.
  @Test
  @Timeout(60)
  void main_clientClosesStdin_serverExits() throws Exception {
    final var process =
        new ProcessBuilder(
                Path.of(
                        System.getProperty(LatheLayout.JAVA_HOME_PROPERTY),
                        LatheLayout.BIN_DIR,
                        "java")
                    .toString(),
                "-D%s=%s".formatted(LatheFlags.CACHE_ROOT, logs),
                "-cp",
                System.getProperty("java.class.path"),
                LatheMcpServer.class.getName())
            .directory(logs.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try {
      final var stdin = process.getOutputStream();
      stdin.write(INITIALIZE.getBytes(StandardCharsets.UTF_8));
      stdin.flush();
      final var stdout =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      assertThat(stdout.readLine()).as("the server answered the handshake").contains("\"result\"");

      stdin.close();

      assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("exited after stdin closed").isTrue();
      assertThat(process.exitValue()).isZero();
    } finally {
      process.destroyForcibly().waitFor();
    }
  }

  private static final String INITIALIZE =
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
      "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
      """;
}
