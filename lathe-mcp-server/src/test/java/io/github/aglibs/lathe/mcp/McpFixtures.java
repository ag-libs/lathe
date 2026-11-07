package io.github.aglibs.lathe.mcp;

import io.github.aglibs.lathe.server.engine.LatheLocation;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

// What the MCP tests share: an answer's text, and a location to render.
final class McpFixtures {

  private McpFixtures() {}

  static String text(final CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }

  static LatheLocation location(final String path, final int line, final String code) {
    return location(path, line, LatheLocation.Origin.REACTOR, code);
  }

  // A location at a 1-based line, as the tools print it.
  static LatheLocation location(
      final String path, final int line, final LatheLocation.Origin origin, final String code) {
    return new LatheLocation("file:///w/%s".formatted(path), path, line - 1, 0, origin, code);
  }
}
