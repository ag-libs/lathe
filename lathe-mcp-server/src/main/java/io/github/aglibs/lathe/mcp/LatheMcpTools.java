package io.github.aglibs.lathe.mcp;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.engine.LatheCall;
import io.github.aglibs.lathe.server.engine.LatheCallHierarchy;
import io.github.aglibs.lathe.server.engine.LatheEngine;
import io.github.aglibs.lathe.server.engine.LatheEngine.CallDirection;
import io.github.aglibs.lathe.server.engine.LatheEngine.TestScope;
import io.github.aglibs.lathe.server.engine.LatheFileEdit;
import io.github.aglibs.lathe.server.engine.LatheLocation;
import io.github.aglibs.lathe.server.engine.LatheReferences;
import io.github.aglibs.lathe.server.engine.LatheRename;
import io.github.aglibs.lathe.server.engine.LatheTestFailure;
import io.github.aglibs.lathe.server.engine.LatheTestRun;
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
import javax.lang.model.SourceVersion;
import org.eclipse.lsp4j.Diagnostic;

/**
 * The MCP tool surface over a {@link LatheEngine}. Bad input or an engine failure becomes an {@code
 * isError} result rather than a thrown exception.
 */
final class LatheMcpTools {

  private static final Logger LOG = Logger.getLogger(LatheMcpTools.class.getName());
  private static final int DEFAULT_MAX_RESULTS = 50;

  private LatheMcpTools() {}

  static List<SyncToolSpecification> all(final LatheEngine engine, final McpJsonMapper mapper) {
    return List.of(
        diagnostics(engine, mapper),
        definition(engine, mapper),
        references(engine, mapper),
        rename(engine, mapper),
        runTest(engine, mapper),
        callHierarchy(engine, mapper));
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

  private static SyncToolSpecification references(
      final LatheEngine engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder(
                "find_references",
                mapper,
                """
                {"type":"object","required":["file","line","column"],
                 "properties":{
                   "file":{"type":"string","description":"Absolute path to a .java file."},
                   "line":{"type":"integer","description":"1-based line of the symbol."},
                   "column":{"type":"integer","description":"1-based column of the symbol."},
                   "maxResults":{"type":"integer",
                     "description":"Max references to return (default 50)."}}}""")
            .description(
                """
                Find every real use of the symbol at a position across the whole reactor — \
                javac-accurate, not text search: resolves overloads and inheritance, spans all \
                modules, and returns each use with a source snippet. Use before changing or \
                removing a symbol, and especially when it is a method with overrides/\
                implementations or a common/overloaded name where text search is ambiguous; for a \
                rare, distinctive name a plain grep is fine.""")
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) ->
                logged("find_references", () -> handleReferences(engine, request)))
        .build();
  }

  private static SyncToolSpecification rename(
      final LatheEngine engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder(
                "rename_symbol",
                mapper,
                """
                {"type":"object","required":["file","line","column","newName"],
                 "properties":{
                   "file":{"type":"string","description":"Absolute path to a .java file."},
                   "line":{"type":"integer","description":"1-based line of the symbol."},
                   "column":{"type":"integer","description":"1-based column of the symbol."},
                   "newName":{"type":"string","description":"The new identifier."}}}""")
            .description(
                """
                Rename the symbol at a position across the whole reactor and apply the edits to \
                disk — javac-accurate, so it renames only the true declaration and its uses \
                (respecting overloads and shadowing locals) across every module, never a text \
                match. Refuses if it would touch a file outside the reactor. After a cross-module \
                rename, rebuild the reactor to confirm it still compiles.""")
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) -> logged("rename_symbol", () -> handleRename(engine, request)))
        .build();
  }

  private static SyncToolSpecification runTest(
      final LatheEngine engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder(
                "run_test",
                mapper,
                """
                {"type":"object","required":["file"],
                 "properties":{
                   "file":{"type":"string","description":"Absolute path to a test .java file."},
                   "scope":{"type":"string","enum":["class","method","package"],
                     "description":"What to run (default class)."},
                   "method":{"type":"string",
                     "description":"Test method name (required when scope=method)."}}}""")
            .description(
                """
                Replay a test against the compiled classpath — no reactor build. Runs one method, \
                the whole test class, or its package; prefer it over `mvn test` for a single \
                target after an edit. Returns pass/fail/skip counts and each failure's message and \
                line. Needs a captured test-launch.json (run `mvn test` once); it reports that if \
                missing.""")
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) -> logged("run_test", () -> handleRunTest(engine, request)))
        .build();
  }

  private static SyncToolSpecification callHierarchy(
      final LatheEngine engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder(
                "call_hierarchy",
                mapper,
                """
                {"type":"object","required":["file","line","column"],
                 "properties":{
                   "file":{"type":"string","description":"Absolute path to a .java file."},
                   "line":{"type":"integer","description":"1-based line of the symbol."},
                   "column":{"type":"integer","description":"1-based column of the symbol."},
                   "direction":{"type":"string","enum":["incoming","outgoing"],
                     "description":"incoming = callers of the symbol (default); outgoing = what it calls."},
                   "maxResults":{"type":"integer","description":"Max calls to return (default 50)."}}}""")
            .description(
                """
                Trace the symbol's callers (incoming) or the methods it calls (outgoing) across the \
                whole reactor — javac-accurate, following the real call graph (resolving overrides \
                and cross-module edges) with a snippet per call. Use to scope the impact of a \
                change; text search cannot follow calls.""")
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) ->
                logged("call_hierarchy", () -> handleCallHierarchy(engine, request)))
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
      return diagnosticsResult(file, engine.diagnostics(file), engine.staleModules());
    } catch (final RuntimeException e) {
      LOG.log(Level.SEVERE, e, () -> "[get_diagnostics] failed for %s".formatted(file));
      return error("[get_diagnostics] %s".formatted(e.getMessage()));
    }
  }

  private static CallToolResult handleDefinition(
      final LatheEngine engine, final CallToolRequest request) {
    return atPosition(
        "get_definition",
        request,
        (file, line, column) ->
            definitionResult(engine.definition(file, line, column), engine.staleModules()));
  }

  private static CallToolResult handleReferences(
      final LatheEngine engine, final CallToolRequest request) {
    return atPosition(
        "find_references",
        request,
        (file, line, column) ->
            referencesResult(
                engine.references(file, line, column, maxResults(request)), engine.staleModules()));
  }

  private static CallToolResult handleRename(
      final LatheEngine engine, final CallToolRequest request) {
    final String newName = stringArg(request, "newName");
    if (newName == null || !SourceVersion.isName(newName)) {
      return error("rename_symbol requires a valid Java identifier 'newName'");
    }

    return atPosition(
        "rename_symbol",
        request,
        (file, line, column) ->
            renameResult(engine.rename(file, line, column, newName), engine.staleModules()));
  }

  private static CallToolResult handleRunTest(
      final LatheEngine engine, final CallToolRequest request) {
    final Path file = filePath(request);
    if (file == null) {
      return error("run_test requires a 'file' argument");
    }

    final TestScope scope = testScope(stringArg(request, "scope"));
    if (scope == null) {
      return error("run_test 'scope' must be one of class, method, package");
    }

    final String method = stringArg(request, "method");
    if (scope == TestScope.METHOD && method == null) {
      return error("run_test with scope=method requires a 'method' argument");
    }

    try {
      return testRunResult(engine.runTest(file, scope, method), engine.staleModules());
    } catch (final RuntimeException e) {
      LOG.log(Level.SEVERE, e, () -> "[run_test] failed for %s".formatted(file));
      return error("[run_test] %s".formatted(e.getMessage()));
    }
  }

  // Optional, case-insensitive; absent means class, unknown means null (caller errors on it).
  private static TestScope testScope(final String scope) {
    return scope == null ? TestScope.CLASS : TestScope.from(scope).orElse(null);
  }

  private static CallToolResult handleCallHierarchy(
      final LatheEngine engine, final CallToolRequest request) {
    final String directionArg = stringArg(request, "direction");
    final CallDirection direction =
        directionArg == null
            ? CallDirection.INCOMING
            : CallDirection.from(directionArg).orElse(null);
    if (direction == null) {
      return error("call_hierarchy 'direction' must be one of incoming, outgoing");
    }

    return atPosition(
        "call_hierarchy",
        request,
        (file, line, column) ->
            callHierarchyResult(
                engine.callHierarchy(file, line, column, direction, maxResults(request)),
                engine.staleModules()));
  }

  // Shared body for the position-based tools: parse the 1-based {file,line,column} into a 0-based
  // position, run the handler, and turn missing args or an engine failure into an isError result.
  private static CallToolResult atPosition(
      final String tool, final CallToolRequest request, final PositionHandler handler) {
    final Path file = filePath(request);
    final Integer line = intArg(request, "line");
    final Integer column = intArg(request, "column");
    if (file == null || line == null || column == null) {
      return error("%s requires 'file', 'line', and 'column' arguments".formatted(tool));
    }

    try {
      return handler.at(file, line - 1, column - 1);
    } catch (final RuntimeException e) {
      LOG.log(Level.SEVERE, e, () -> "[%s] failed for %s".formatted(tool, file));
      return error("[%s] %s".formatted(tool, e.getMessage()));
    }
  }

  @FunctionalInterface
  private interface PositionHandler {
    CallToolResult at(Path file, int line, int column);
  }

  private static int maxResults(final CallToolRequest request) {
    final Integer max = intArg(request, "maxResults");
    return max != null ? max : DEFAULT_MAX_RESULTS;
  }

  private static CallToolResult referencesResult(
      final LatheReferences refs, final List<String> stale) {
    final List<Map<String, Object>> items =
        refs.references().stream().map(LatheMcpTools::locationMap).toList();
    final String header =
        refs.truncated()
            ? "%d references (showing first %d):".formatted(refs.total(), refs.references().size())
            : "%d reference(s):".formatted(refs.total());
    final String text =
        refs.references().isEmpty()
            ? "No references found."
            : "%s%n%s".formatted(header, locationLines(refs.references()));
    return result(
        text,
        Map.<String, Object>of(
            "total", refs.total(), "truncated", refs.truncated(), "references", items),
        stale);
  }

  private static CallToolResult renameResult(final LatheRename rename, final List<String> stale) {
    if (rename.totalEdits() == 0) {
      return result(
          "No references to rename.",
          Map.<String, Object>of("newName", rename.newName(), "totalEdits", 0, "files", List.of()),
          stale);
    }

    final List<Map<String, Object>> items =
        rename.files().stream().map(LatheMcpTools::fileEditMap).toList();
    final String text =
        "Renamed to %s: %d edit(s) across %d file(s):%n%s"
            .formatted(
                rename.newName(),
                rename.totalEdits(),
                rename.files().size(),
                rename.files().stream()
                    .map(LatheMcpTools::fileEditLine)
                    .collect(Collectors.joining(System.lineSeparator())));
    return result(
        text,
        Map.<String, Object>of(
            "newName", rename.newName(), "totalEdits", rename.totalEdits(), "files", items),
        stale);
  }

  private static CallToolResult callHierarchyResult(
      final LatheCallHierarchy ch, final List<String> stale) {
    final String noun = ch.incoming() ? "caller" : "callee";
    final String arrow = ch.incoming() ? "←" : "→";
    final String header =
        ch.truncated()
            ? "%d %ss (showing first %d):".formatted(ch.total(), noun, ch.calls().size())
            : "%d %s(s):".formatted(ch.total(), noun);
    final String text =
        ch.calls().isEmpty()
            ? "No %ss found.".formatted(noun)
            : "%s%n%s"
                .formatted(
                    header,
                    ch.calls().stream()
                        .map(call -> callLine(arrow, call))
                        .collect(Collectors.joining(System.lineSeparator())));
    return result(
        text,
        Map.<String, Object>of(
            "incoming", ch.incoming(),
            "total", ch.total(),
            "truncated", ch.truncated(),
            "calls", ch.calls().stream().map(LatheMcpTools::callMap).toList()),
        stale);
  }

  private static String callLine(final String arrow, final LatheCall call) {
    return "%s %s [%s]%n%s"
        .formatted(arrow, call.name(), call.location().origin(), call.location().snippet());
  }

  private static Map<String, Object> callMap(final LatheCall call) {
    final LatheLocation loc = call.location();
    return Map.<String, Object>of(
        "name", call.name(),
        "uri", loc.uri(),
        "origin", loc.origin().name(),
        "startLine", loc.range().getStart().getLine() + 1,
        "snippet", loc.snippet());
  }

  private static CallToolResult testRunResult(final LatheTestRun run, final List<String> stale) {
    if (!run.launched()) {
      return result(
          "Test run blocked: %s".formatted(String.join("; ", run.blockedReasons())),
          Map.<String, Object>of("launched", false, "blockedReasons", run.blockedReasons()),
          stale);
    }

    final String header =
        "%s — %d passed, %d failed, %d skipped"
            .formatted(
                run.failed() == 0 ? "PASS" : "FAIL", run.passed(), run.failed(), run.skipped());
    final String text =
        run.failures().isEmpty()
            ? header
            : "%s%n%s"
                .formatted(
                    header,
                    run.failures().stream()
                        .map(LatheMcpTools::testFailureLine)
                        .collect(Collectors.joining(System.lineSeparator())));
    return result(
        text,
        Map.<String, Object>of(
            "launched", true,
            "passed", run.passed(),
            "failed", run.failed(),
            "skipped", run.skipped(),
            "failures", run.failures().stream().map(LatheMcpTools::testFailureMap).toList()),
        stale);
  }

  private static String testFailureLine(final LatheTestFailure f) {
    return "  %s:%d — %s".formatted(f.test(), f.line(), f.summary());
  }

  private static Map<String, Object> testFailureMap(final LatheTestFailure f) {
    return Map.<String, Object>of("test", f.test(), "line", f.line(), "summary", f.summary());
  }

  private static CallToolResult diagnosticsResult(
      final Path file, final List<Diagnostic> diagnostics, final List<String> stale) {
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
    return result(
        text, Map.<String, Object>of("file", file.toString(), "diagnostics", items), stale);
  }

  private static CallToolResult definitionResult(
      final List<LatheLocation> targets, final List<String> stale) {
    final List<Map<String, Object>> items =
        targets.stream().map(LatheMcpTools::locationMap).toList();
    final String text = targets.isEmpty() ? "No definition found." : locationLines(targets);
    return result(text, Map.<String, Object>of("targets", items), stale);
  }

  private static String locationLines(final List<LatheLocation> locations) {
    return locations.stream()
        .map(LatheMcpTools::locationLine)
        .collect(Collectors.joining(System.lineSeparator()));
  }

  private static String locationLine(final LatheLocation location) {
    return "→ %s [%s]%n%s".formatted(location.uri(), location.origin(), location.snippet());
  }

  private static CallToolResult result(
      final String text, final Map<String, Object> structured, final List<String> stale) {
    final String body =
        stale.isEmpty()
            ? text
            : "%s%n%n%s"
                .formatted(text, LatheLayout.STALE_REMEDIATION.formatted(String.join(", ", stale)));
    return CallToolResult.builder().addTextContent(body).structuredContent(structured).build();
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

  private static String fileEditLine(final LatheFileEdit f) {
    return "  %s [%s] — %d edit(s)".formatted(f.uri(), f.origin(), f.editCount());
  }

  private static Map<String, Object> fileEditMap(final LatheFileEdit f) {
    return Map.<String, Object>of(
        "uri", f.uri(), "origin", f.origin().name(), "editCount", f.editCount());
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
    final String file = stringArg(request, "file");
    return file == null ? null : Path.of(file);
  }

  private static String stringArg(final CallToolRequest request, final String name) {
    final Object value = request.arguments().get(name);
    return value == null ? null : value.toString();
  }

  private static Integer intArg(final CallToolRequest request, final String name) {
    return request.arguments().get(name) instanceof Number n ? n.intValue() : null;
  }

  private static CallToolResult error(final String message) {
    return CallToolResult.builder().addTextContent(message).isError(true).build();
  }
}
