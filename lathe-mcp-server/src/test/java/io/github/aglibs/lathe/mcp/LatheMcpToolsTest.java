package io.github.aglibs.lathe.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.MissingImportsResult.MissingImport;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.LatheEngine;
import io.github.aglibs.lathe.server.LatheEngine.SymbolScope;
import io.github.aglibs.lathe.server.ReconcileOutcome;
import io.github.aglibs.lathe.server.engine.LatheCall;
import io.github.aglibs.lathe.server.engine.LatheCallHierarchy;
import io.github.aglibs.lathe.server.engine.LatheChangeImpact;
import io.github.aglibs.lathe.server.engine.LatheDiagnostic;
import io.github.aglibs.lathe.server.engine.LatheFileEdit;
import io.github.aglibs.lathe.server.engine.LatheFormat;
import io.github.aglibs.lathe.server.engine.LatheImplementations;
import io.github.aglibs.lathe.server.engine.LatheImports;
import io.github.aglibs.lathe.server.engine.LatheLocation;
import io.github.aglibs.lathe.server.engine.LatheModuleSummary;
import io.github.aglibs.lathe.server.engine.LatheNotes;
import io.github.aglibs.lathe.server.engine.LatheOutlineItem;
import io.github.aglibs.lathe.server.engine.LatheReferences;
import io.github.aglibs.lathe.server.engine.LatheRename;
import io.github.aglibs.lathe.server.engine.LatheSymbol;
import io.github.aglibs.lathe.server.engine.LatheTestFailure;
import io.github.aglibs.lathe.server.engine.LatheTestRun;
import io.github.aglibs.lathe.server.engine.LatheVerifyChange;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheMcpToolsTest {

  @TempDir private Path tmp;
  private LatheEngine engine;
  private List<SyncToolSpecification> specs;

  @BeforeEach
  void setUp() {
    engine = new LatheEngine(tmp);
    specs = LatheMcpTools.all(() -> engine, McpJsonDefaults.getMapper());
  }

  @AfterEach
  void close() {
    engine.close();
  }

  @Test
  void all_registersEveryTool_withDeclaredInputSchemas() {
    assertThat(specs)
        .map(spec -> spec.tool().name())
        .containsExactlyInAnyOrder(
            "get_diagnostics",
            "get_definition",
            "find_references",
            "rename_symbol",
            "run_test",
            "call_hierarchy",
            "describe_symbol",
            "search_symbols",
            "find_implementations",
            "analyze_change",
            "verify_change",
            "format_file",
            "fix_imports",
            "outline",
            "workspace_summary");

    assertThat(tool("get_diagnostics").tool().inputSchema().toString()).contains("file");
    assertThat(tool("get_definition").tool().inputSchema().toString())
        .contains("file", "line", "column");
    assertThat(tool("find_references").tool().inputSchema().toString())
        .contains("file", "line", "column", "maxResults");
    assertThat(tool("rename_symbol").tool().inputSchema().toString())
        .contains("file", "line", "column", "newName");
    assertThat(tool("run_test").tool().inputSchema().toString())
        .contains("file", "scope", "method");
    assertThat(tool("call_hierarchy").tool().inputSchema().toString())
        .contains("file", "line", "column", "direction");
    assertThat(tool("describe_symbol").tool().inputSchema().toString())
        .contains("file", "line", "column");
    assertThat(tool("search_symbols").tool().inputSchema().toString()).contains("query");
    assertThat(tool("find_implementations").tool().inputSchema().toString())
        .contains("file", "line", "column", "maxResults");
    assertThat(tool("verify_change").tool().inputSchema().toString()).contains("files");
    assertThat(tool("analyze_change").tool().inputSchema().toString())
        .contains("file", "line", "column");
    assertThat(tool("format_file").tool().inputSchema().toString()).contains("file");
    assertThat(tool("fix_imports").tool().inputSchema().toString()).contains("file");
    assertThat(tool("outline").tool().inputSchema().toString()).contains("file");
  }

  @Test
  void all_everyTool_declaresWhetherItWrites() {
    assertThat(specs)
        .filteredOn(spec -> Boolean.TRUE.equals(spec.tool().annotations().readOnlyHint()))
        .map(spec -> spec.tool().name())
        .containsExactlyInAnyOrder(
            "get_diagnostics",
            "get_definition",
            "find_references",
            "call_hierarchy",
            "describe_symbol",
            "search_symbols",
            "find_implementations",
            "analyze_change",
            "verify_change",
            "outline",
            "workspace_summary");
    assertThat(tool("rename_symbol").tool().annotations().destructiveHint()).isTrue();
    assertThat(tool("run_test").tool().annotations().destructiveHint()).isFalse();
    assertThat(List.of("format_file", "fix_imports"))
        .map(name -> tool(name).tool().annotations())
        .allSatisfy(
            hints -> {
              assertThat(hints.destructiveHint()).isFalse();
              assertThat(hints.idempotentHint()).isTrue();
            });
  }

  @Test
  void analyzeChange_missingPositionArguments_returnsError() {
    assertThat(call("analyze_change", Map.of("file", tmp.resolve("Any.java").toString())).isError())
        .isTrue();
  }

  @Test
  void verifyChange_emptyWorkspace_reportsCleanInTextOnly() {
    final CallToolResult result = call("verify_change", Map.of());

    assertThat(result.isError()).isNotEqualTo(true);
    assertThat(text(result)).startsWith("No errors");
    assertThat(result.structuredContent()).isNull();
  }

  @Test
  void searchSymbols_blankQuery_returnsError() {
    assertThat(call("search_symbols", Map.of("query", " ")).isError()).isTrue();
  }

  @Test
  void callHierarchy_invalidDirectionOrScope_returnsError() {
    final String file = tmp.resolve("Any.java").toString();

    assertThat(
            call(
                "call_hierarchy",
                Map.of("file", file, "line", 1, "column", 1, "direction", "sideways")))
        .extracting(CallToolResult::isError)
        .isEqualTo(true);
    assertThat(
            text(
                call(
                    "call_hierarchy",
                    Map.of("file", file, "line", 1, "column", 1, "scope", "jdk"))))
        .isEqualTo("call_hierarchy 'scope' must be one of reactor, all");
  }

  @Test
  void runTest_invalidArguments_returnError() {
    final String file = tmp.resolve("FooTest.java").toString();

    assertThat(call("run_test", Map.of()).isError()).isTrue(); // no file
    assertThat(call("run_test", Map.of("file", file, "scope", "nope")).isError()).isTrue();
    assertThat(call("run_test", Map.of("file", file, "scope", "method")).isError())
        .isTrue(); // method scope without a method
  }

  @Test
  void renameSymbol_invalidIdentifier_returnsTheReasonAsAnError() throws Exception {
    final Path file = tmp.resolve("A.java");
    Files.writeString(file, "class A {\n  int count;\n}\n");

    final CallToolResult result =
        call(
            "rename_symbol",
            Map.of("file", file.toString(), "line", 2, "column", 7, "newName", "a.b"));

    assertThat(result.isError()).isTrue();
    assertThat(text(result)).contains("`a.b` is not a valid name");
  }

  // An off-by-one column would otherwise answer for the enclosing method or class; the symbol's
  // name finds its column, past an occurrence in a string.
  @Test
  void describeSymbol_positionNotOnAName_returnsErrorShowingTheLine_aNamedSymbolIsFound()
      throws Exception {
    final Path file = tmp.resolve("A.java");
    Files.writeString(
        file, "class A {\n  public void run() {}\n  void go() { String s = \"run\"; run(); }\n}\n");

    assertThat(List.of(describe(file, 2, 3), describe(file, 2, 1), describe(file, 2, 30)))
        .as("a keyword, indentation, past the line's end")
        .allSatisfy(
            result -> {
              assertThat(result.isError()).isTrue();
              assertThat(text(result))
                  .contains("must be on a name. Line 2 reads: public void run() {}");
            });
    assertThat(text(describe(file, 9, 1))).isEqualTo("No symbol at line 9: the file is shorter.");
    assertThat(
            List.of(
                named(file, 2, "run", null), named(file, 2, "run", 3), named(file, 3, "run", null)))
        .as("no column, a column off the name, a line naming it twice")
        .allSatisfy(
            result -> {
              assertThat(result.isError()).isNotEqualTo(true);
              assertThat(text(result)).doesNotContain("has no symbol");
            });
    assertThat(text(named(file, 2, "walk", null)))
        .isEqualTo("Line 2 has no symbol named 'walk'. Line 2 reads: public void run() {}");
    assertThat(text(describe(file, 0, 1)))
        .isEqualTo("Positions are 1-based: line and column start at 1.");
    assertThat(text(describe(file, 2, 15))).doesNotContain("must be on a name");
  }

  // Every file tool reads a Java source: anything else would be answered about a file never
  // compiled.
  @Test
  void fileTools_notAnExistingJavaSource_returnError() throws Exception {
    final Path pom = Files.writeString(tmp.resolve("pom.xml"), "<project/>");
    final Path missing = tmp.resolve("Missing.java");

    final CallToolResult onPom = call("get_diagnostics", Map.of("file", pom.toString()));
    assertThat(onPom.isError()).isTrue();
    assertThat(text(onPom)).isEqualTo("%s is not a .java file".formatted(pom));
    assertThat(List.of(describe(missing, 1, 1), describe(pom, 1, 2)))
        .allSatisfy(result -> assertThat(result.isError()).isTrue());
    assertThat(text(describe(missing, 1, 1))).isEqualTo("%s does not exist".formatted(missing));
    assertThat(call("run_test", Map.of("file", pom.toString())).isError()).isTrue();
    assertThat(text(call("format_file", Map.of("file", missing.toString()))))
        .isEqualTo("%s does not exist".formatted(missing));
    assertThat(text(call("format_file", Map.of())))
        .isEqualTo("format_file requires a 'file' argument");
    assertThat(call("fix_imports", Map.of("file", pom.toString())).isError()).isTrue();
    assertThat(call("outline", Map.of("file", missing.toString())).isError()).isTrue();
  }

  // Without a pinned formatter "already formatted" would be a lie: the answer is an error with the
  // remedy.
  @Test
  void formatFile_noFormatterPinned_returnsErrorWithTheRemedy() throws Exception {
    final Path file = Files.writeString(tmp.resolve("Loose.java"), "class Loose {}");

    final CallToolResult result = call("format_file", Map.of("file", file.toString()));

    assertThat(result.isError()).isTrue();
    assertThat(text(result))
        .contains("No formatter for this workspace", LatheLayout.STYLE_SHARED_FILE);
  }

  // No module compiles it, so nothing was checked; the note says whether a sync would help.
  @Test
  void getDiagnostics_fileNoModuleCompiles_saysItWasNotChecked() throws Exception {
    final Path file = Files.writeString(tmp.resolve("Loose.java"), "class Loose {}");

    final String answer = text(call("get_diagnostics", Map.of("file", file.toString())));

    assertThat(answer)
        .startsWith("Loose.java was not checked.")
        .contains("Not checked: Loose.java is outside the modules the last build compiled")
        .doesNotContain("No errors");
  }

  // A named file it could not check must not read as compiling; a non-Java one is a wrong argument.
  @Test
  void verifyChange_namedFileNotChecked_saysSoInsteadOfCompiling() throws Exception {
    final Path file = Files.writeString(tmp.resolve("Loose.java"), "class Loose {}");

    final String answer = text(call("verify_change", Map.of("files", List.of(file.toString()))));
    final CallToolResult notJava =
        call("verify_change", Map.of("files", List.of(tmp.resolve("pom.xml").toString())));

    assertThat(answer)
        .startsWith("No errors in what was checked; 1 named file(s) not checked.")
        .contains("Not checked: Loose.java is outside the modules the last build compiled")
        .doesNotContain("callers compile");
    assertThat(notJava.isError()).isTrue();
    assertThat(text(notJava)).endsWith("pom.xml is not a .java file");
  }

  @Test
  void locatedDiagnosticLine_warning_grepStyleWithPathLineAndColumn() {
    final var diagnostic =
        new Diagnostic(
            new Range(new Position(757, 8), new Position(757, 20)),
            "setMinResponseDataRate(long) has been deprecated",
            DiagnosticSeverity.Warning,
            "javac");

    assertThat(
            LatheMcpTools.locatedDiagnosticLine(
                LatheDiagnostic.of("jetty/src/main/java/Http.java", diagnostic)))
        .isEqualTo(
            "jetty/src/main/java/Http.java:758:9: Warning: setMinResponseDataRate(long) has been deprecated");
  }

  @Test
  void getDiagnostics_missingFileArgument_returnsError() {
    final CallToolResult result = call("get_diagnostics", Map.of());

    assertThat(result.isError()).isTrue();
  }

  @Test
  void getDiagnostics_nonexistentFile_returnsErrorNotException() {
    final CallToolResult result =
        call("get_diagnostics", Map.of("file", tmp.resolve("Missing.java").toString()));

    assertThat(result.isError()).isTrue();
  }

  // A refusal is the tool's normal answer: logged as a call, never as an unexpected failure.
  @Test
  void logged_recordsToolNameAndArguments_aRefusalWithoutAnError() throws Exception {
    final var records = new ArrayList<LogRecord>();
    final Logger logger = Logger.getLogger(LatheMcpTools.class.getName());
    final Handler handler =
        new Handler() {
          @Override
          public void publish(final LogRecord record) {
            records.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    final Level previous = logger.getLevel();
    final String file = tmp.resolve("Missing.java").toString();
    final Path loose = Files.writeString(tmp.resolve("Loose.java"), "class Loose {}");
    logger.setLevel(Level.INFO);
    logger.addHandler(handler);
    try {
      call("get_diagnostics", Map.of("file", file));
      assertThat(call("format_file", Map.of("file", loose.toString())).isError()).isTrue();
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(previous);
    }

    assertThat(records)
        .extracting(LogRecord::getMessage)
        .anySatisfy(
            message ->
                assertThat(message).contains("[tool] get_diagnostics", "file=%s".formatted(file)));
    assertThat(records).extracting(LogRecord::getLevel).doesNotContain(Level.SEVERE);
  }

  private CallToolResult describe(final Path file, final int line, final int column) {
    return call("describe_symbol", Map.of("file", file.toString(), "line", line, "column", column));
  }

  private CallToolResult named(
      final Path file, final int line, final String symbol, final Integer column) {
    final var arguments =
        new HashMap<String, Object>(
            Map.of("file", file.toString(), "line", line, "symbol", symbol));
    if (column != null) {
      arguments.put("column", column);
    }

    return call("describe_symbol", arguments);
  }

  private static String text(final CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }

  // Started where no .lathe/ is, the server still lists every tool, and each answers why and how
  // to set the project up, instead of the server failing to connect; once a build creates .lathe/,
  // the tools use it.
  @Test
  void all_noWorkspace_everyToolAnswersWhereItLookedAndTheSetup_untilABuildCreatesIt(
      @TempDir final Path bare) throws Exception {
    final String noWorkspace = LatheMcpServer.noWorkspace(bare);
    final var awaited = new LatheMcpServer.AwaitedEngine(bare);
    final List<SyncToolSpecification> unconfigured =
        LatheMcpTools.all(awaited, McpJsonDefaults.getMapper());

    assertThat(noWorkspace).contains(".lathe/", bare.toString(), "lathe-maven-extension");
    assertThat(unconfigured)
        .hasSameSizeAs(specs)
        .allSatisfy(
            spec -> {
              final CallToolResult result =
                  spec.callHandler()
                      .apply(
                          null,
                          CallToolRequest.builder(spec.tool().name()).arguments(Map.of()).build());
              assertThat(result.isError()).isTrue();
              assertThat(text(result)).isEqualTo(noWorkspace);
            });

    Files.createDirectories(bare.resolve(LatheLayout.LATHE_DIR));
    final LatheEngine found = awaited.get();
    try {
      assertThat(awaited.get()).isSameAs(found);
    } finally {
      found.close();
    }
  }

  private CallToolResult call(final String name, final Map<String, Object> arguments) {
    final var request = CallToolRequest.builder(name).arguments(arguments).build();
    return tool(name).callHandler().apply(null, request);
  }

  private SyncToolSpecification tool(final String name) {
    return specs.stream().filter(spec -> spec.tool().name().equals(name)).findFirst().orElseThrow();
  }

  // The SDK validates arguments against the schema, so a count below 1 is refused with the reason.
  @Test
  void maxResults_everyToolTakingIt_declaresAMinimumOfOne() {
    assertThat(specs)
        .map(spec -> ((Map<?, ?>) spec.tool().inputSchema().get("properties")).get("maxResults"))
        .filteredOn(Objects::nonNull)
        .hasSize(4)
        .allSatisfy(schema -> assertThat(schema.toString()).contains("minimum=1"));
  }

  @Test
  void agentMessage_withAndWithoutAMessage_givesTheReasonOrPointsAtTheLog() {
    assertThat(
            LatheMcpTools.agentMessage(
                "run_test", new IllegalStateException("no test method 'm' in T.java")))
        .isEqualTo("no test method 'm' in T.java");
    assertThat(LatheMcpTools.agentMessage("outline", new IllegalStateException()))
        .isEqualTo("outline failed; see the server log");
  }

  @Test
  void workspaceSummary_emptyWorkspace_answersNoModulesNotAnError() {
    final CallToolResult result = call("workspace_summary", Map.of());

    assertThat(result.isError()).isNotEqualTo(true);
    assertThat(text(result)).startsWith("0 module(s)");
  }

  // The first call an agent makes says why it lists no modules, as every other tool does.
  @Test
  void workspaceSummary_latheFromAnotherVersion_notesTheSyncWithoutAPrefix() throws Exception {
    Files.createDirectories(tmp.resolve(LatheLayout.LATHE_DIR));
    Files.writeString(
        tmp.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON),
        "{\"schemaVersion\": \"0\", \"workspaceRoot\": \".\"}");
    engine.close();
    engine = new LatheEngine(tmp);

    final String text = text(call("workspace_summary", Map.of()));

    assertThat(text)
        .startsWith("0 module(s)")
        .contains(
            "Sync required: .lathe/ was written by another Lathe version (schema 0, expected %s)"
                .formatted(LatheLayout.SCHEMA_VERSION))
        .doesNotContain("Lathe: .lathe/");
  }

  @Test
  void modulesResult_modules_oneLineEachWithUpstreamAndTestCapture_longListsCut() {
    final List<String> upstream =
        IntStream.rangeClosed(1, 12).mapToObj("lib%02d"::formatted).toList();

    final String text =
        text(
            LatheMcpTools.modulesResult(
                List.of(
                    new LatheModuleSummary("app", upstream, true, true),
                    new LatheModuleSummary("lib01", List.of(), true, false),
                    new LatheModuleSummary("docs", List.of(), false, false)),
                LatheMcpTools.NO_NOTES));

    assertThat(text.lines())
        .contains(
            "app  depends on: lib01, lib02, lib03, lib04, lib05, lib06, lib07, lib08, lib09, lib10, and 2 more  tests: captured",
            "lib01  tests: not captured",
            "docs")
        .first()
        .asString()
        .startsWith("3 module(s); build them with `%s`".formatted(LatheLayout.SYNC_SCRIPT_COMMAND));
    assertThat(text).endsWith("`%s`.".formatted(LatheLayout.captureTestsCommand("<module>")));
  }

  @Test
  void outlineResult_nestedDeclarations_indentedLinesUnderOneHeader_orNoDeclarations() {
    final List<LatheOutlineItem> items =
        List.of(
            outlineItem("class", "Shop", 0, 3, "public class Shop {"),
            outlineItem("method", "sell", 1, 8, "int sell(int count) {"));

    assertThat(
            text(
                LatheMcpTools.outlineResult(
                    tmp.resolve("Shop.java"), items, LatheMcpTools.NO_NOTES)))
        .isEqualTo(
            lines(
                "2 declaration(s) in app/Shop.java:",
                "class Shop  3:1: public class Shop {",
                "  method sell  8:1: int sell(int count) {"));
    assertThat(
            text(
                LatheMcpTools.outlineResult(
                    tmp.resolve("Empty.java"), List.of(), LatheMcpTools.NO_NOTES)))
        .isEqualTo("No declarations in Empty.java.");
  }

  private static LatheOutlineItem outlineItem(
      final String kind, final String name, final int depth, final int line, final String code) {
    return new LatheOutlineItem(kind, name, depth, location("app/Shop.java", line, code));
  }

  private static LatheLocation location(final String path, final int line, final String code) {
    return location(path, line, LatheLocation.Origin.REACTOR, code);
  }

  // A location at a 1-based line, as the tools print it.
  private static LatheLocation location(
      final String path, final int line, final LatheLocation.Origin origin, final String code) {
    return new LatheLocation("file:///w/%s".formatted(path), path, line - 1, 0, origin, code);
  }

  private static String lines(final String... lines) {
    return String.join(System.lineSeparator(), lines);
  }

  @Test
  void importsResult_addedAmbiguousAndUnknown_listsEachKind_onlyTheAddedLineWhenNothingIsLeft() {
    final var imports =
        new LatheImports(
            "app/src/main/java/a/Foo.java",
            List.of("java.time.Duration", "java.util.ArrayList"),
            List.of(
                new MissingImport("List", List.of("java.util.List", "java.awt.List")),
                new MissingImport("Fooo", List.of())));

    assertThat(text(LatheMcpTools.importsResult(imports, LatheMcpTools.NO_NOTES)))
        .isEqualTo(
            lines(
                "Added 2 import(s) to app/src/main/java/a/Foo.java: java.time.Duration, java.util.ArrayList",
                "Ambiguous — add one yourself:",
                "  List: java.util.List, java.awt.List",
                "No import found for: Fooo"));
    assertThat(
            text(
                LatheMcpTools.importsResult(
                    new LatheImports("a/Foo.java", List.of(), List.of()), LatheMcpTools.NO_NOTES)))
        .isEqualTo("No import added to a/Foo.java.");
  }

  @Test
  void verifyChangeResult_deferred_namesTheSyncOnlyWhenOneHelps() {
    assertThat(
            text(
                LatheMcpTools.verifyChangeResult(
                    new LatheVerifyChange(
                        ReconcileOutcome.DeferReason.BUILD_IN_PROGRESS,
                        List.of(),
                        List.of(),
                        ".lathe/lathe-sync.sh"),
                    LatheMcpTools.NO_NOTES)))
        .isEqualTo(
            "Verification deferred: a build is in progress. Run `.lathe/lathe-sync.sh`, then re-check.");
    assertThat(
            text(
                LatheMcpTools.verifyChangeResult(
                    new LatheVerifyChange(
                        ReconcileOutcome.DeferReason.OTHER_SERVER_WRITING,
                        List.of(),
                        List.of(),
                        null),
                    LatheMcpTools.NO_NOTES)))
        .isEqualTo(
            "Verification deferred: another Lathe server on this workspace is updating .lathe/. Re-check in a moment.");
  }

  @Test
  void noteLines_eachKindOfNote_rendersItsLine_noneWhenNothingToSay() {
    assertThat(LatheMcpTools.noteLines(LatheMcpTools.NO_NOTES)).isEmpty();
    assertThat(
            LatheMcpTools.noteLines(
                new LatheNotes(
                    List.of("a/A.java:3: cannot find symbol", "b/B.java:1: ';' expected"),
                    List.of("build files changed — run `.lathe/lathe-sync.sh`"),
                    List.of("x/X.java is outside the modules the last build compiled"),
                    true)))
        .containsExactly(
            "Not compiled: a/A.java:3: cannot find symbol; b/B.java:1: ';' expected — fix the code; cross-module answers use their last good classes.",
            "Sync required: build files changed — run `.lathe/lathe-sync.sh`",
            "Not checked: x/X.java is outside the modules the last build compiled",
            "A build is running: this answer reflects Lathe's classes from before it; re-query when it ends.");
    assertThat(LatheMcpServer.INSTRUCTIONS)
        .as("the routing instructions explain every note")
        .contains("\"Not compiled:\"")
        .contains("\"Sync required:\"")
        .contains("\"Not checked:\"")
        .doesNotContain("Stale:");
  }

  @Test
  void crossModuleNote_dependents_namesTheFirstTenAndSaysWhetherTheBuildIsNeeded() {
    final List<String> twelve = IntStream.rangeClosed(1, 12).mapToObj("mod%d"::formatted).toList();
    final String sync = ".lathe/lathe-sync.sh core";

    assertThat(
            LatheMcpTools.crossModuleNote(new LatheVerifyChange(null, List.of(), twelve, sync), 0))
        .contains("12 module(s) depend on your change (mod1, mod2,")
        .contains("mod10, and 2 more)")
        .doesNotContain("mod11")
        .endsWith(sync);
    assertThat(
            LatheMcpTools.crossModuleNote(
                new LatheVerifyChange(null, List.of(), List.of("a", "b"), sync), 0))
        .contains(
            "2 module(s) depend on your change (a, b); their callers compile, so a full build is optional")
        .doesNotContain("more");
    assertThat(
            LatheMcpTools.crossModuleNote(
                new LatheVerifyChange(null, List.of(), List.of("a", "b"), sync), 1))
        .contains("(a, b); fix the errors and re-check, or build them with")
        .endsWith(sync);
    assertThat(
            LatheMcpTools.crossModuleNote(new LatheVerifyChange(null, List.of(), twelve, null), 0))
        .isEmpty();
  }

  @Test
  void verifyHeadline_errorsAndWarnings_leadsWithErrors() {
    assertThat(LatheMcpTools.verifyHeadline(0, 0, 0))
        .isEqualTo("No errors — the changed files and their callers compile.");
    assertThat(LatheMcpTools.verifyHeadline(0, 7, 0))
        .isEqualTo(
            "No errors — the changed files and their callers compile; 7 warning(s) in the changed files.");
    assertThat(LatheMcpTools.verifyHeadline(2, 1, 0))
        .isEqualTo("2 error(s); 1 warning(s) in the changed files.");
    assertThat(LatheMcpTools.verifyHeadline(3, 0, 1)).isEqualTo("3 error(s).");
  }

  @Test
  void verifyHeadline_namedFileNotChecked_neverSaysEverythingCompiles() {
    assertThat(LatheMcpTools.verifyHeadline(0, 0, 1))
        .isEqualTo("No errors in what was checked; 1 named file(s) not checked.");
    assertThat(LatheMcpTools.verifyHeadline(0, 2, 3))
        .isEqualTo(
            "No errors in what was checked; 2 warning(s) in the changed files; 3 named file(s) not checked.");
  }

  // A reactor file carries no tag (most answers are reactor code); anything else says where it is.
  @Test
  void locationLine_reactorAndExternal_oneGrepStyleLineTaggedOnlyOutsideTheReactor() {

    assertThat(
            LatheMcpTools.locationLine(
                new LatheLocation(
                    "file:///ws/app/src/main/java/App.java",
                    "app/src/main/java/App.java",
                    411,
                    8,
                    LatheLocation.Origin.REACTOR,
                    "client.connect();")))
        .isEqualTo("app/src/main/java/App.java:412: client.connect();");
    assertThat(
            LatheMcpTools.locationLine(
                new LatheLocation(
                    "jar:file:///repo/lib-sources.jar!/Lib.java",
                    "jar:file:///repo/lib-sources.jar!/Lib.java",
                    411,
                    8,
                    LatheLocation.Origin.EXTERNAL,
                    "void connect() {")))
        .isEqualTo("jar:file:///repo/lib-sources.jar!/Lib.java:412 [EXTERNAL]: void connect() {");
  }

  // A capped list says how many there are in all; an empty one says none, without the authority.
  @Test
  void referencesAndImplementationsResult_cappedListAndEmpty_headerLinesThenAuthority_orNone() {
    final var call = location("app/App.java", 12, "shop.sell(1);");
    final var dependency =
        location("lib/Lib.java", 3, LatheLocation.Origin.EXTERNAL, "void sell(int n) {");

    assertThat(
            text(
                LatheMcpTools.referencesResult(
                    new LatheReferences(5, true, List.of(call, dependency)),
                    LatheMcpTools.NO_NOTES)))
        .startsWith(
            lines(
                "5 references (showing first 2):",
                "app/App.java:12: shop.sell(1);",
                "lib/Lib.java:3 [EXTERNAL]: void sell(int n) {",
                "",
                "All javac-resolved references"));
    assertThat(
            text(
                LatheMcpTools.implementationsResult(
                    new LatheImplementations(1, false, List.of(call)), LatheMcpTools.NO_NOTES)))
        .startsWith(
            lines(
                "1 implementation(s):",
                "app/App.java:12: shop.sell(1);",
                "",
                "All javac-resolved implementers"));
    assertThat(
            text(
                LatheMcpTools.referencesResult(
                    new LatheReferences(0, false, List.of()), LatheMcpTools.NO_NOTES)))
        .isEqualTo("No references found.");
  }

  // The places where the old name is still text are listed for the agent to decide, never edited.
  @Test
  void renameResult_editsAndMentions_filesThenTheMentionsToReview_orNothingToRename() {
    final var rename =
        new LatheRename(
            "total",
            3,
            List.of(
                new LatheFileEdit(
                    "file:///w/app/Shop.java", "app/Shop.java", LatheLocation.Origin.REACTOR, 2),
                new LatheFileEdit(
                    "file:///w/gen/ShopView.java",
                    "gen/ShopView.java",
                    LatheLocation.Origin.GENERATED,
                    1)),
            List.of(location("app/Shop.java", 7, "// the count of items")),
            4);

    final String text = text(LatheMcpTools.renameResult(rename, LatheMcpTools.NO_NOTES));

    assertThat(text)
        .startsWith(
            lines(
                "Renamed to total: 3 edit(s) across 2 file(s):",
                "  app/Shop.java — 2 edit(s)",
                "  gen/ShopView.java [GENERATED] — 1 edit(s)"))
        .endsWith(
            lines(
                "Not renamed — review: the old name is still text in 4 place(s) (showing first 1):",
                "app/Shop.java:7: // the count of items"));
    assertThat(
            text(
                LatheMcpTools.renameResult(
                    new LatheRename("total", 0, List.of(), List.of(), 0), LatheMcpTools.NO_NOTES)))
        .isEqualTo("No references to rename.");
  }

  // Outgoing entries are call sites; an empty reactor-only answer points at scope=all.
  @Test
  void callHierarchyResult_incomingOutgoingAndEmpty_arrowLinesOrWhatTheScopeLeftOut() {
    final var caller = new LatheCall("run", location("app/App.java", 12, "shop.sell(1);"));
    final var callee = new LatheCall("total", location("app/Shop.java", 9, "return total();"));

    assertThat(
            text(
                LatheMcpTools.callHierarchyResult(
                    new LatheCallHierarchy(true, 3, true, List.of(caller)),
                    SymbolScope.REACTOR,
                    LatheMcpTools.NO_NOTES)))
        .isEqualTo(lines("3 callers (showing first 1):", "← run  app/App.java:12: shop.sell(1);"));
    assertThat(
            text(
                LatheMcpTools.callHierarchyResult(
                    new LatheCallHierarchy(false, 1, false, List.of(callee)),
                    SymbolScope.ALL,
                    LatheMcpTools.NO_NOTES)))
        .isEqualTo(lines("1 call(s):", "→ total  app/Shop.java:9: return total();"));
    assertThat(
            text(
                LatheMcpTools.callHierarchyResult(
                    new LatheCallHierarchy(false, 0, false, List.of()),
                    SymbolScope.REACTOR,
                    LatheMcpTools.NO_NOTES)))
        .isEqualTo(
            "No callees in the reactor; scope=all includes calls into the JDK and dependencies.");
    assertThat(
            text(
                LatheMcpTools.callHierarchyResult(
                    new LatheCallHierarchy(false, 0, false, List.of()),
                    SymbolScope.ALL,
                    LatheMcpTools.NO_NOTES)))
        .isEqualTo("No calls found.");
    assertThat(
            text(
                LatheMcpTools.callHierarchyResult(
                    new LatheCallHierarchy(true, 0, false, List.of()),
                    SymbolScope.REACTOR,
                    LatheMcpTools.NO_NOTES)))
        .isEqualTo("No callers found.");
  }

  @Test
  void searchResult_symbolsAndNoMatch_oneLineEachWithItsContainer_orTheQuery() {
    final var symbols =
        List.of(
            new LatheSymbol(
                "Shop", "class", "com.example", location("app/Shop.java", 3, "class Shop {")),
            new LatheSymbol("sell", "method", "", location("app/Shop.java", 8, "int sell() {")));

    assertThat(text(LatheMcpTools.searchResult("S", symbols, LatheMcpTools.NO_NOTES)))
        .isEqualTo(
            lines(
                "2 symbol(s):",
                "Shop (class) — com.example  app/Shop.java:3",
                "sell (method)  app/Shop.java:8"));
    assertThat(text(LatheMcpTools.searchResult("Cart", List.of(), LatheMcpTools.NO_NOTES)))
        .isEqualTo("No symbols matching 'Cart'.");
  }

  // A run that reported no test says how the JVM ended instead of a PASS over nothing.
  @Test
  void testRunResult_blockedNothingRanFailedAndPassed_eachSaysWhatHappened() {
    assertThat(
            text(
                LatheMcpTools.testRunResult(
                    testRun(false, List.of("no runner jar", "not captured"), 0, 0, 0, List.of()),
                    LatheMcpTools.NO_NOTES)))
        .isEqualTo("Test run blocked: no runner jar; not captured");
    assertThat(
            text(
                LatheMcpTools.testRunResult(
                    new LatheTestRun(
                        true,
                        List.of(),
                        List.of("com.example.ShopTest"),
                        0,
                        0,
                        0,
                        0,
                        List.of(),
                        1,
                        List.of("Error: could not find the main class"),
                        List.of()),
                    LatheMcpTools.NO_NOTES)))
        .isEqualTo(
            lines(
                "No test ran (exit 1)",
                "Ran: com.example.ShopTest",
                "    | Error: could not find the main class"));
    assertThat(
            text(
                LatheMcpTools.testRunResult(
                    testRun(
                        true,
                        List.of(),
                        1,
                        1,
                        1,
                        List.of(
                            new LatheTestFailure(
                                "ShopTest.sell",
                                "expected 2 but was 1",
                                14,
                                List.of("at ShopTest.sell")))),
                    LatheMcpTools.NO_NOTES)))
        .isEqualTo(
            lines(
                "FAIL — 1 passed, 1 failed, 1 skipped",
                "Ran: com.example.ShopTest",
                "  ShopTest.sell:14 — expected 2 but was 1",
                "    | at ShopTest.sell",
                "Note: classes from the last build"));
    assertThat(
            text(
                LatheMcpTools.testRunResult(
                    testRun(true, List.of(), 2, 0, 0, List.of()), LatheMcpTools.NO_NOTES)))
        .startsWith(lines("PASS — 2 passed, 0 failed, 0 skipped", "Ran: com.example.ShopTest"));
  }

  private static LatheTestRun testRun(
      final boolean launched,
      final List<String> blockedReasons,
      final int passed,
      final int failed,
      final int skipped,
      final List<LatheTestFailure> failures) {
    return new LatheTestRun(
        launched,
        blockedReasons,
        launched ? List.of("com.example.ShopTest") : List.of(),
        passed + failed + skipped,
        passed,
        failed,
        skipped,
        failures,
        failed == 0 ? 0 : 1,
        List.of(),
        launched ? List.of("classes from the last build") : List.of());
  }

  @Test
  void analyzeResult_impactAndNoSymbol_everyFieldOnItsLine_noneWhereEmpty() {
    final var base = location("app/Base.java", 4, "abstract int sell(int count);");
    final var override = location("app/Shop.java", 8, "int sell(int count) {");

    assertThat(
            text(
                LatheMcpTools.analyzeResult(
                    new LatheChangeImpact(
                        "int sell(int count)",
                        List.of(base, override),
                        4,
                        2,
                        true,
                        List.of("app", "core"),
                        List.of("ShopTest")),
                    LatheMcpTools.NO_NOTES)))
        .isEqualTo(
            lines(
                "int sell(int count)",
                "references: 4 production, 2 test (truncated)",
                "affected modules: app, core",
                "relevant tests: ShopTest",
                "override family: 2",
                "app/Base.java:4: abstract int sell(int count);",
                "app/Shop.java:8: int sell(int count) {"));
    assertThat(
            text(
                LatheMcpTools.analyzeResult(
                    new LatheChangeImpact("", List.of(), 0, 0, false, List.of(), List.of()),
                    LatheMcpTools.NO_NOTES)))
        .isEqualTo(
            lines(
                "(no symbol at that position)",
                "references: 0 production, 0 test",
                "affected modules: none",
                "relevant tests: none",
                "override family: none"));
  }

  private static LatheDiagnostic diagnostic(
      final Range range,
      final String message,
      final DiagnosticSeverity severity,
      final String source) {
    return LatheDiagnostic.of("Shop.java", new Diagnostic(range, message, severity, source));
  }

  // Never "compiles cleanly": a file no module checked says so, and a clean one says no problems.
  @Test
  void diagnosticsText_notCheckedCleanAndDiagnostics_saysWhichWithOneLinePerDiagnostic() {
    final Path file = tmp.resolve("Shop.java");
    final var error =
        diagnostic(
            new Range(new Position(4, 9), new Position(4, 12)),
            "cannot find symbol",
            DiagnosticSeverity.Error,
            "javac");
    final var warning =
        diagnostic(
            new Range(new Position(0, 0), new Position(0, 1)),
            "unchecked call",
            DiagnosticSeverity.Warning,
            "javac");

    assertThat(
            LatheMcpTools.diagnosticsText(
                file, List.of(error), LatheNotes.ofNotChecked(List.of("Shop.java: no module"))))
        .isEqualTo("Shop.java was not checked.");
    assertThat(LatheMcpTools.diagnosticsText(file, List.of(), LatheMcpTools.NO_NOTES))
        .isEqualTo("No errors or warnings in Shop.java.");
    assertThat(LatheMcpTools.diagnosticsText(file, List.of(error, warning), LatheMcpTools.NO_NOTES))
        .isEqualTo(
            lines(
                "2 diagnostic(s) in Shop.java:",
                "  Error [5:10] cannot find symbol",
                "  Warning [1:1] unchecked call"));
  }

  // The engine's lines are 0-based like every position; the agent reads them 1-based.
  @Test
  void formatResult_oneLineOrASpanOrNothing_linesAsTheAgentReadsThem() {
    final Path file = tmp.resolve("Shop.java");

    assertThat(
            text(
                LatheMcpTools.formatResult(
                    file,
                    Optional.of(new LatheFormat("app/Shop.java", 5, 7)),
                    LatheMcpTools.NO_NOTES)))
        .isEqualTo("Formatted app/Shop.java: lines 6–8 changed.");
    assertThat(
            text(
                LatheMcpTools.formatResult(
                    file,
                    Optional.of(new LatheFormat("app/Shop.java", 0, 0)),
                    LatheMcpTools.NO_NOTES)))
        .isEqualTo("Formatted app/Shop.java: line 1 changed.");
    assertThat(text(LatheMcpTools.formatResult(file, Optional.empty(), LatheMcpTools.NO_NOTES)))
        .isEqualTo("Shop.java was already formatted.");
  }
}
