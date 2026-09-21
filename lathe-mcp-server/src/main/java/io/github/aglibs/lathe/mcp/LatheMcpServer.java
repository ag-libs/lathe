package io.github.aglibs.lathe.mcp;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.LatheLogging;
import io.github.aglibs.lathe.server.engine.LatheEngine;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import java.nio.file.Files;
import java.nio.file.Path;
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

  // Routing guidance injected into the agent's context (Claude Code reads server instructions).
  // Dispatch-first and kept well under the ~2KB the client truncates at. The tool descriptions
  // repeat the key cues so they still steer clients that drop instructions (e.g. claude.ai web).
  private static final String INSTRUCTIONS =
      """
      Lathe provides javac-accurate, cross-module code intelligence for this Java/Maven reactor \
      — precise where text search is ambiguous. Choose by task:

      - Renaming or removing a symbol: ALWAYS use rename_symbol. It rewrites the true declaration, \
      every override/implementation, and all call sites across modules, and nothing that merely \
      shares the name; grep/sed cannot do this safely.
      - Finding all uses / call sites of a symbol before changing it — especially a common or \
      overloaded name where text search is ambiguous: use find_references (resolves overloads and \
      inheritance, excludes same-named unrelated symbols). For a rare, distinctive name a plain \
      grep is fine and cheaper.
      - Resolving or jumping to a definition, especially into dependencies, the JDK, or generated \
      sources: use get_definition (grep cannot follow into non-source).
      - Tracing who calls a method (or what it calls) to scope the impact of a change: use \
      call_hierarchy — it follows the real cross-module call graph, which text search cannot.
      - Understanding an API before calling it (signature, type, javadoc): use describe_symbol.
      - Finding a type or symbol by name across the reactor, dependencies, and the JDK: use \
      search_symbols.
      - Finding the implementations of an interface, or the overrides of a method (the declarations \
      themselves, not their call sites): use find_implementations.
      - Checking whether a file still compiles after an edit: use get_diagnostics (one file, no \
      Maven).
      - Checking whether a specific test, class, or package passes after an edit: use run_test — it \
      replays just that against the compiled classpath, no reactor build; prefer it over \
      `mvn test` for a single target.

      A "Stale:" note on a result means a module's source is newer than its compiled classes; run \
      `%s` to refresh, then re-query."""
          .formatted(LatheLayout.SYNC_COMMAND);

  private LatheMcpServer() {}

  public static void main(final String[] args) throws InterruptedException {
    LatheLogging.init();

    final Optional<Path> workspaceRoot = findWorkspaceRoot();
    if (workspaceRoot.isEmpty()) {
      LOG.severe(LatheLayout.SETUP_REMEDIATION);
      System.exit(2);
      return;
    }

    final Path root = workspaceRoot.get();
    LOG.info(() -> "[startup] lathe-mcp-server workspace=%s".formatted(root));

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

    LOG.info(() -> "[startup] lathe-mcp-server ready");
    new CountDownLatch(1).await(); // serve until the agent terminates the process
  }

  private static Optional<Path> findWorkspaceRoot() {
    final var cwd = Path.of("").toAbsolutePath();
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
