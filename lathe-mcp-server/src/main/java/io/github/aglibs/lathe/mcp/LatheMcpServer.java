package io.github.aglibs.lathe.mcp;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheBuildInfo;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.LatheLogging;
import io.github.aglibs.lathe.server.engine.LatheEngine;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MCP server entry point. Resolves the reactor root from the working directory (walking up to the
 * nearest {@code .lathe/}), opens a {@link LatheEngine}, and serves the tool surface over the SDK
 * stdio transport. One process per agent session.
 */
public final class LatheMcpServer {

  private static final Logger LOG = Logger.getLogger(LatheMcpServer.class.getName());

  // Session tag for the per-session log filename: process start time, so files sort
  // chronologically.
  private static final DateTimeFormatter SESSION_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

  // A session log at INFO is a few kilobytes, so this keeps weeks of sessions.
  private static final int KEPT_SESSION_LOGS = 100;

  // Routing guidance injected into the agent's context (Claude Code reads server instructions).
  // Dispatch-first and kept well under the ~2KB the client truncates at. The tool descriptions
  // repeat the key cues so they still steer clients that drop instructions (e.g. claude.ai web).
  static final String INSTRUCTIONS =
      """
      Lathe provides javac-accurate, cross-module code intelligence for this multi-module Java build \
      — precise where text search is ambiguous. In an unfamiliar project, call workspace_summary \
      first: the modules, what each depends on, and the sync command. Choose by task:

      - Renaming or removing a symbol: ALWAYS use rename_symbol. It rewrites the true declaration, \
      every override/implementation, and all call sites across modules, and nothing that merely \
      shares the name; grep/sed cannot do this safely.
      - Finding all uses of a symbol before changing it, especially a common or overloaded name: \
      use find_references (resolves overloads and inheritance). For a rare, distinctive name grep \
      is fine and cheaper.
      - Jumping to a definition, especially into dependencies, the JDK, or generated sources: use \
      get_definition (grep cannot follow into non-source).
      - Tracing who calls a method, or what it calls: use call_hierarchy (the real cross-module call \
      graph, which text search cannot follow).
      - Understanding an API before calling it (signature, type, javadoc): use describe_symbol.
      - Learning a file's structure, or finding a member's line, without reading the whole file: \
      use outline.
      - Finding a type by name (reactor, dependencies, JDK): use search_symbols.
      - Finding the implementations of an interface, or the overrides of a method (the declarations \
      themselves, not their call sites): use find_implementations.
      - Checking that edits still compile, no full build: get_diagnostics for one file; \
      verify_change (no arguments) after a multi-file edit or a rename.
      - Adding the imports a file is missing: use fix_imports, which adds every one with a single \
      match and lists the ambiguous names with their candidates.
      - Making an edited file pass the build's formatting check (Spotless): use format_file, which \
      runs the formatter the project pins.
      - Checking whether a test, class, or package passes after an edit: use run_test (replays just \
      that; prefer it over the build's own test command).

      Every tool first compiles the files you changed, so answers reflect your edits. A result may \
      end with notes: "Not compiled:" lists files whose code does not compile, with the error -- fix \
      the code, no sync helps; "Sync required:" names what only a sync fixes (changed build files) \
      with the exact command to run, such as `%s`; "Not checked:" names a file no module \
      compiles, with the sync that would add it when one can; "A build is running" means \
      re-query once it ends."""
          .formatted(LatheLayout.SYNC_SCRIPT_COMMAND);

  private LatheMcpServer() {}

  public static void main(final String[] args) throws InterruptedException {
    LatheLogging.init();

    final var cwd = Path.of("").toAbsolutePath();
    final long pid = ProcessHandle.current().pid();
    final Path logFile = LatheLayout.mcpSessionLog(sessionId(pid));
    LatheLogging.initFile(logFile);
    pruneSessionLogs();
    // The client keeps only the startup burst of stderr, so this line points at the full log.
    LOG.info(
        () ->
            "[startup] %s pid=%d cwd=%s log=%s"
                .formatted(LatheBuildInfo.summary(LatheMcpServer.class), pid, cwd, logFile));

    final Optional<Path> workspaceRoot = findWorkspaceRoot(cwd);
    if (workspaceRoot.isEmpty()) {
      LOG.severe(LatheLayout.SETUP_REMEDIATION);
      System.exit(2);
      return;
    }

    final Path root = workspaceRoot.get();
    LOG.info(() -> "[startup] workspace=%s".formatted(root));

    // stdout is the MCP JSON-RPC channel: hand the transport the real stdout and redirect
    // System.out
    // to stderr so a stray print in the engine cannot corrupt the protocol.
    final var protocolOut = System.out;
    System.setOut(System.err);
    try {
      final var engine = new LatheEngine(root);
      final McpJsonMapper mapper = McpJsonDefaults.getMapper();
      final var transport = new StdioServerTransportProvider(mapper, System.in, protocolOut);
      McpServer.sync(transport)
          .serverInfo("lathe", version())
          .capabilities(ServerCapabilities.builder().tools(true).build())
          .instructions(INSTRUCTIONS)
          .tools(LatheMcpTools.all(engine, mapper))
          .build();
    } catch (final RuntimeException e) {
      LOG.log(Level.SEVERE, e, () -> "[startup] lathe-mcp-server failed to start");
      System.exit(1);
      return;
    }

    LOG.info(() -> "[startup] ready");
    new CountDownLatch(1).await(); // serve until the agent terminates the process
  }

  // Process start time + pid, so each agent session maps to one log file; pid alone if the start
  // time is unavailable.
  // One log per session would accrue forever; the names start with the session's start time, so
  // name order is age order. Best-effort: a failure costs only the pruning.
  private static void pruneSessionLogs() {
    try {
      FileUtil.deleteAllButLast(
          LatheLayout.logsDir(), LatheLayout::isMcpSessionLog, KEPT_SESSION_LOGS);
      FileUtil.deleteAllButLast(LatheLayout.logsDir(), LatheMcpServer::isOrphanLock, 0);
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING,
          e,
          () -> "[startup] old session logs not pruned in %s".formatted(LatheLayout.logsDir()));
    }
  }

  // A live session's lock always has its log beside it.
  static boolean isOrphanLock(final Path path) {
    final String name = path.getFileName().toString();
    if (!name.endsWith(LatheLayout.LOG_LOCK_SUFFIX)) {
      return false;
    }

    final Path log =
        path.resolveSibling(
            name.substring(0, name.length() - LatheLayout.LOG_LOCK_SUFFIX.length()));
    return LatheLayout.isMcpSessionLog(log) && Files.notExists(log);
  }

  private static String sessionId(final long pid) {
    return ProcessHandle.current()
        .info()
        .startInstant()
        .map(SESSION_TIMESTAMP::format)
        .map(timestamp -> "%s-%d".formatted(timestamp, pid))
        .orElseGet(() -> Long.toString(pid));
  }

  private static Optional<Path> findWorkspaceRoot(final Path cwd) {
    for (Path dir = cwd; dir != null; dir = dir.getParent()) {
      if (Files.isDirectory(dir.resolve(LatheLayout.LATHE_DIR))) {
        return Optional.of(dir);
      }
    }

    return Optional.empty();
  }

  private static String version() {
    final var packaged = LatheMcpServer.class.getPackage().getImplementationVersion();
    return packaged != null ? packaged : "dev";
  }
}
