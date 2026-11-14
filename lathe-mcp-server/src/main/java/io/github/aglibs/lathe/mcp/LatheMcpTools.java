package io.github.aglibs.lathe.mcp;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.LatheEngine;
import io.github.aglibs.lathe.server.LatheEngine.CallDirection;
import io.github.aglibs.lathe.server.LatheEngine.SymbolScope;
import io.github.aglibs.lathe.server.LatheEngine.TestScope;
import io.github.aglibs.lathe.server.engine.LatheNotes;
import io.github.aglibs.lathe.server.engine.LatheRename;
import io.github.aglibs.lathe.server.engine.Noted;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * The MCP tool surface over a {@link LatheEngine}. Bad input or an engine failure becomes an {@code
 * isError} result rather than a thrown exception.
 */
final class LatheMcpTools {

  private static final Logger LOG = Logger.getLogger(LatheMcpTools.class.getName());
  private static final int DEFAULT_MAX_RESULTS = 50;
  private static final int MAX_ARG_VALUE_CHARS = 200;

  // An unset destructiveHint reads as true, so a writing tool that destroys nothing says so.
  private static final ToolAnnotations READ_ONLY =
      ToolAnnotations.builder().readOnlyHint(true).build();
  private static final ToolAnnotations REWRITES_SOURCE =
      ToolAnnotations.builder().readOnlyHint(false).destructiveHint(true).build();
  private static final ToolAnnotations RUNS_CODE =
      ToolAnnotations.builder().readOnlyHint(false).destructiveHint(false).build();
  private static final ToolAnnotations EDITS_IN_PLACE =
      ToolAnnotations.builder()
          .readOnlyHint(false)
          .destructiveHint(false)
          .idempotentHint(true)
          .build();

  private static final String FILE_SCHEMA =
      """
      {"type":"object","required":["file"],
       "properties":{"file":{"type":"string",
         "description":"Absolute path to a .java file in this project."}}}""";

  private LatheMcpTools() {}

  // The symbol-at-a-position input every position tool takes: a column, or the symbol's name on
  // the line, which agents give more reliably than a counted column.
  private static final String POSITION_PROPERTIES =
      """
      "file":{"type":"string","description":"Absolute path to a .java file."},
      "line":{"type":"integer","description":"1-based line of the symbol."},
      "column":{"type":"integer","description":"1-based column of the symbol; optional with 'symbol'."},
      "symbol":{"type":"string","description":"The symbol's name as written on that line (a method, field, type or variable name); finds its column, so 'column' may be left out."}\
      """;

  // The position input plus a tool's own properties (each starting with a comma).
  private static String positionSchema(final String extraProperties) {
    return """
        {"type":"object","required":["file","line"],
         "properties":{%s%s}}"""
        .formatted(POSITION_PROPERTIES, extraProperties);
  }

  // The engine is taken when a tool is called: started where no .lathe/ is, there is none, and each
  // tool answers why (NoWorkspaceException).
  static List<SyncToolSpecification> all(
      final Supplier<LatheEngine> engine, final McpJsonMapper mapper) {
    return List.of(
        tool(
            engine,
            mapper,
            "get_diagnostics",
            FILE_SCHEMA,
            READ_ONLY,
            """
                Compiler-accurate errors and warnings (plus unused-code hints) for a Java file, \
                exactly as javac sees them on the real build classpath. Call after editing a file to check whether \
                it compiles — no whole-project build.\
                """,
            LatheMcpTools::handleDiagnostics),
        tool(
            engine,
            mapper,
            "get_definition",
            positionSchema(""),
            READ_ONLY,
            """
                Resolve the symbol at a position to its definition — across modules and into \
                dependency, JDK, and generated sources — returning each target as \
                `path:line:column: code`, its source line included.\
                """,
            LatheMcpTools::handleDefinition),
        tool(
            engine,
            mapper,
            "find_references",
            positionSchema(
                """
                    ,"maxResults":{"type":"integer","minimum":1,
                     "description":"Max references to return (default 50)."}\
                    """),
            READ_ONLY,
            """
                Find every real use of the symbol at a position across the whole reactor — \
                javac-accurate, not text search: resolves overloads and inheritance, spans all \
                modules, and returns each use as one `path:line:column: code` line. It finds uses a plain grep \
                misses or misattributes — a symbol reached through a variable, or one of several \
                overloads that share a name — and excludes unrelated symbols that merely share the \
                name, so you need not re-check with grep. Use before changing or removing a symbol, \
                especially a method \
                with overrides/implementations or a common/overloaded name; for a rare, distinctive \
                name a plain grep is fine.\
                """,
            LatheMcpTools::handleReferences),
        tool(
            engine,
            mapper,
            "rename_symbol",
            """
                {"type":"object","required":["file","line","newName"],
                 "properties":{%s,
                   "newName":{"type":"string","description":"The new identifier."}}}"""
                .formatted(POSITION_PROPERTIES),
            REWRITES_SOURCE,
            """
                Rename the symbol at a position across the whole reactor and apply the edits to \
                disk — javac-accurate, so it renames only the true declaration and its uses \
                (respecting overloads and shadowing locals) across every module, never a text \
                match. It updates uses grep+sed would miss or silently rebind to the wrong overload, \
                and reports exactly which files it changed, then lists where the old name is still \
                text (string literals, qualified in resources) for you to review; it edits none. Renaming a public top-level type also \
                renames its file. Refuses if it would touch a file outside the reactor, or if the \
                symbol (or a method it overrides) is declared in the JDK, a dependency, or \
                generated source, or if it would change what a name means: a use the new name \
                would capture (a shadowed field, a call picking the renamed overload) or a renamed \
                use that would then refer to something else; the error names the file and line. \
                The changed files are compiled before it returns, so follow-up \
                queries see the new names. Then \
                call verify_change with no arguments to check the rewritten files and their callers, \
                and run the sync it suggests for other modules.\
                """,
            LatheMcpTools::handleRename),
        tool(
            engine,
            mapper,
            "run_test",
            """
                {"type":"object","required":["file"],
                 "properties":{
                   "file":{"type":"string","description":"Absolute path to a test .java file."},
                   "scope":{"type":"string","enum":["class","method","package"],
                     "description":"What to run (default class)."},
                   "method":{"type":"string",
                     "description":"Test method name (required when scope=method)."}}}\
                """,
            RUNS_CODE,
            """
                Replay a test against the compiled classpath — no full build. Runs one method, \
                the whole test class, or its package; prefer it over the build's own test command \
                for a single target after an edit. Returns pass/fail/skip counts and each failure's \
                message, line and last printed lines (the run's last lines when no test ran). Needs a captured test-launch.json (run `%s` once); it reports \
                that if missing.\
                """
                .formatted(LatheLayout.captureTestsCommand("<module>")),
            LatheMcpTools::handleRunTest),
        tool(
            engine,
            mapper,
            "call_hierarchy",
            positionSchema(
                """
                    ,"direction":{"type":"string","enum":["incoming","outgoing"],
                     "description":"incoming = callers of the symbol (default); outgoing = what it calls."},
                    "scope":{"type":"string","enum":["reactor","all"],
                     "description":"reactor (default) leaves out calls into the JDK and dependencies; all keeps them."},
                    "maxResults":{"type":"integer","minimum":1,"description":"Max calls to return (default 50)."}\
                    """),
            READ_ONLY,
            """
                Trace the symbol's callers (incoming) or the methods it calls (outgoing) across the \
                whole reactor — javac-accurate, following the real call graph (resolving overrides \
                and cross-module edges), one `path:line:column: code` line per call site: for outgoing, \
                where the method makes each call, in source order. Use to scope the impact of a \
                change; text search cannot follow calls.\
                """,
            LatheMcpTools::handleCallHierarchy),
        tool(
            engine,
            mapper,
            "describe_symbol",
            positionSchema(""),
            READ_ONLY,
            """
                Describe the symbol at a position — its rendered signature, type, and javadoc as \
                markdown — exactly as the compiler sees it, without opening the file. Use to \
                understand an API before calling it.\
                """,
            LatheMcpTools::handleDescribe),
        tool(
            engine,
            mapper,
            "search_symbols",
            """
                {"type":"object","required":["query"],
                 "properties":{
                   "query":{"type":"string","description":"Type name or prefix, simple or qualified (java.util.Map.Entry, util.Duration); reactor types also match by CamelHumps (ASF finds AbstractServerFactory)."},
                   "scope":{"type":"string","enum":["reactor","all"],"description":"all (default) also finds JDK and dependency types, listed after the reactor's; reactor finds only the project's own."},
                   "maxResults":{"type":"integer","minimum":1,"description":"Max symbols to return (default 50)."}}}\
                """,
            READ_ONLY,
            """
                Find a type by name across the whole reactor, its dependencies, and the \
                JDK (the reactor's first, never JDK internals no project code can use) — \
                returning each with its kind, container, and `path:line:column`. Use to locate a type \
                when you know its name but not its file.\
                """,
            LatheMcpTools::handleSearchSymbols),
        tool(
            engine,
            mapper,
            "find_implementations",
            positionSchema(
                """
                    ,"maxResults":{"type":"integer","minimum":1,
                     "description":"Max implementations to return (default 50)."}\
                    """),
            READ_ONLY,
            """
                Find the implementations of the interface — or the overrides of the method — at a \
                position, across the whole reactor, javac-accurate, one `path:line:column: code` line each. This is \
                the "who implements X / what overrides this" question text search cannot answer: it \
                follows the type hierarchy across modules and includes anonymous and nested \
                implementers a name grep cannot find.\
                """,
            LatheMcpTools::handleFindImplementations),
        tool(
            engine,
            mapper,
            "analyze_change",
            positionSchema(""),
            READ_ONLY,
            """
                Before editing a symbol, preview its blast radius — javac-accurate and cross-module: \
                its override/implementation family, how many production vs test references it has, \
                which reactor modules use it, and the relevant test classes. Use it to scope a rename \
                or signature change before making it.""",
            LatheMcpTools::handleAnalyzeChange),
        tool(
            engine,
            mapper,
            "verify_change",
            """
                {"type":"object",
                 "properties":{"files":{"type":"array","items":{"type":"string"},
                   "description":"Optional absolute paths of changed .java files; omit to check what changed on disk plus what Lathe's tools wrote (e.g. rename_symbol) since the last check."}}}\
                """,
            READ_ONLY,
            """
                After editing Java files, recompile the changed files and their callers, in their \
                module and in dependent modules, in-process against the real classpath — no full \
                build — and report one list, errors first, each path starting with its module: \
                errors and warnings of the changed files, only errors of their callers (unused-code \
                hints left out). With no `files` it checks what changed on disk plus what Lathe's \
                own tools wrote, such as a rename. When the change reaches other modules it names them and returns the sync \
                command that builds them, optional when no errors were found. Use it to confirm \
                your edits still compile before a build.\
                """,
            LatheMcpTools::handleVerifyChange),
        tool(
            engine,
            mapper,
            "format_file",
            FILE_SCHEMA,
            EDITS_IN_PLACE,
            """
                Format a Java file in place with the formatter the project pins (its Spotless \
                step: google-java-format, palantir, Eclipse), so your edits pass the build's \
                formatting check. Returns the lines it rewrote, or says the file was already \
                formatted. Call after editing a file, before verify_change.\
                """,
            LatheMcpTools::handleFormatFile),
        tool(
            engine,
            mapper,
            "fix_imports",
            FILE_SCHEMA,
            EDITS_IN_PLACE,
            """
                Add the missing imports of a Java file in place — every unresolved type name that \
                matches exactly one type in the reactor, its dependencies, or the JDK. A name with \
                several matches (List: java.util.List, java.awt.List) is listed with its \
                candidates for you to choose, and a name with none is listed too. Each import goes \
                to its sorted place among the file's imports. Call after writing code that uses \
                new types.\
                """,
            LatheMcpTools::handleFixImports),
        tool(
            engine,
            mapper,
            "outline",
            FILE_SCHEMA,
            READ_ONLY,
            """
                A Java file's structure without reading it: every type, field, constructor and \
                method in source order, indented by nesting, each with the line and column of its \
                name and its declaration (`method sell  8:7: int sell(int count) {`), the position \
                the other tools take. Use it to find a member before reading or editing that part \
                of a large file.\
                """,
            LatheMcpTools::handleOutline),
        tool(
            engine,
            mapper,
            "workspace_summary",
            """
                {"type":"object","properties":{}}""",
            READ_ONLY,
            """
                The reactor at a glance: every module, the reactor modules it depends on, \
                and whether its test launch is captured (which run_test needs), plus the sync \
                command that builds them. Call it first in an unfamiliar project, instead of \
                reading the build files.\
                """,
            LatheMcpTools::handleWorkspaceSummary));
  }

  private static CallToolResult handleWorkspaceSummary(
      final LatheEngine engine, final CallToolRequest request, final String tool) {
    return guarded(tool, "workspace", () -> render(engine.modules(), McpText::modulesResult));
  }

  // A read tool's answer, rendered with the notes its request read.
  private static <T> CallToolResult render(
      final Noted<T> answer, final BiFunction<T, LatheNotes, CallToolResult> renderer) {
    return renderer.apply(answer.value(), answer.notes());
  }

  // A tool's handler, given the engine, the call, and the tool's name for its answers and logs.
  @FunctionalInterface
  private interface ToolHandler {
    CallToolResult handle(LatheEngine engine, CallToolRequest request, String tool);
  }

  private static SyncToolSpecification tool(
      final Supplier<LatheEngine> engine,
      final McpJsonMapper mapper,
      final String name,
      final String schema,
      final ToolAnnotations annotations,
      final String description,
      final ToolHandler handler) {
    final var tool =
        Tool.builder(name, mapper, schema)
            .description(description)
            .annotations(annotations)
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) ->
                logged(name, request, () -> answer(engine, request, name, handler)))
        .build();
  }

  private static CallToolResult answer(
      final Supplier<LatheEngine> engine,
      final CallToolRequest request,
      final String tool,
      final ToolHandler handler) {
    // The schemas' minimum, which a client need not enforce; a negative value would make
    // Stream.limit throw.
    final Integer max = intArg(request, "maxResults");
    if (max != null && max < 1) {
      return McpText.error("%s 'maxResults' must be at least 1".formatted(tool));
    }

    // Guarded as a whole, so what fails before a handler's own guard (the engine's start, a path
    // the OS rejects) still reaches the agent as an isError result, not a protocol error.
    return guarded(tool, argsSummary(request), () -> handler.handle(engine.get(), request, tool));
  }

  // No engine to answer with: the server started where no .lathe/ is. The message says why, and
  // how to set the project up.
  static final class NoWorkspaceException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    NoWorkspaceException(final String message) {
      super(message, null, false, false);
    }
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
    return args(request).entrySet().stream()
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
      final LatheEngine engine, final CallToolRequest request, final String tool) {
    return atFile(
        tool,
        request,
        file ->
            render(
                engine.diagnostics(file),
                (diagnostics, notes) -> McpText.diagnosticsResult(file, diagnostics, notes)));
  }

  private static CallToolResult handleDefinition(
      final LatheEngine engine, final CallToolRequest request, final String tool) {
    return atPosition(
        engine,
        tool,
        request,
        (file, line, column) ->
            render(engine.definition(file, line, column), McpText::definitionResult));
  }

  private static CallToolResult handleReferences(
      final LatheEngine engine, final CallToolRequest request, final String tool) {
    return atPosition(
        engine,
        tool,
        request,
        (file, line, column) ->
            render(
                engine.references(file, line, column, maxResults(request)),
                McpText::referencesResult));
  }

  private static CallToolResult handleFindImplementations(
      final LatheEngine engine, final CallToolRequest request, final String tool) {
    return atPosition(
        engine,
        tool,
        request,
        (file, line, column) ->
            render(
                engine.findImplementations(file, line, column, maxResults(request)),
                McpText::implementationsResult));
  }

  private static CallToolResult handleRename(
      final LatheEngine engine, final CallToolRequest request, final String tool) {
    final String newName = stringArg(request, "newName");
    return atPosition(
        engine,
        tool,
        request,
        (file, line, column) -> {
          final LatheRename rename = engine.rename(file, line, column, newName);
          return McpText.renameResult(
              rename, engine.notes(rename.files().isEmpty() ? List.of(file) : rename.paths()));
        });
  }

  private static CallToolResult handleRunTest(
      final LatheEngine engine, final CallToolRequest request, final String tool) {
    final TestScope scope = testScope(stringArg(request, "scope"));
    if (scope == null) {
      return McpText.error("run_test 'scope' must be one of class, method, package");
    }

    final String method = stringArg(request, "method");
    if (scope == TestScope.METHOD && method == null) {
      return McpText.error("run_test with scope=method requires a 'method' argument");
    }

    return atFile(
        tool, request, file -> render(engine.runTest(file, scope, method), McpText::testRunResult));
  }

  private static CallToolResult handleAnalyzeChange(
      final LatheEngine engine, final CallToolRequest request, final String tool) {
    return atPosition(
        engine,
        tool,
        request,
        (file, line, column) ->
            render(engine.analyzeChange(file, line, column), McpText::analyzeResult));
  }

  private static CallToolResult handleVerifyChange(
      final LatheEngine engine, final CallToolRequest request, final String tool) {
    final List<Path> files = fileListArg(request);
    final Optional<Path> notJava =
        files.stream().filter(file -> !FileUtil.isJavaFile(file)).findFirst();
    if (notJava.isPresent()) {
      return McpText.error(notAJavaSource(notJava.get()));
    }

    // Only which named files went unchecked: verify_change reports its own failures and deferral.
    return guarded(
        tool,
        "files",
        () ->
            McpText.verifyChangeResult(
                engine.verifyChange(files),
                LatheNotes.ofNotChecked(engine.notes(files).notChecked())));
  }

  private static CallToolResult handleFormatFile(
      final LatheEngine engine, final CallToolRequest request, final String tool) {
    return atFile(
        tool,
        request,
        file ->
            McpText.formatResult(
                file, engine.format(file).orElse(null), engine.notes(List.of(file))));
  }

  private static CallToolResult handleFixImports(
      final LatheEngine engine, final CallToolRequest request, final String tool) {
    return atFile(
        tool,
        request,
        file -> McpText.importsResult(engine.fixImports(file), engine.notes(List.of(file))));
  }

  private static CallToolResult handleOutline(
      final LatheEngine engine, final CallToolRequest request, final String tool) {
    return atFile(
        tool,
        request,
        file ->
            render(
                engine.outline(file), (items, notes) -> McpText.outlineResult(file, items, notes)));
  }

  // An enum argument by its constant's name, case-insensitive.
  private static <E extends Enum<E>> Optional<E> enumArg(final Class<E> type, final String name) {
    return Arrays.stream(type.getEnumConstants())
        .filter(constant -> constant.name().equalsIgnoreCase(name))
        .findFirst();
  }

  // Optional, case-insensitive; absent means class, unknown means null (caller errors on it).
  private static TestScope testScope(final String scope) {
    return scope == null ? TestScope.CLASS : enumArg(TestScope.class, scope).orElse(null);
  }

  // Optional, case-insensitive; absent means the tool's default, unknown means null.
  private static SymbolScope symbolScope(
      final CallToolRequest request, final SymbolScope defaultScope) {
    final String scope = stringArg(request, "scope");
    return scope == null ? defaultScope : enumArg(SymbolScope.class, scope).orElse(null);
  }

  private static CallToolResult handleCallHierarchy(
      final LatheEngine engine, final CallToolRequest request, final String tool) {
    final String directionArg = stringArg(request, "direction");
    final CallDirection direction =
        directionArg == null
            ? CallDirection.INCOMING
            : enumArg(CallDirection.class, directionArg).orElse(null);
    if (direction == null) {
      return McpText.error("call_hierarchy 'direction' must be one of incoming, outgoing");
    }

    final SymbolScope scope = symbolScope(request, SymbolScope.REACTOR);
    if (scope == null) {
      return McpText.error("call_hierarchy 'scope' must be one of reactor, all");
    }

    return atPosition(
        engine,
        tool,
        request,
        (file, line, column) ->
            render(
                engine.callHierarchy(file, line, column, direction, scope, maxResults(request)),
                (hierarchy, notes) -> McpText.callHierarchyResult(hierarchy, scope, notes)));
  }

  private static CallToolResult handleDescribe(
      final LatheEngine engine, final CallToolRequest request, final String tool) {
    return atPosition(
        engine,
        tool,
        request,
        (file, line, column) ->
            render(engine.describe(file, line, column), McpText::describeResult));
  }

  private static CallToolResult handleSearchSymbols(
      final LatheEngine engine, final CallToolRequest request, final String tool) {
    final String query = stringArg(request, "query");
    if (query == null || query.isBlank()) {
      return McpText.error("search_symbols requires a 'query' argument");
    }

    final SymbolScope scope = symbolScope(request, SymbolScope.ALL);
    if (scope == null) {
      return McpText.error("search_symbols 'scope' must be one of reactor, all");
    }

    return guarded(
        tool,
        query,
        () ->
            render(
                engine.searchSymbols(query, scope, maxResults(request)),
                (symbols, notes) -> McpText.searchResult(query, symbols, notes)));
  }

  // Shared body for the file tools: an existing .java file, then the handler, an engine failure
  // becoming an isError result.
  private static CallToolResult atFile(
      final String tool,
      final CallToolRequest request,
      final Function<Path, CallToolResult> handler) {
    final Path file = filePath(request);
    if (file == null) {
      return McpText.error("%s requires a 'file' argument".formatted(tool));
    }

    if (!isJavaSource(file)) {
      return McpText.error(notAJavaSource(file));
    }

    return guarded(tool, file, () -> handler.apply(file));
  }

  // Shared body for the position-based tools: parse the 1-based {file,line,column} into a 0-based
  // position, run the handler, and turn missing args or an engine failure into an isError result.
  private static CallToolResult atPosition(
      final LatheEngine engine,
      final String tool,
      final CallToolRequest request,
      final PositionHandler handler) {
    final Path file = filePath(request);
    final Integer line = intArg(request, "line");
    final Integer column = intArg(request, "column");
    final String symbol = stringArg(request, "symbol");
    if (file == null || line == null || (column == null && symbol == null)) {
      return McpText.error(
          "%s requires 'file', 'line', and the 'symbol' on that line or its 'column'"
              .formatted(tool));
    }

    if (!isJavaSource(file)) {
      return McpText.error(notAJavaSource(file));
    }

    if (line < 1 || (column != null && column < 1)) {
      return McpText.error("Positions are 1-based: line and column start at 1.");
    }

    return guarded(
        tool,
        file,
        () ->
            candidateColumns(engine, file, fromWire(line), column, symbol).stream()
                .map(candidate -> answerAt(handler, file, fromWire(line), candidate))
                .flatMap(Optional::stream)
                .findFirst()
                .orElseGet(
                    () ->
                        McpText.error(
                            engine
                                .sourceLine(file, fromWire(line))
                                .map(text -> notOnName(line, column, symbol, text))
                                .orElse(
                                    "No symbol at line %d: the file is shorter."
                                        .formatted(line)))));
  }

  // The column given, or each place the symbol's name stands on the line, the one nearest the
  // given column first.
  private static List<Integer> candidateColumns(
      final LatheEngine engine,
      final Path file,
      final int line,
      final Integer column,
      final String symbol) {
    if (symbol == null) {
      return List.of(fromWire(column));
    }

    final List<Integer> named = engine.nameColumns(file, line, symbol);
    return column == null
        ? named
        : named.stream()
            .sorted(Comparator.comparingInt(c -> Math.abs(c - fromWire(column))))
            .toList();
  }

  // Empty when the position is not on a name, so the next candidate is tried.
  private static Optional<CallToolResult> answerAt(
      final PositionHandler handler, final Path file, final int line, final int column) {
    try {
      return Optional.of(handler.at(file, line, column));
    } catch (final LatheEngine.NotOnName e) {
      return Optional.empty();
    }
  }

  // The line itself, so the agent can correct its input without reading the file.
  private static String notOnName(
      final int line, final Integer column, final String symbol, final String text) {
    final String where =
        symbol != null
            ? "no symbol named '%s'".formatted(symbol)
            : "no symbol at column %d: the position must be on a name".formatted(column);
    return "Line %d has %s. Line %d reads: %s".formatted(line, where, line, text.strip());
  }

  // Run an engine call, turning a refusal or an unexpected failure into an isError result; only
  // the failure is logged as an error.
  private static CallToolResult guarded(
      final String tool, final Object context, final Supplier<CallToolResult> body) {
    try {
      return body.get();
    } catch (final LatheEngine.Refused | NoWorkspaceException e) {
      LOG.fine(() -> "[%s] %s refused: %s".formatted(tool, context, e.getMessage()));
      return McpText.error(e.getMessage());
    } catch (final RuntimeException e) {
      LOG.log(Level.SEVERE, e, () -> "[%s] failed for %s".formatted(tool, context));
      return McpText.error(agentMessage(tool, e));
    }
  }

  static String agentMessage(final String tool, final RuntimeException e) {
    return e.getMessage() == null
        ? "%s failed; see the server log".formatted(tool)
        : e.getMessage();
  }

  @FunctionalInterface
  private interface PositionHandler {
    CallToolResult at(Path file, int line, int column);
  }

  private static int maxResults(final CallToolRequest request) {
    final Integer max = intArg(request, "maxResults");
    return max != null ? max : DEFAULT_MAX_RESULTS;
  }

  // Agents give 1-based lines and columns; the server's positions are 0-based (McpText.toWire is
  // the way back).
  private static int fromWire(final int oneBased) {
    return oneBased - 1;
  }

  // The file tools read a Java source; anything else would get an answer about a file never
  // compiled.
  private static boolean isJavaSource(final Path file) {
    return FileUtil.isJavaFile(file) && Files.isRegularFile(file);
  }

  private static String notAJavaSource(final Path file) {
    return FileUtil.isJavaFile(file)
        ? "%s does not exist".formatted(file)
        : "%s is not a .java file".formatted(file);
  }

  // A call may carry no arguments at all, which the SDK passes on as null.
  private static Map<String, Object> args(final CallToolRequest request) {
    return request.arguments() != null ? request.arguments() : Map.of();
  }

  private static Path filePath(final CallToolRequest request) {
    final String file = stringArg(request, "file");
    return file == null ? null : FileUtil.absolute(file);
  }

  private static String stringArg(final CallToolRequest request, final String name) {
    final Object value = args(request).get(name);
    return value == null ? null : value.toString();
  }

  private static Integer intArg(final CallToolRequest request, final String name) {
    return args(request).get(name) instanceof Number n ? n.intValue() : null;
  }

  // Optional "files" array; absent or malformed means "detect the change set".
  private static List<Path> fileListArg(final CallToolRequest request) {
    if (!(args(request).get("files") instanceof List<?> files)) {
      return List.of();
    }

    return files.stream().map(String::valueOf).map(FileUtil::absolute).toList();
  }
}
