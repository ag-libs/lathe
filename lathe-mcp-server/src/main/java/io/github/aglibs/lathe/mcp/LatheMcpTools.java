package io.github.aglibs.lathe.mcp;

import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.LatheEngine;
import io.github.aglibs.lathe.server.LatheLocation;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.Diagnostic;

/**
 * The MCP tool surface over a {@link LatheEngine}. Bad input or an engine failure becomes an {@code
 * isError} result rather than a thrown exception.
 */
final class LatheMcpTools {

  private static final Logger LOG = Logger.getLogger(LatheMcpTools.class.getName());

  private LatheMcpTools() {}

  static List<SyncToolSpecification> all(final LatheEngine engine, final McpJsonMapper mapper) {
    return List.of(diagnostics(engine, mapper), definition(engine, mapper));
  }

  private static SyncToolSpecification diagnostics(
      final LatheEngine engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder(
                "get_diagnostics",
                mapper,
                """
                {"type":"object","required":["file"],
                 "properties":{"file":{"type":"string",
                   "description":"Absolute path to a .java file in this project."}}}""")
            .description(
                """
                Compiler-accurate errors and warnings for a Java file, exactly as javac sees \
                them on the real build classpath. Call after editing a file to check whether \
                it compiles — no Maven, no whole-project build.""")
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) ->
                logged("get_diagnostics", () -> handleDiagnostics(engine, request)))
        .build();
  }

  private static SyncToolSpecification definition(
      final LatheEngine engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder(
                "get_definition",
                mapper,
                """
                {"type":"object","required":["file","line","column"],
                 "properties":{
                   "file":{"type":"string","description":"Absolute path to a .java file."},
                   "line":{"type":"integer","description":"1-based line of the symbol."},
                   "column":{"type":"integer","description":"1-based column of the symbol."}}}""")
            .description(
                """
                Resolve the symbol at a position to its definition — across modules and into \
                dependency, JDK, and generated sources — returning each target with a source \
                snippet, not just a file:line.""")
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) ->
                logged("get_definition", () -> handleDefinition(engine, request)))
        .build();
  }

  // One INFO line per tool call — the adoption/latency signal (visible without LATHE_DEBUG).
  private static CallToolResult logged(final String tool, final Supplier<CallToolResult> body) {
    final var t = Stopwatch.start();
    final CallToolResult result = body.get();
    final boolean ok = !Boolean.TRUE.equals(result.isError());
    LOG.info(() -> "[tool] %s %dms %s".formatted(tool, t.elapsedMs(), ok ? "ok" : "error"));
    return result;
  }

  private static CallToolResult handleDiagnostics(
      final LatheEngine engine, final CallToolRequest request) {
    final Path file = filePath(request);
    if (file == null) {
      return error("get_diagnostics requires a 'file' argument");
    }

    try {
      return diagnosticsResult(file, engine.diagnostics(file));
    } catch (final RuntimeException e) {
      LOG.log(Level.SEVERE, e, () -> "[get_diagnostics] failed for %s".formatted(file));
      return error("[get_diagnostics] %s".formatted(e.getMessage()));
    }
  }

  private static CallToolResult handleDefinition(
      final LatheEngine engine, final CallToolRequest request) {
    final Path file = filePath(request);
    final Integer line = intArg(request, "line");
    final Integer column = intArg(request, "column");
    if (file == null || line == null || column == null) {
      return error("get_definition requires 'file', 'line', and 'column' arguments");
    }

    try {
      // MCP positions are 1-based; LatheEngine (LSP) is 0-based.
      return definitionResult(engine.definition(file, line - 1, column - 1));
    } catch (final RuntimeException e) {
      LOG.log(Level.SEVERE, e, () -> "[get_definition] failed for %s".formatted(file));
      return error("[get_definition] %s".formatted(e.getMessage()));
    }
  }

  private static CallToolResult diagnosticsResult(
      final Path file, final List<Diagnostic> diagnostics) {
    final List<Map<String, Object>> items =
        diagnostics.stream().map(LatheMcpTools::diagnosticMap).toList();
    final String text =
        diagnostics.isEmpty()
            ? "No diagnostics — %s compiles cleanly.".formatted(file.getFileName())
            : "%d diagnostic(s) in %s:%n%s"
                .formatted(
                    diagnostics.size(),
                    file.getFileName(),
                    diagnostics.stream()
                        .map(LatheMcpTools::diagnosticLine)
                        .collect(Collectors.joining(System.lineSeparator())));
    return CallToolResult.builder()
        .addTextContent(text)
        .structuredContent(Map.<String, Object>of("file", file.toString(), "diagnostics", items))
        .build();
  }

  private static CallToolResult definitionResult(final List<LatheLocation> targets) {
    final List<Map<String, Object>> items =
        targets.stream().map(LatheMcpTools::locationMap).toList();
    final String text =
        targets.isEmpty()
            ? "No definition found."
            : targets.stream()
                .map(t -> "→ %s [%s]%n%s".formatted(t.uri(), t.origin(), t.snippet()))
                .collect(Collectors.joining(System.lineSeparator()));
    return CallToolResult.builder()
        .addTextContent(text)
        .structuredContent(Map.<String, Object>of("targets", items))
        .build();
  }

  private static Map<String, Object> diagnosticMap(final Diagnostic d) {
    return Map.<String, Object>of(
        "severity",
        String.valueOf(d.getSeverity()),
        "line",
        d.getRange().getStart().getLine() + 1,
        "column",
        d.getRange().getStart().getCharacter() + 1,
        "message",
        messageText(d));
  }

  private static Map<String, Object> locationMap(final LatheLocation t) {
    return Map.<String, Object>of(
        "uri", t.uri(),
        "origin", t.origin().name(),
        "startLine", t.range().getStart().getLine() + 1,
        "startColumn", t.range().getStart().getCharacter() + 1,
        "snippet", t.snippet());
  }

  private static String diagnosticLine(final Diagnostic d) {
    return "  %s [%d:%d] %s"
        .formatted(
            d.getSeverity(),
            d.getRange().getStart().getLine() + 1,
            d.getRange().getStart().getCharacter() + 1,
            messageText(d));
  }

  private static String messageText(final Diagnostic d) {
    final var message = d.getMessage();
    return message.isLeft() ? message.getLeft() : message.getRight().getValue();
  }

  private static Path filePath(final CallToolRequest request) {
    final Object file = request.arguments().get("file");
    return file == null ? null : Path.of(file.toString());
  }

  private static Integer intArg(final CallToolRequest request, final String name) {
    return request.arguments().get(name) instanceof Number n ? n.intValue() : null;
  }

  private static CallToolResult error(final String message) {
    return CallToolResult.builder().addTextContent(message).isError(true).build();
  }
}
