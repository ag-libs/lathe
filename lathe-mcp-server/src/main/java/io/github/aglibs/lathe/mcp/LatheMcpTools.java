package io.github.aglibs.lathe.mcp;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.engine.LatheCall;
import io.github.aglibs.lathe.server.engine.LatheCallHierarchy;
import io.github.aglibs.lathe.server.engine.LatheChangeImpact;
import io.github.aglibs.lathe.server.engine.LatheEngine;
import io.github.aglibs.lathe.server.engine.LatheEngine.CallDirection;
import io.github.aglibs.lathe.server.engine.LatheEngine.TestScope;
import io.github.aglibs.lathe.server.engine.LatheFileEdit;
import io.github.aglibs.lathe.server.engine.LatheImplementations;
import io.github.aglibs.lathe.server.engine.LatheLocation;
import io.github.aglibs.lathe.server.engine.LatheModuleDiagnostics;
import io.github.aglibs.lathe.server.engine.LatheReferences;
import io.github.aglibs.lathe.server.engine.LatheRename;
import io.github.aglibs.lathe.server.engine.LatheSymbol;
import io.github.aglibs.lathe.server.engine.LatheTestFailure;
import io.github.aglibs.lathe.server.engine.LatheTestRun;
import io.github.aglibs.lathe.server.engine.LatheVerifyChange;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
  private static final int MAX_ARG_VALUE_CHARS = 200;

  private LatheMcpTools() {}

  static List<SyncToolSpecification> all(final LatheEngine engine, final McpJsonMapper mapper) {
    return List.of(
        diagnostics(engine, mapper),
        definition(engine, mapper),
        references(engine, mapper),
        rename(engine, mapper),
        runTest(engine, mapper),
        callHierarchy(engine, mapper),
        describeSymbol(engine, mapper),
        searchSymbols(engine, mapper),
        findImplementations(engine, mapper),
        analyzeChange(engine, mapper),
        verifyChange(engine, mapper));
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
                   "description":"Absolute path to a .java file in this project."}}}\
                """)
            .description(
                """
                Compiler-accurate errors and warnings for a Java file, exactly as javac sees \
                them on the real build classpath. Call after editing a file to check whether \
                it compiles — no Maven, no whole-project build.\
                """)
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) ->
                logged("get_diagnostics", request, () -> handleDiagnostics(engine, request)))
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
                   "column":{"type":"integer","description":"1-based column of the symbol."}}}\
                """)
            .description(
                """
                Resolve the symbol at a position to its definition — across modules and into \
                dependency, JDK, and generated sources — returning each target with a source \
                snippet, not just a file:line.\
                """)
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) ->
                logged("get_definition", request, () -> handleDefinition(engine, request)))
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
                     "description":"Max references to return (default 50)."}}}\
                """)
            .description(
                """
                Find every real use of the symbol at a position across the whole reactor — \
                javac-accurate, not text search: resolves overloads and inheritance, spans all \
                modules, and returns each use with a source snippet. Use before changing or \
                removing a symbol, and especially when it is a method with overrides/\
                implementations or a common/overloaded name where text search is ambiguous; for a \
                rare, distinctive name a plain grep is fine.\
                """)
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) ->
                logged("find_references", request, () -> handleReferences(engine, request)))
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
                   "newName":{"type":"string","description":"The new identifier."}}}\
                """)
            .description(
                """
                Rename the symbol at a position across the whole reactor and apply the edits to \
                disk — javac-accurate, so it renames only the true declaration and its uses \
                (respecting overloads and shadowing locals) across every module, never a text \
                match. Refuses if it would touch a file outside the reactor. After a cross-module \
                rename, rebuild the reactor to confirm it still compiles.\
                """)
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) ->
                logged("rename_symbol", request, () -> handleRename(engine, request)))
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
                     "description":"Test method name (required when scope=method)."}}}\
                """)
            .description(
                """
                Replay a test against the compiled classpath — no reactor build. Runs one method, \
                the whole test class, or its package; prefer it over `mvn test` for a single \
                target after an edit. Returns pass/fail/skip counts and each failure's message and \
                line. Needs a captured test-launch.json (run `mvn test` once); it reports that if \
                missing.\
                """)
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) ->
                logged("run_test", request, () -> handleRunTest(engine, request)))
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
                   "maxResults":{"type":"integer","description":"Max calls to return (default 50)."}}}\
                """)
            .description(
                """
                Trace the symbol's callers (incoming) or the methods it calls (outgoing) across the \
                whole reactor — javac-accurate, following the real call graph (resolving overrides \
                and cross-module edges) with a snippet per call. Use to scope the impact of a \
                change; text search cannot follow calls.\
                """)
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) ->
                logged("call_hierarchy", request, () -> handleCallHierarchy(engine, request)))
        .build();
  }

  private static SyncToolSpecification describeSymbol(
      final LatheEngine engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder(
                "describe_symbol",
                mapper,
                """
                {"type":"object","required":["file","line","column"],
                 "properties":{
                   "file":{"type":"string","description":"Absolute path to a .java file."},
                   "line":{"type":"integer","description":"1-based line of the symbol."},
                   "column":{"type":"integer","description":"1-based column of the symbol."}}}\
                """)
            .description(
                """
                Describe the symbol at a position — its rendered signature, type, and javadoc as \
                markdown — exactly as the compiler sees it, without opening the file. Use to \
                understand an API before calling it.\
                """)
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) ->
                logged("describe_symbol", request, () -> handleDescribe(engine, request)))
        .build();
  }

  private static SyncToolSpecification searchSymbols(
      final LatheEngine engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder(
                "search_symbols",
                mapper,
                """
                {"type":"object","required":["query"],
                 "properties":{
                   "query":{"type":"string","description":"Symbol name (CamelHumps supported)."},
                   "maxResults":{"type":"integer","description":"Max symbols to return (default 50)."}}}\
                """)
            .description(
                """
                Find a type or symbol by name across the whole reactor, its dependencies, and the \
                JDK — returning each with its kind, container, and a declaration snippet. Use to \
                locate a type when you know its name but not its file.\
                """)
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) ->
                logged("search_symbols", request, () -> handleSearchSymbols(engine, request)))
        .build();
  }

  private static SyncToolSpecification findImplementations(
      final LatheEngine engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder(
                "find_implementations",
                mapper,
                """
                {"type":"object","required":["file","line","column"],
                 "properties":{
                   "file":{"type":"string","description":"Absolute path to a .java file."},
                   "line":{"type":"integer","description":"1-based line of the symbol."},
                   "column":{"type":"integer","description":"1-based column of the symbol."},
                   "maxResults":{"type":"integer",
                     "description":"Max implementations to return (default 50)."}}}\
                """)
            .description(
                """
                Find the implementations of the interface — or the overrides of the method — at a \
                position, across the whole reactor, javac-accurate and with a snippet each. This is \
                the "who implements X / what overrides this" question text search cannot answer.\
                """)
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) ->
                logged(
                    "find_implementations",
                    request,
                    () -> handleFindImplementations(engine, request)))
        .build();
  }

  private static SyncToolSpecification analyzeChange(
      final LatheEngine engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder(
                "analyze_change",
                mapper,
                """
                {"type":"object","required":["file","line","column"],
                 "properties":{
                   "file":{"type":"string","description":"Absolute path to a .java file."},
                   "line":{"type":"integer","description":"1-based line of the symbol."},
                   "column":{"type":"integer","description":"1-based column of the symbol."}}}""")
            .description(
                """
                Before editing a symbol, preview its blast radius — javac-accurate and cross-module: \
                its override/implementation family, how many production vs test references it has, \
                which reactor modules use it, and the relevant test classes. Use it to scope a rename \
                or signature change before making it.""")
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) ->
                logged("analyze_change", request, () -> handleAnalyzeChange(engine, request)))
        .build();
  }

  private static SyncToolSpecification verifyChange(
      final LatheEngine engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder(
                "verify_change",
                mapper,
                """
                {"type":"object",
                 "properties":{"files":{"type":"array","items":{"type":"string"},
                   "description":"Optional absolute paths of changed .java files; omit to auto-detect from disk."}}}\
                """)
            .description(
                """
                After editing Java files, recompile just the changed set in-process against the real \
                classpath — no full Maven build — and report the new compiler diagnostics grouped by \
                module. Detects the change set from disk automatically, or pass `files` to scope it. \
                When the change reaches other modules it returns the exact scoped `mvn` command to \
                verify the remainder. Use it to confirm your edits still compile before a build.\
                """)
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) ->
                logged("verify_change", request, () -> handleVerifyChange(engine, request)))
        .build();
  }

  // One INFO line per tool call — the adoption/usage/latency signal (visible without LATHE_DEBUG).
  private static CallToolResult logged(
      final String tool, final CallToolRequest request, final Supplier<CallToolResult> body) {
    final var t = Stopwatch.start();
    final CallToolResult result = body.get();
    final boolean ok = !Boolean.TRUE.equals(result.isError());
    LOG.info(
        () ->
            "[tool] %s %s %dms %s"
                .formatted(tool, argsSummary(request), t.elapsedMs(), ok ? "ok" : "error"));
    return result;
  }

  // Compact "key=value" of the call arguments for the usage log; values are paths, positions,
  // identifiers, and queries — never source content — each capped defensively.
  private static String argsSummary(final CallToolRequest request) {
    return request.arguments().entrySet().stream()
        .map(entry -> "%s=%s".formatted(entry.getKey(), capped(entry.getValue())))
        .collect(Collectors.joining(" "));
  }

  private static String capped(final Object value) {
    final String text = String.valueOf(value);
    return text.length() <= MAX_ARG_VALUE_CHARS
        ? text
        : "%s…".formatted(text.substring(0, MAX_ARG_VALUE_CHARS));
  }

  private static CallToolResult handleDiagnostics(
      final LatheEngine engine, final CallToolRequest request) {
    final Path file = filePath(request);
    if (file == null) {
      return error("get_diagnostics requires a 'file' argument");
    }

    return guarded(
        "get_diagnostics",
        file,
        () -> diagnosticsResult(file, engine.diagnostics(file), engine.staleModules()));
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

  private static CallToolResult handleFindImplementations(
      final LatheEngine engine, final CallToolRequest request) {
    return atPosition(
        "find_implementations",
        request,
        (file, line, column) ->
            implementationsResult(
                engine.findImplementations(file, line, column, maxResults(request)),
                engine.staleModules()));
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

    return guarded(
        "run_test",
        file,
        () -> testRunResult(engine.runTest(file, scope, method), engine.staleModules()));
  }

  private static CallToolResult handleAnalyzeChange(
      final LatheEngine engine, final CallToolRequest request) {
    return atPosition(
        "analyze_change",
        request,
        (file, line, column) ->
            analyzeResult(engine.analyzeChange(file, line, column), engine.staleModules()));
  }

  private static CallToolResult handleVerifyChange(
      final LatheEngine engine, final CallToolRequest request) {
    return guarded(
        "verify_change",
        "files",
        () -> verifyChangeResult(engine.verifyChange(fileListArg(request))));
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

  private static CallToolResult handleDescribe(
      final LatheEngine engine, final CallToolRequest request) {
    return atPosition(
        "describe_symbol",
        request,
        (file, line, column) ->
            describeResult(engine.describe(file, line, column), engine.staleModules()));
  }

  private static CallToolResult handleSearchSymbols(
      final LatheEngine engine, final CallToolRequest request) {
    final String query = stringArg(request, "query");
    if (query == null || query.isBlank()) {
      return error("search_symbols requires a 'query' argument");
    }

    return guarded(
        "search_symbols",
        query,
        () ->
            searchResult(
                query, engine.searchSymbols(query, maxResults(request)), engine.staleModules()));
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

    return guarded(tool, file, () -> handler.at(file, line - 1, column - 1));
  }

  // Run an engine call, turning an unexpected failure into a logged isError result.
  private static CallToolResult guarded(
      final String tool, final Object context, final Supplier<CallToolResult> body) {
    try {
      return body.get();
    } catch (final RuntimeException e) {
      LOG.log(Level.SEVERE, e, () -> "[%s] failed for %s".formatted(tool, context));
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
    return locationListResult(
        "reference", refs.total(), refs.truncated(), refs.references(), stale);
  }

  private static CallToolResult implementationsResult(
      final LatheImplementations impls, final List<String> stale) {
    return locationListResult(
        "implementation", impls.total(), impls.truncated(), impls.implementations(), stale);
  }

  // Shared rendering for a capped, ranked location list. noun drives both the text ("N noun(s)")
  // and the structured list key (noun + "s").
  private static CallToolResult locationListResult(
      final String noun,
      final int total,
      final boolean truncated,
      final List<LatheLocation> locations,
      final List<String> stale) {
    final List<Map<String, Object>> items =
        locations.stream().map(LatheMcpTools::locationMap).toList();
    final String header =
        truncated
            ? "%d %ss (showing first %d):".formatted(total, noun, locations.size())
            : "%d %s(s):".formatted(total, noun);
    final String text =
        locations.isEmpty()
            ? "No %ss found.".formatted(noun)
            : "%s%n%s".formatted(header, locationLines(locations));
    return result(
        text,
        Map.<String, Object>of(
            "total", total, "truncated", truncated, "%ss".formatted(noun), items),
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

  private static CallToolResult describeResult(final String markup, final List<String> stale) {
    final String text = markup.isBlank() ? "No symbol information at that position." : markup;
    return result(text, Map.<String, Object>of("markup", markup), stale);
  }

  private static CallToolResult searchResult(
      final String query, final List<LatheSymbol> symbols, final List<String> stale) {
    final String text =
        symbols.isEmpty()
            ? "No symbols matching '%s'.".formatted(query)
            : "%d symbol(s):%n%s"
                .formatted(
                    symbols.size(),
                    symbols.stream()
                        .map(LatheMcpTools::symbolLine)
                        .collect(Collectors.joining(System.lineSeparator())));
    return result(
        text,
        Map.<String, Object>of(
            "query", query,
            "total", symbols.size(),
            "symbols", symbols.stream().map(LatheMcpTools::symbolMap).toList()),
        stale);
  }

  private static String symbolLine(final LatheSymbol symbol) {
    final String where = symbol.container().isBlank() ? "" : " — %s".formatted(symbol.container());
    return "%s (%s)%s%n%s"
        .formatted(symbol.name(), symbol.kind(), where, symbol.location().snippet());
  }

  private static Map<String, Object> symbolMap(final LatheSymbol symbol) {
    final LatheLocation loc = symbol.location();
    return Map.<String, Object>of(
        "name", symbol.name(),
        "kind", symbol.kind(),
        "container", symbol.container(),
        "uri", loc.uri(),
        "startLine", loc.range().getStart().getLine() + 1,
        "snippet", loc.snippet());
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

  private static CallToolResult analyzeResult(
      final LatheChangeImpact impact, final List<String> stale) {
    final String overrides =
        impact.overrideFamily().isEmpty()
            ? "none"
            : "%d%n%s"
                .formatted(impact.overrideFamily().size(), locationLines(impact.overrideFamily()));
    final String text =
        """
        %s
        references: %d production, %d test%s
        affected modules: %s
        relevant tests: %s
        override family: %s"""
            .formatted(
                impact.signature().isBlank() ? "(no symbol at that position)" : impact.signature(),
                impact.productionRefs(),
                impact.testRefs(),
                impact.referencesTruncated() ? " (truncated)" : "",
                joinOrNone(impact.affectedModules()),
                joinOrNone(impact.relevantTests()),
                overrides);
    return result(
        text,
        Map.<String, Object>of(
            "signature", impact.signature(),
            "productionRefs", impact.productionRefs(),
            "testRefs", impact.testRefs(),
            "referencesTruncated", impact.referencesTruncated(),
            "affectedModules", impact.affectedModules(),
            "relevantTests", impact.relevantTests(),
            "overrideFamily",
                impact.overrideFamily().stream().map(LatheMcpTools::locationMap).toList()),
        stale);
  }

  private static String joinOrNone(final List<String> items) {
    return items.isEmpty() ? "none" : String.join(", ", items);
  }

  private static CallToolResult verifyChangeResult(final LatheVerifyChange verify) {
    if (verify.deferral() != null) {
      final String text =
          "Verification deferred: %s. Run `%s`, then re-check."
              .formatted(deferralText(verify.deferral()), verify.suggestedMvn());
      return result(
          text,
          Map.<String, Object>of(
              "deferred", verify.deferral(), "suggestedMvn", verify.suggestedMvn()),
          List.of());
    }

    final int total = verify.perModule().stream().mapToInt(m -> m.diagnostics().size()).sum();
    final String head =
        total == 0
            ? "No new diagnostics — the changed files compile cleanly."
            : "%d diagnostic(s) after recompile:%n%s"
                .formatted(total, moduleDiagnosticsLines(verify.perModule()));
    return result(head + crossModuleNote(verify), verifyStructured(verify, total), List.of());
  }

  private static String crossModuleNote(final LatheVerifyChange verify) {
    if (verify.suggestedMvn() == null) {
      return "";
    }

    return "%n%n%d module(s) depend on your change (%s); verify them with:%n  %s"
        .formatted(
            verify.affectedModules().size(),
            String.join(", ", verify.affectedModules()),
            verify.suggestedMvn());
  }

  private static Map<String, Object> verifyStructured(
      final LatheVerifyChange verify, final int total) {
    final var structured = new LinkedHashMap<String, Object>();
    structured.put("diagnosticCount", total);
    structured.put(
        "perModule", verify.perModule().stream().map(LatheMcpTools::moduleDiagnosticsMap).toList());
    structured.put("affectedModules", verify.affectedModules());
    if (verify.suggestedMvn() != null) {
      structured.put("suggestedMvn", verify.suggestedMvn());
    }

    return structured;
  }

  private static String moduleDiagnosticsLines(final List<LatheModuleDiagnostics> perModule) {
    return perModule.stream()
        .map(LatheMcpTools::moduleDiagnosticsLine)
        .collect(Collectors.joining(System.lineSeparator()));
  }

  private static String moduleDiagnosticsLine(final LatheModuleDiagnostics module) {
    return "%s:%n%s"
        .formatted(
            module.module(),
            module.diagnostics().stream()
                .map(LatheMcpTools::diagnosticLine)
                .collect(Collectors.joining(System.lineSeparator())));
  }

  private static Map<String, Object> moduleDiagnosticsMap(final LatheModuleDiagnostics module) {
    return Map.<String, Object>of(
        "module",
        module.module(),
        "diagnostics",
        module.diagnostics().stream().map(LatheMcpTools::diagnosticMap).toList());
  }

  private static String deferralText(final String reason) {
    return switch (reason) {
      case "POM_PENDING" -> "a pending POM/dependency change needs a full build";
      case "BULK_CHANGE" -> "too many changed files for an in-process recompile";
      case "BUILD_IN_PROGRESS" -> "a reactor build is in progress";
      default -> reason;
    };
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

  // Optional "files" array of absolute paths; absent or malformed means "detect the change set".
  private static List<Path> fileListArg(final CallToolRequest request) {
    if (!(request.arguments().get("files") instanceof List<?> files)) {
      return List.of();
    }

    return files.stream().map(String::valueOf).map(Path::of).toList();
  }

  private static CallToolResult error(final String message) {
    return CallToolResult.builder().addTextContent(message).isError(true).build();
  }
}
