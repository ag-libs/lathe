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
