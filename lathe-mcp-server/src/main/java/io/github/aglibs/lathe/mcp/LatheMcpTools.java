package io.github.aglibs.lathe.mcp;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.analysis.MissingImportsResult.MissingImport;
import io.github.aglibs.lathe.server.engine.LatheCall;
import io.github.aglibs.lathe.server.engine.LatheCallHierarchy;
import io.github.aglibs.lathe.server.engine.LatheChangeImpact;
import io.github.aglibs.lathe.server.engine.LatheDiagnostic;
import io.github.aglibs.lathe.server.engine.LatheEngine;
import io.github.aglibs.lathe.server.engine.LatheEngine.CallDirection;
import io.github.aglibs.lathe.server.engine.LatheEngine.TestScope;
import io.github.aglibs.lathe.server.engine.LatheFileEdit;
import io.github.aglibs.lathe.server.engine.LatheFormat;
import io.github.aglibs.lathe.server.engine.LatheImplementations;
import io.github.aglibs.lathe.server.engine.LatheImports;
import io.github.aglibs.lathe.server.engine.LatheLocation;
import io.github.aglibs.lathe.server.engine.LatheModuleDiagnostics;
import io.github.aglibs.lathe.server.engine.LatheModuleSummary;
import io.github.aglibs.lathe.server.engine.LatheNotes;
import io.github.aglibs.lathe.server.engine.LatheOutlineItem;
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
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

/**
 * The MCP tool surface over a {@link LatheEngine}. Bad input or an engine failure becomes an {@code
 * isError} result rather than a thrown exception.
 */
final class LatheMcpTools {

  private static final Logger LOG = Logger.getLogger(LatheMcpTools.class.getName());
  private static final int DEFAULT_MAX_RESULTS = 50;
  private static final int MAX_ARG_VALUE_CHARS = 200;
  private static final int LISTED_MODULES = 10;

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

  // The symbol-at-a-position input every position tool takes, plus its own properties (each
  // starting with a comma).
  private static String positionSchema(final String extraProperties) {
    return """
        {"type":"object","required":["file","line","column"],
         "properties":{
           "file":{"type":"string","description":"Absolute path to a .java file."},
           "line":{"type":"integer","description":"1-based line of the symbol."},
           "column":{"type":"integer","description":"1-based column of the symbol."}%s}}"""
        .formatted(extraProperties);
  }

  // The engine is taken when a tool is called: started where no .lathe/ is, there is none, and each
  // tool answers why (NoWorkspaceException).
  static List<SyncToolSpecification> all(
      final Supplier<LatheEngine> engine, final McpJsonMapper mapper) {
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
        verifyChange(engine, mapper),
        formatFile(engine, mapper),
        fixImports(engine, mapper),
        outline(engine, mapper),
        workspaceSummary(engine, mapper));
  }

  private static SyncToolSpecification diagnostics(
      final Supplier<LatheEngine> engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder("get_diagnostics", mapper, FILE_SCHEMA)
            .description(
                """
                Compiler-accurate errors and warnings (plus unused-code hints) for a Java file, \
                exactly as javac sees them on the real build classpath. Call after editing a file to check whether \
                it compiles — no whole-project build.\
                """)
            .annotations(READ_ONLY)
            .build();
    return spec(tool, request -> handleDiagnostics(engine.get(), request));
  }

  private static SyncToolSpecification definition(
      final Supplier<LatheEngine> engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder("get_definition", mapper, positionSchema(""))
            .description(
                """
                Resolve the symbol at a position to its definition — across modules and into \
                dependency, JDK, and generated sources — returning each target as \
                `path:line: code`, its source line included.\
                """)
            .annotations(READ_ONLY)
            .build();
    return spec(tool, request -> handleDefinition(engine.get(), request));
  }

  private static SyncToolSpecification references(
      final Supplier<LatheEngine> engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder(
                "find_references",
                mapper,
                positionSchema(
                    """
                    ,"maxResults":{"type":"integer","minimum":1,
                     "description":"Max references to return (default 50)."}\
                    """))
            .description(
                """
                Find every real use of the symbol at a position across the whole reactor — \
                javac-accurate, not text search: resolves overloads and inheritance, spans all \
                modules, and returns each use as one `path:line: code` line. It finds uses a plain grep \
                misses or misattributes — a symbol reached through a variable, or one of several \
                overloads that share a name — and excludes unrelated symbols that merely share the \
                name, so you need not re-check with grep. Use before changing or removing a symbol, \
                especially a method \
                with overrides/implementations or a common/overloaded name; for a rare, distinctive \
                name a plain grep is fine.\
                """)
            .annotations(READ_ONLY)
            .build();
    return spec(tool, request -> handleReferences(engine.get(), request));
  }

  private static SyncToolSpecification rename(
      final Supplier<LatheEngine> engine, final McpJsonMapper mapper) {
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
                match. It updates uses grep+sed would miss or silently rebind to the wrong overload, \
                and reports exactly which files it changed. Renaming a public top-level type also \
                renames its file. Refuses if it would touch a file outside the reactor, or if the \
                symbol (or a method it overrides) is declared in the JDK, a dependency, or \
                generated source. The changed files are compiled before it returns, so follow-up \
                queries see the new names. Then \
                call verify_change with no arguments to check the rewritten files and their callers, \
                and run the sync it suggests for other modules.\
                """)
            .annotations(REWRITES_SOURCE)
            .build();
    return spec(tool, request -> handleRename(engine.get(), request));
  }

  private static SyncToolSpecification runTest(
      final Supplier<LatheEngine> engine, final McpJsonMapper mapper) {
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
                Replay a test against the compiled classpath — no full build. Runs one method, \
                the whole test class, or its package; prefer it over the build's own test command \
                for a single target after an edit. Returns pass/fail/skip counts and each failure's \
                message and line. Needs a captured test-launch.json (run `%s` once); it reports \
                that if missing.\
                """
                    .formatted(LatheLayout.captureTestsCommand("<module>")))
            .annotations(RUNS_CODE)
            .build();
    return spec(tool, request -> handleRunTest(engine.get(), request));
  }

  private static SyncToolSpecification callHierarchy(
      final Supplier<LatheEngine> engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder(
                "call_hierarchy",
                mapper,
                positionSchema(
                    """
                    ,"direction":{"type":"string","enum":["incoming","outgoing"],
                     "description":"incoming = callers of the symbol (default); outgoing = what it calls."},
                    "maxResults":{"type":"integer","minimum":1,"description":"Max calls to return (default 50)."}\
                    """))
            .description(
                """
                Trace the symbol's callers (incoming) or the methods it calls (outgoing) across the \
                whole reactor — javac-accurate, following the real call graph (resolving overrides \
                and cross-module edges), one `path:line: code` line per call. Use to scope the impact of a \
                change; text search cannot follow calls.\
                """)
            .annotations(READ_ONLY)
            .build();
    return spec(tool, request -> handleCallHierarchy(engine.get(), request));
  }

  private static SyncToolSpecification describeSymbol(
      final Supplier<LatheEngine> engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder("describe_symbol", mapper, positionSchema(""))
            .description(
                """
                Describe the symbol at a position — its rendered signature, type, and javadoc as \
                markdown — exactly as the compiler sees it, without opening the file. Use to \
                understand an API before calling it.\
                """)
            .annotations(READ_ONLY)
            .build();
    return spec(tool, request -> handleDescribe(engine.get(), request));
  }

  private static SyncToolSpecification searchSymbols(
      final Supplier<LatheEngine> engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder(
                "search_symbols",
                mapper,
                """
                {"type":"object","required":["query"],
                 "properties":{
                   "query":{"type":"string","description":"Type name or prefix, simple or qualified (java.util.Map.Entry, util.Duration); reactor types also match by CamelHumps (ASF finds AbstractServerFactory)."},
                   "maxResults":{"type":"integer","minimum":1,"description":"Max symbols to return (default 50)."}}}\
                """)
            .description(
                """
                Find a type by name across the whole reactor, its dependencies, and the \
                JDK — returning each with its kind, container, and `path:line`. Use to \
                locate a type when you know its name but not its file.\
                """)
            .annotations(READ_ONLY)
            .build();
    return spec(tool, request -> handleSearchSymbols(engine.get(), request));
  }

  private static SyncToolSpecification findImplementations(
      final Supplier<LatheEngine> engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder(
                "find_implementations",
                mapper,
                positionSchema(
                    """
                    ,"maxResults":{"type":"integer","minimum":1,
                     "description":"Max implementations to return (default 50)."}\
                    """))
            .description(
                """
                Find the implementations of the interface — or the overrides of the method — at a \
                position, across the whole reactor, javac-accurate, one `path:line: code` line each. This is \
                the "who implements X / what overrides this" question text search cannot answer: it \
                follows the type hierarchy across modules and includes anonymous and nested \
                implementers a name grep cannot find.\
                """)
            .annotations(READ_ONLY)
            .build();
    return spec(tool, request -> handleFindImplementations(engine.get(), request));
  }

  private static SyncToolSpecification analyzeChange(
      final Supplier<LatheEngine> engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder("analyze_change", mapper, positionSchema(""))
            .description(
                """
                Before editing a symbol, preview its blast radius — javac-accurate and cross-module: \
                its override/implementation family, how many production vs test references it has, \
                which reactor modules use it, and the relevant test classes. Use it to scope a rename \
                or signature change before making it.""")
            .annotations(READ_ONLY)
            .build();
    return spec(tool, request -> handleAnalyzeChange(engine.get(), request));
  }

  private static SyncToolSpecification verifyChange(
      final Supplier<LatheEngine> engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder(
                "verify_change",
                mapper,
                """
                {"type":"object",
                 "properties":{"files":{"type":"array","items":{"type":"string"},
                   "description":"Optional absolute paths of changed .java files; omit to check what changed on disk plus what Lathe's tools wrote (e.g. rename_symbol) since the last check."}}}\
                """)
            .description(
                """
                After editing Java files, recompile the changed files and their callers, in their \
                module and in dependent modules, in-process against the real classpath — no full \
                build — and report one list, errors first, each path starting with its module: \
                errors and warnings of the changed files, only errors of their callers (unused-code \
                hints left out). With no `files` it checks what changed on disk plus what Lathe's \
                own tools wrote, such as a rename. When the change reaches other modules it names them and returns the sync \
                command that builds them, optional when no errors were found. Use it to confirm \
                your edits still compile before a build.\
                """)
            .annotations(READ_ONLY)
            .build();
    return spec(tool, request -> handleVerifyChange(engine.get(), request));
  }

  private static SyncToolSpecification formatFile(
      final Supplier<LatheEngine> engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder("format_file", mapper, FILE_SCHEMA)
            .description(
                """
                Format a Java file in place with the formatter the project pins (its Spotless \
                step: google-java-format, palantir, Eclipse), so your edits pass the build's \
                formatting check. Returns the lines it rewrote, or says the file was already \
                formatted. Call after editing a file, before verify_change.\
                """)
            .annotations(EDITS_IN_PLACE)
            .build();
    return spec(tool, request -> handleFormatFile(engine.get(), request));
  }

  private static SyncToolSpecification fixImports(
      final Supplier<LatheEngine> engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder("fix_imports", mapper, FILE_SCHEMA)
            .description(
                """
                Add the missing imports of a Java file in place — every unresolved type name that \
                matches exactly one type in the reactor, its dependencies, or the JDK. A name with \
                several matches (List: java.util.List, java.awt.List) is listed with its \
                candidates for you to choose, and a name with none is listed too. Each import goes \
                to its sorted place among the file's imports. Call after writing code that uses \
                new types.\
                """)
            .annotations(EDITS_IN_PLACE)
            .build();
    return spec(tool, request -> handleFixImports(engine.get(), request));
  }

  private static SyncToolSpecification outline(
      final Supplier<LatheEngine> engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder("outline", mapper, FILE_SCHEMA)
            .description(
                """
                A Java file's structure without reading it: every type, field, constructor and \
                method in source order, indented by nesting, each with its line and declaration \
                (`method sell  8: int sell(int count) {`). Use it to find a member's line before \
                reading or editing that part of a large file.\
                """)
            .annotations(READ_ONLY)
            .build();
    return spec(tool, request -> handleOutline(engine.get(), request));
  }

  private static SyncToolSpecification workspaceSummary(
      final Supplier<LatheEngine> engine, final McpJsonMapper mapper) {
    final var tool =
        Tool.builder(
                "workspace_summary",
                mapper,
                """
                {"type":"object","properties":{}}""")
            .description(
                """
                The reactor at a glance: every module, the reactor modules it depends on, \
                and whether its test launch is captured (which run_test needs), plus the sync \
                command that builds them. Call it first in an unfamiliar project, instead of \
                reading the build files.\
                """)
            .annotations(READ_ONLY)
            .build();
    return spec(tool, request -> handleWorkspaceSummary(engine.get()));
  }

  private static CallToolResult handleWorkspaceSummary(final LatheEngine engine) {
    return guarded(
        "workspace_summary",
        "workspace",
        () -> modulesResult(engine.modules(), engine.notes(List.of())));
  }

  private static SyncToolSpecification spec(
      final Tool tool, final Function<CallToolRequest, CallToolResult> handler) {
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) -> logged(tool.name(), request, () -> answer(handler, request)))
        .build();
  }

  private static CallToolResult answer(
      final Function<CallToolRequest, CallToolResult> handler, final CallToolRequest request) {
    try {
      return handler.apply(request);
    } catch (final NoWorkspaceException e) {
      return error(e.getMessage());
    }
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
    return atFile(
        "get_diagnostics",
        request,
        file -> diagnosticsResult(file, engine.diagnostics(file), engine.notes(List.of(file))));
  }

  private static CallToolResult handleDefinition(
      final LatheEngine engine, final CallToolRequest request) {
    return atPosition(
        engine,
        "get_definition",
        request,
        (file, line, column) ->
            definitionResult(engine.definition(file, line, column), engine.notes(List.of(file))));
  }

  private static CallToolResult handleReferences(
      final LatheEngine engine, final CallToolRequest request) {
    return atPosition(
        engine,
        "find_references",
        request,
        (file, line, column) ->
            referencesResult(
                engine.references(file, line, column, maxResults(request)),
                engine.notes(List.of(file))));
  }

  private static CallToolResult handleFindImplementations(
      final LatheEngine engine, final CallToolRequest request) {
    return atPosition(
        engine,
        "find_implementations",
        request,
        (file, line, column) ->
            implementationsResult(
                engine.findImplementations(file, line, column, maxResults(request)),
                engine.notes(List.of(file))));
  }

  private static CallToolResult handleRename(
      final LatheEngine engine, final CallToolRequest request) {
    final String newName = stringArg(request, "newName");
    return atPosition(
        engine,
        "rename_symbol",
        request,
        (file, line, column) -> {
          final LatheRename rename = engine.rename(file, line, column, newName);
          return renameResult(
              rename, engine.notes(rename.files().isEmpty() ? List.of(file) : rename.paths()));
        });
  }

  private static CallToolResult handleRunTest(
      final LatheEngine engine, final CallToolRequest request) {
    final TestScope scope = testScope(stringArg(request, "scope"));
    if (scope == null) {
      return error("run_test 'scope' must be one of class, method, package");
    }

    final String method = stringArg(request, "method");
    if (scope == TestScope.METHOD && method == null) {
      return error("run_test with scope=method requires a 'method' argument");
    }

    return atFile(
        "run_test",
        request,
        file -> testRunResult(engine.runTest(file, scope, method), engine.notes(List.of(file))));
  }

  private static CallToolResult handleAnalyzeChange(
      final LatheEngine engine, final CallToolRequest request) {
    return atPosition(
        engine,
        "analyze_change",
        request,
        (file, line, column) ->
            analyzeResult(engine.analyzeChange(file, line, column), engine.notes(List.of(file))));
  }

  private static CallToolResult handleVerifyChange(
      final LatheEngine engine, final CallToolRequest request) {
    final List<Path> files = fileListArg(request);
    final Optional<Path> notJava =
        files.stream().filter(file -> !FileUtil.isJavaFile(file)).findFirst();
    if (notJava.isPresent()) {
      return error(notAJavaSource(notJava.get()));
    }

    // Only which named files went unchecked: verify_change reports its own failures and deferral.
    return guarded(
        "verify_change",
        "files",
        () ->
            verifyChangeResult(
                engine.verifyChange(files),
                LatheNotes.ofNotChecked(engine.notes(files).notChecked())));
  }

  private static CallToolResult handleFormatFile(
      final LatheEngine engine, final CallToolRequest request) {
    return atFile(
        "format_file",
        request,
        file -> formatResult(file, engine.format(file), engine.notes(List.of(file))));
  }

  private static CallToolResult handleFixImports(
      final LatheEngine engine, final CallToolRequest request) {
    return atFile(
        "fix_imports",
        request,
        file -> importsResult(engine.fixImports(file), engine.notes(List.of(file))));
  }

  private static CallToolResult handleOutline(
      final LatheEngine engine, final CallToolRequest request) {
    return atFile(
        "outline",
        request,
        file -> outlineResult(file, engine.outline(file), engine.notes(List.of(file))));
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
        engine,
        "call_hierarchy",
        request,
        (file, line, column) ->
            callHierarchyResult(
                engine.callHierarchy(file, line, column, direction, maxResults(request)),
                engine.notes(List.of(file))));
  }

  private static CallToolResult handleDescribe(
      final LatheEngine engine, final CallToolRequest request) {
    return atPosition(
        engine,
        "describe_symbol",
        request,
        (file, line, column) ->
            describeResult(engine.describe(file, line, column), engine.notes(List.of(file))));
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
                query, engine.searchSymbols(query, maxResults(request)), engine.notes(List.of())));
  }

  // Shared body for the file tools: an existing .java file, then the handler, an engine failure
  // becoming an isError result.
  private static CallToolResult atFile(
      final String tool,
      final CallToolRequest request,
      final Function<Path, CallToolResult> handler) {
    final Path file = filePath(request);
    if (file == null) {
      return error("%s requires a 'file' argument".formatted(tool));
    }

    if (!isJavaSource(file)) {
      return error(notAJavaSource(file));
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
    if (file == null || line == null || column == null) {
      return error("%s requires 'file', 'line', and 'column' arguments".formatted(tool));
    }

    if (!isJavaSource(file)) {
      return error(notAJavaSource(file));
    }

    if (line < 1 || column < 1) {
      return error("Positions are 1-based: line and column start at 1.");
    }

    return guarded(
        tool,
        file,
        () -> {
          try {
            return handler.at(file, line - 1, column - 1);
          } catch (final LatheEngine.NotOnName e) {
            return error(
                engine
                    .sourceLine(file, line - 1)
                    .map(text -> notOnName(line, column, text))
                    .orElse("No symbol at line %d: the file is shorter.".formatted(line)));
          }
        });
  }

  // The line itself, so the agent can correct its column without reading the file.
  private static String notOnName(final int line, final int column, final String text) {
    return "No symbol at line %d, column %d: the position must be on a name. Line %d reads: %s"
        .formatted(line, column, line, text.strip());
  }

  // Run an engine call, turning an unexpected failure into a logged isError result.
  private static CallToolResult guarded(
      final String tool, final Object context, final Supplier<CallToolResult> body) {
    try {
      return body.get();
    } catch (final RuntimeException e) {
      LOG.log(Level.SEVERE, e, () -> "[%s] failed for %s".formatted(tool, context));
      return error(agentMessage(tool, e));
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

  // Authority lines appended to semantic-result text so the agent trusts the result instead of
  // re-verifying with grep (the "distrust tax"). True by construction: the search is
  // javac-resolved.
  private static final String REFERENCES_AUTHORITY =
      "All javac-resolved references to this exact declaration; same-named but unrelated symbols are"
          + " already excluded — no need to re-verify with grep.";
  private static final String IMPLEMENTATIONS_AUTHORITY =
      "All javac-resolved implementers/overriders across modules; unrelated same-named types are"
          + " excluded — no need to re-verify with grep.";
  private static final String RENAME_AUTHORITY =
      "Only javac-resolved uses of this declaration were renamed (overload- and scope-correct);"
          + " same-named unrelated symbols were left untouched.";

  private static CallToolResult referencesResult(
      final LatheReferences refs, final LatheNotes notes) {
    return locationListResult(
        "reference",
        refs.total(),
        refs.truncated(),
        refs.references(),
        notes,
        REFERENCES_AUTHORITY);
  }

  private static CallToolResult implementationsResult(
      final LatheImplementations impls, final LatheNotes notes) {
    return locationListResult(
        "implementation",
        impls.total(),
        impls.truncated(),
        impls.implementations(),
        notes,
        IMPLEMENTATIONS_AUTHORITY);
  }

  // Shared rendering for a capped, ranked location list ("N noun(s)"); authority is appended when
  // the list is non-empty.
  private static CallToolResult locationListResult(
      final String noun,
      final int total,
      final boolean truncated,
      final List<LatheLocation> locations,
      final LatheNotes notes,
      final String authority) {
    final String header =
        truncated
            ? "%d %ss (showing first %d):".formatted(total, noun, locations.size())
            : "%d %s(s):".formatted(total, noun);
    final String text =
        locations.isEmpty()
            ? "No %ss found.".formatted(noun)
            : "%s%n%s%n%n%s".formatted(header, locationLines(locations), authority);
    return result(text, notes);
  }

  private static CallToolResult renameResult(final LatheRename rename, final LatheNotes notes) {
    if (rename.totalEdits() == 0) {
      return result("No references to rename.", notes);
    }

    final String text =
        "Renamed to %s: %d edit(s) across %d file(s):%n%s%n%n%s"
            .formatted(
                rename.newName(),
                rename.totalEdits(),
                rename.files().size(),
                rename.files().stream()
                    .map(LatheMcpTools::fileEditLine)
                    .collect(Collectors.joining(System.lineSeparator())),
                RENAME_AUTHORITY);
    return result(text, notes);
  }

  private static CallToolResult formatResult(
      final Path file, final Optional<LatheFormat> format, final LatheNotes notes) {
    return format
        .map(f -> result("Formatted %s: %s changed.".formatted(f.path(), lineSpan(f)), notes))
        .orElseGet(() -> result("%s was already formatted.".formatted(file.getFileName()), notes));
  }

  // The path once in the header: each line carries only its line number and declaration.
  static CallToolResult outlineResult(
      final Path file, final List<LatheOutlineItem> items, final LatheNotes notes) {
    final String text =
        items.isEmpty()
            ? "No declarations in %s.".formatted(file.getFileName())
            : "%d declaration(s) in %s:%n%s"
                .formatted(
                    items.size(),
                    items.get(0).location().path(),
                    items.stream()
                        .map(LatheMcpTools::outlineLine)
                        .collect(Collectors.joining(System.lineSeparator())));
    return result(text, notes);
  }

  private static String outlineLine(final LatheOutlineItem item) {
    return "%s%s %s  %d: %s"
        .formatted(
            "  ".repeat(item.depth()),
            item.kind(),
            item.name(),
            line(item.location()),
            item.location().snippet());
  }

  // The notes say why the list may be empty or stale: a .lathe/ from another Lathe version loads no
  // modules, and a pending build-file change awaits a sync.
  static CallToolResult modulesResult(
      final List<LatheModuleSummary> modules, final LatheNotes notes) {
    final String text =
        "%d module(s); build them with `%s`, one with `%s`.%n%s%n%nCapture a module's tests for run_test with `%s`."
            .formatted(
                modules.size(),
                LatheLayout.SYNC_SCRIPT_COMMAND,
                LatheLayout.scopedSyncCommand(List.of("<module>")),
                modules.stream()
                    .map(LatheMcpTools::moduleLine)
                    .collect(Collectors.joining(System.lineSeparator())),
                LatheLayout.captureTestsCommand("<module>"));
    return result(text, notes);
  }

  private static String moduleLine(final LatheModuleSummary module) {
    final String upstream =
        module.dependsOn().isEmpty()
            ? ""
            : "  depends on: %s".formatted(listed(module.dependsOn()));
    final String tests =
        !module.hasTests()
            ? ""
            : "  tests: %s".formatted(module.testsCaptured() ? "captured" : "not captured");
    return "%s%s%s".formatted(module.module(), upstream, tests);
  }

  static CallToolResult importsResult(final LatheImports imports, final LatheNotes notes) {
    final List<MissingImport> ambiguous =
        imports.unresolved().stream().filter(item -> !item.candidates().isEmpty()).toList();
    final List<String> unknown =
        imports.unresolved().stream()
            .filter(item -> item.candidates().isEmpty())
            .map(MissingImport::name)
            .toList();
    final String added =
        imports.added().isEmpty()
            ? "No import added to %s.".formatted(imports.path())
            : "Added %d import(s) to %s: %s"
                .formatted(
                    imports.added().size(), imports.path(), String.join(", ", imports.added()));
    final String text =
        Stream.of(
                Optional.of(added),
                Optional.of(ambiguous)
                    .filter(items -> !items.isEmpty())
                    .map(
                        items ->
                            "Ambiguous — add one yourself:%n%s"
                                .formatted(
                                    items.stream()
                                        .map(LatheMcpTools::choiceLine)
                                        .collect(Collectors.joining(System.lineSeparator())))),
                Optional.of(unknown)
                    .filter(names -> !names.isEmpty())
                    .map(names -> "No import found for: %s".formatted(String.join(", ", names))))
            .flatMap(Optional::stream)
            .collect(Collectors.joining(System.lineSeparator()));
    return result(text, notes);
  }

  private static String choiceLine(final MissingImport item) {
    return "  %s: %s".formatted(item.name(), String.join(", ", item.candidates()));
  }

  private static String lineSpan(final LatheFormat format) {
    return format.firstLine() == format.lastLine()
        ? "line %d".formatted(format.firstLine())
        : "lines %d–%d".formatted(format.firstLine(), format.lastLine());
  }

  private static CallToolResult callHierarchyResult(
      final LatheCallHierarchy ch, final LatheNotes notes) {
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
    return result(text, notes);
  }

  private static CallToolResult describeResult(final String markup, final LatheNotes notes) {
    final String text = markup.isBlank() ? "No symbol information at that position." : markup;
    return result(text, notes);
  }

  private static CallToolResult searchResult(
      final String query, final List<LatheSymbol> symbols, final LatheNotes notes) {
    final String text =
        symbols.isEmpty()
            ? "No symbols matching '%s'.".formatted(query)
            : "%d symbol(s):%n%s"
                .formatted(
                    symbols.size(),
                    symbols.stream()
                        .map(LatheMcpTools::symbolLine)
                        .collect(Collectors.joining(System.lineSeparator())));
    return result(text, notes);
  }

  private static String symbolLine(final LatheSymbol symbol) {
    final String where = symbol.container().isBlank() ? "" : " — %s".formatted(symbol.container());
    return "%s (%s)%s  %s"
        .formatted(symbol.name(), symbol.kind(), where, position(symbol.location()));
  }

  private static String callLine(final String arrow, final LatheCall call) {
    return "%s %s  %s".formatted(arrow, call.name(), locationLine(call.location()));
  }

  private static CallToolResult testRunResult(final LatheTestRun run, final LatheNotes notes) {
    if (!run.launched()) {
      return result(
          "Test run blocked: %s".formatted(String.join("; ", run.blockedReasons())), notes);
    }

    final String header =
        "%s — %d passed, %d failed, %d skipped%nRan: %s"
            .formatted(
                run.failed() == 0 ? "PASS" : "FAIL",
                run.passed(),
                run.failed(),
                run.skipped(),
                String.join(", ", run.ran()));
    final String text =
        run.failures().isEmpty()
            ? header
            : "%s%n%s"
                .formatted(
                    header,
                    run.failures().stream()
                        .map(LatheMcpTools::testFailureLine)
                        .collect(Collectors.joining(System.lineSeparator())));
    return result(text, notes);
  }

  private static String testFailureLine(final LatheTestFailure f) {
    return "  %s:%d — %s".formatted(f.test(), f.line(), f.summary());
  }

  private static CallToolResult analyzeResult(
      final LatheChangeImpact impact, final LatheNotes notes) {
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
    return result(text, notes);
  }

  private static String joinOrNone(final List<String> items) {
    return items.isEmpty() ? "none" : String.join(", ", items);
  }

  static final LatheNotes NO_NOTES = LatheNotes.ofNotChecked(List.of());

  static CallToolResult verifyChangeResult(final LatheVerifyChange verify, final LatheNotes notes) {
    if (verify.deferral() != null) {
      // Another server writing .lathe/ ends on its own, so no sync is named then.
      final String next =
          verify.suggestedSync() == null
              ? "Re-check in a moment."
              : "Run `%s`, then re-check.".formatted(verify.suggestedSync());
      return result(
          "Verification deferred: %s. %s".formatted(deferralText(verify.deferral()), next), notes);
    }

    final int total = verify.perModule().stream().mapToInt(m -> m.diagnostics().size()).sum();
    final long errors = errorCount(verify);
    return result(
        verifyHeadline(errors, total - errors, notes.notChecked().size())
            + verifyDiagnosticLines(verify.perModule())
            + crossModuleNote(verify, errors),
        notes);
  }

  // Callers report errors only, so every warning is in a changed file. A file it could not check
  // must not read as compiling.
  static String verifyHeadline(final long errors, final long warnings, final int unchecked) {
    final String warned =
        warnings == 0 ? "" : "; %d warning(s) in the changed files".formatted(warnings);
    if (errors == 0 && unchecked > 0) {
      return "No errors in what was checked%s; %d named file(s) not checked."
          .formatted(warned, unchecked);
    }

    return errors == 0
        ? "No errors — the changed files and their callers compile%s.".formatted(warned)
        : "%d error(s)%s.".formatted(errors, warned);
  }

  private static long errorCount(final LatheVerifyChange verify) {
    return verify.perModule().stream()
        .flatMap(module -> module.diagnostics().stream())
        .filter(located -> located.diagnostic().getSeverity() == DiagnosticSeverity.Error)
        .count();
  }

  // A change to a core module names over a hundred dependents; the count and the sync command carry
  // what matters.
  static String crossModuleNote(final LatheVerifyChange verify, final long errors) {
    if (verify.suggestedSync() == null) {
      return "";
    }

    final List<String> modules = verify.affectedModules();
    final String advice =
        errors == 0
            ? "their callers compile, so a full build is optional"
            : "fix the errors and re-check, or build them with";
    return "%n%n%d module(s) depend on your change (%s); %s:%n  %s"
        .formatted(modules.size(), listed(modules), advice, verify.suggestedSync());
  }

  // A core module has a hundred dependents: the first few name the shape.
  private static String listed(final List<String> modules) {
    final String first = modules.stream().limit(LISTED_MODULES).collect(Collectors.joining(", "));
    return modules.size() > LISTED_MODULES
        ? "%s, and %d more".formatted(first, modules.size() - LISTED_MODULES)
        : first;
  }

  // One flat list, errors first: each line's path already starts with its module.
  private static String verifyDiagnosticLines(final List<LatheModuleDiagnostics> perModule) {
    return perModule.stream()
        .flatMap(module -> module.diagnostics().stream())
        .sorted(Comparator.comparing(located -> located.diagnostic().getSeverity()))
        .map(located -> System.lineSeparator() + locatedDiagnosticLine(located))
        .collect(Collectors.joining());
  }

  // grep-style, like a location: path:line:column: severity: message.
  static String locatedDiagnosticLine(final LatheDiagnostic located) {
    final Diagnostic d = located.diagnostic();
    return "%s:%d:%d: %s: %s"
        .formatted(
            located.path(),
            d.getRange().getStart().getLine() + 1,
            d.getRange().getStart().getCharacter() + 1,
            d.getSeverity(),
            messageText(d));
  }

  private static String deferralText(final String reason) {
    return switch (reason) {
      case "BUILD_FILES_PENDING" -> "a pending build-file or dependency change needs a sync";
      case "BUILD_IN_PROGRESS" -> "a build is in progress";
      case "OTHER_SERVER_WRITING" -> "another Lathe server on this workspace is updating .lathe/";
      default -> reason;
    };
  }

  private static CallToolResult diagnosticsResult(
      final Path file, final List<Diagnostic> diagnostics, final LatheNotes notes) {
    return result(diagnosticsText(file, diagnostics, notes), notes);
  }

  // Never "compiles cleanly": no module may compile the file, and the analysis is not the write
  // (whose failure the "Not compiled:" note reports).
  private static String diagnosticsText(
      final Path file, final List<Diagnostic> diagnostics, final LatheNotes notes) {
    if (!notes.notChecked().isEmpty()) {
      return "%s was not checked.".formatted(file.getFileName());
    }

    if (diagnostics.isEmpty()) {
      return "No errors or warnings in %s.".formatted(file.getFileName());
    }

    return "%d diagnostic(s) in %s:%n%s"
        .formatted(
            diagnostics.size(),
            file.getFileName(),
            diagnostics.stream()
                .map(LatheMcpTools::diagnosticLine)
                .collect(Collectors.joining(System.lineSeparator())));
  }

  private static CallToolResult definitionResult(
      final List<LatheLocation> targets, final LatheNotes notes) {
    return result(targets.isEmpty() ? "No definition found." : locationLines(targets), notes);
  }

  private static String locationLines(final List<LatheLocation> locations) {
    return locations.stream()
        .map(LatheMcpTools::locationLine)
        .collect(Collectors.joining(System.lineSeparator()));
  }

  // grep-style, the form an agent already reads: one line per location.
  static String locationLine(final LatheLocation location) {
    return "%s: %s".formatted(position(location), location.snippet());
  }

  private static String position(final LatheLocation location) {
    return "%s:%d%s".formatted(location.path(), line(location), originTag(location.origin()));
  }

  private static int line(final LatheLocation location) {
    return location.range().getStart().getLine() + 1;
  }

  // Most answers are reactor code, so only the exceptions are tagged.
  private static String originTag(final LatheLocation.Origin origin) {
    return origin == LatheLocation.Origin.REACTOR ? "" : " [%s]".formatted(origin);
  }

  // Text only: several clients give the model a result's structured content in place of its text,
  // and the notes are only in the text.
  private static CallToolResult result(final String text, final LatheNotes notes) {
    final String body =
        Stream.concat(Stream.of(text), noteLines(notes))
            .collect(Collectors.joining(System.lineSeparator().repeat(2)));
    return CallToolResult.builder().addTextContent(body).build();
  }

  // What an answer cannot vouch for: code the agent must fix (no sync helps), what only a sync can
  // fix, and a build running meanwhile.
  static Stream<String> noteLines(final LatheNotes notes) {
    return Stream.of(
            Optional.of(notes.notCompiled())
                .filter(files -> !files.isEmpty())
                .map(
                    files ->
                        "Not compiled: %s — fix the code; cross-module answers use their last good classes."
                            .formatted(String.join("; ", files))),
            Optional.of(notes.syncRequired())
                .filter(reasons -> !reasons.isEmpty())
                .map(reasons -> "Sync required: %s".formatted(String.join("; ", reasons))),
            Optional.of(notes.notChecked())
                .filter(files -> !files.isEmpty())
                .map(files -> "Not checked: %s".formatted(String.join("; ", files))),
            Optional.of(
                    "A build is running: this answer reflects Lathe's classes from before it; re-query when it ends.")
                .filter(text -> notes.buildInProgress()))
        .flatMap(Optional::stream);
  }

  private static String fileEditLine(final LatheFileEdit f) {
    return "  %s%s — %d edit(s)".formatted(f.path(), originTag(f.origin()), f.editCount());
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

  private static Path filePath(final CallToolRequest request) {
    final String file = stringArg(request, "file");
    return file == null ? null : FileUtil.absolute(file);
  }

  private static String stringArg(final CallToolRequest request, final String name) {
    final Object value = request.arguments().get(name);
    return value == null ? null : value.toString();
  }

  private static Integer intArg(final CallToolRequest request, final String name) {
    return request.arguments().get(name) instanceof Number n ? n.intValue() : null;
  }

  // Optional "files" array; absent or malformed means "detect the change set".
  private static List<Path> fileListArg(final CallToolRequest request) {
    if (!(request.arguments().get("files") instanceof List<?> files)) {
      return List.of();
    }

    return files.stream().map(String::valueOf).map(FileUtil::absolute).toList();
  }

  private static CallToolResult error(final String message) {
    return CallToolResult.builder().addTextContent(message).isError(true).build();
  }
}
