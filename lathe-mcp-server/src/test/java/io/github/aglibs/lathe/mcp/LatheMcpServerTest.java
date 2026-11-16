package io.github.aglibs.lathe.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
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

  // An agent's session tags the log, cut to file-name-safe characters; any other client keeps the
  // process name, and the first agent variable set wins.
  @Test
  void sessionId_agentSessionVariables_tagTheProcessName() {
    final long pid = ProcessHandle.current().pid();
    final String process = LatheMcpServer.sessionId(pid, Map.of());

    assertThat(process).endsWith("-%d".formatted(pid));
    assertThat(LatheMcpServer.sessionId(pid, Map.of("CLAUDE_CODE_SESSION_ID", "3f2a9c1e-77b4-4c")))
        .isEqualTo("%s-claude-3f2a9c1e".formatted(process));
    assertThat(
            LatheMcpServer.sessionId(
                pid, Map.of("COPILOT_AGENT_SESSION_ID", "a/b:c", "CODEX_THREAD_ID", "t1")))
        .isEqualTo("%s-copilot-abc".formatted(process));
    assertThat(
            LatheMcpServer.sessionId(
                pid, Map.of("CLAUDE_CODE_SESSION_ID", "--", "CODEX_THREAD_ID", "t1")))
        .isEqualTo("%s-codex-t1".formatted(process));
  }

  // A client ends a stdio session by closing stdin, and one that crashes sends no signal at all:
  // the server must exit then, or each such session leaves a JVM running. A line that is not a
  // JSON-RPC message is dropped: the SDK's transport would stop reading at it, leaving the server
  // deaf and, with stdin no longer read, never exiting.
  // On a thread of its own: a deaf server blocks readLine, which the test thread cannot interrupt.
  @Test
  @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void main_garbageLineThenStdinClosed_serverKeepsAnsweringThenExits() throws Exception {
    final Process process = startServer(logs, logs);
    try {
      final var stdin = process.getOutputStream();
      stdin.write(INITIALIZE.getBytes(StandardCharsets.UTF_8));
      stdin.flush();
      final var stdout =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      assertThat(stdout.readLine()).as("the server answered the handshake").contains("\"result\"");

      stdin.write(AFTER_GARBAGE.getBytes(StandardCharsets.UTF_8));
      stdin.flush();
      assertThat(stdout.readLine()).as("tools/list after a garbage line").contains("\"tools\"");

      stdin.close();

      assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("exited after stdin closed").isTrue();
      assertThat(process.exitValue()).isZero();
    } finally {
      process.destroyForcibly().waitFor();
    }
  }

  // A session on a workspace compiles in a temp dir per module, which closing the engine deletes:
  // a client closing stdin ends the session with none left behind.
  @Test
  @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void main_workspaceSessionStdinClosed_leavesNoCompileTempDir() throws Exception {
    final Path workspace = Files.createDirectories(logs.resolve("workspace"));
    final Path source = writeWorkspace(workspace);

    sessionLeavesNoCompileTempDir(workspace, () -> source);
  }

  // A server started before the build wrote .lathe/ opens its engine at the first tool call after,
  // and closes it the same way.
  @Test
  @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void main_workspaceCreatedAfterStartStdinClosed_leavesNoCompileTempDir() throws Exception {
    final Path workspace = Files.createDirectories(logs.resolve("workspace"));

    sessionLeavesNoCompileTempDir(workspace, () -> writeWorkspace(workspace));
  }

  // Once the session ends, a tool call still running opens no engine nothing would close.
  @Test
  void awaitedEngine_closed_opensNone() throws Exception {
    final Path workspace = Files.createDirectories(logs.resolve("workspace"));
    writeWorkspace(workspace);
    final var engine = new LatheMcpServer.AwaitedEngine(workspace);

    engine.close();

    assertThatThrownBy(engine::get).isInstanceOf(IllegalStateException.class);
  }

  // Answers one tool call on the file workspaceFile gives after the handshake, then closes stdin.
  private void sessionLeavesNoCompileTempDir(
      final Path workspace, final Callable<Path> workspaceFile) throws Exception {
    final Path temp = Files.createDirectories(logs.resolve("tmp"));
    final Process process = startServer(workspace, temp);
    try {
      final var stdin = process.getOutputStream();
      final var stdout =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      stdin.write(INITIALIZE.getBytes(StandardCharsets.UTF_8));
      stdin.flush();
      assertThat(stdout.readLine()).contains("\"result\"");
      final Path source = workspaceFile.call();
      stdin.write(
          DIAGNOSTICS
              .formatted(source.toString().replace("\\", "\\\\"))
              .getBytes(StandardCharsets.UTF_8));
      stdin.flush();
      assertThat(stdout.readLine()).as("the diagnostics answer").contains("\"result\"");
      assertThat(compileTempDirs(temp))
          .as("the compile's temp dir, while the session runs")
          .isNotEmpty();

      stdin.close();

      assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("exited after stdin closed").isTrue();
      assertThat(compileTempDirs(temp)).isEmpty();
    } finally {
      process.destroyForcibly().waitFor();
    }
  }

  // One module, app, with one source in src/, as a sync writes its params; returns the source.
  private static Path writeWorkspace(final Path workspace) throws IOException {
    final Path source =
        Files.writeString(
            Files.createDirectories(workspace.resolve("src/com/example")).resolve("A.java"),
            "package com.example; class A {}");
    final Path module = Files.createDirectories(workspace.resolve(".lathe/app"));
    Json.write(
        new ModuleConfigData(
            LatheLayout.CLASSES_DIR,
            module.resolve(LatheLayout.CLASSES_DIR).toString(),
            null,
            List.of(workspace.resolve("src").toString()),
            List.of(),
            List.of(),
            List.of(),
            Integer.toString(Runtime.version().feature()),
            "UTF-8",
            false,
            false,
            null,
            List.of(),
            null,
            false,
            false,
            List.of()),
        module.resolve(LatheLayout.paramsFileName(LatheLayout.CLASSES_DIR)));
    return source;
  }

  // The server as a client starts it, in dir, with its temp files in temp and its logs in logs.
  private Process startServer(final Path dir, final Path temp) throws IOException {
    return new ProcessBuilder(
            Path.of(System.getProperty(LatheLayout.JAVA_HOME_PROPERTY), LatheLayout.BIN_DIR, "java")
                .toString(),
            "-D%s=%s".formatted(LatheFlags.CACHE_ROOT, logs),
            "-Djava.io.tmpdir=%s".formatted(temp),
            "-cp",
            System.getProperty("java.class.path"),
            LatheMcpServer.class.getName())
        .directory(dir.toFile())
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();
  }

  private static List<Path> compileTempDirs(final Path temp) throws IOException {
    try (final var entries = Files.list(temp)) {
      return entries
          .filter(
              entry ->
                  entry.getFileName().toString().startsWith(LatheLayout.COMPILE_TEMP_DIR_PREFIX))
          .toList();
    }
  }

  private static final String DIAGNOSTICS =
      """
      {"jsonrpc":"2.0","method":"notifications/initialized"}
      {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_diagnostics",\
      "arguments":{"file":"%s"}}}
      """;

  private static final String INITIALIZE =
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
      "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
      """;

  private static final String AFTER_GARBAGE =
      """
      {"jsonrpc":"2.0","method":"notifications/initialized"}
      this is not json {
      {"jsonrpc":"2.0","id":2,"method":"tools/list"}
      """;

  // MCP session logs are named for their start time, so the last in name order are the newest.
  @Test
  void deleteAllButLast_sessionLogs_keepsTheNewestAndLeavesOtherFilesAlone() throws IOException {
    for (final String name :
        List.of(
            "mcp-20261001-090000-11.log",
            "mcp-20261002-090000-12.log",
            "mcp-20261003-090000-13.log",
            "lsp.log",
            "mcp-notes.txt")) {
      Files.writeString(logs.resolve(name), "x");
    }

    LatheMcpServer.deleteAllButLast(logs, LatheLayout::isMcpSessionLog, 2);

    try (final var left = Files.list(logs)) {
      assertThat(left.map(path -> path.getFileName().toString()))
          .containsExactlyInAnyOrder(
              "mcp-20261002-090000-12.log",
              "mcp-20261003-090000-13.log",
              "lsp.log",
              "mcp-notes.txt");
    }

    LatheMcpServer.deleteAllButLast(logs.resolve("absent"), LatheLayout::isMcpSessionLog, 2);
  }
}
