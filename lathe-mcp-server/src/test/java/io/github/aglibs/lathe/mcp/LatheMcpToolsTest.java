package io.github.aglibs.lathe.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.engine.LatheDiagnostic;
import io.github.aglibs.lathe.server.engine.LatheEngine;
import io.github.aglibs.lathe.server.engine.LatheLocation;
import io.github.aglibs.lathe.server.engine.LatheNotes;
import io.github.aglibs.lathe.server.engine.LatheVerifyChange;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    specs = LatheMcpTools.all(engine, McpJsonDefaults.getMapper());
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
            "format_file");

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
            "verify_change");
    assertThat(tool("rename_symbol").tool().annotations().destructiveHint()).isTrue();
    assertThat(tool("run_test").tool().annotations().destructiveHint()).isFalse();
    assertThat(tool("format_file").tool().annotations())
        .satisfies(
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
  void verifyChange_emptyWorkspace_reportsCleanNotError() {
    final CallToolResult result = call("verify_change", Map.of());

    assertThat(result.isError()).isNotEqualTo(true);
    @SuppressWarnings("unchecked")
    final Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
    assertThat(structured).containsEntry("diagnosticCount", 0);
  }

  @Test
  void searchSymbols_blankQuery_returnsError() {
    assertThat(call("search_symbols", Map.of("query", " ")).isError()).isTrue();
  }

  @Test
  void callHierarchy_invalidDirection_returnsError() {
    final CallToolResult result =
        call(
            "call_hierarchy",
            Map.of(
                "file",
                tmp.resolve("Any.java").toString(),
                "line",
                1,
                "column",
                1,
                "direction",
                "sideways"));

    assertThat(result.isError()).isTrue();
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
  void renameSymbol_invalidIdentifier_returnsErrorNotException() {
    final CallToolResult result =
        call(
            "rename_symbol",
            Map.of(
                "file",
                tmp.resolve("Any.java").toString(),
                "line",
                1,
                "column",
                1,
                "newName",
                "not a name"));

    assertThat(result.isError()).isTrue();
  }

  // An off-by-one column would otherwise answer for the enclosing method or class.
  @Test
  void describeSymbol_positionNotOnAName_returnsErrorShowingTheLine() throws Exception {
    final Path file = tmp.resolve("A.java");
    Files.writeString(file, "class A {\n  public void run() {}\n}\n");

    assertThat(List.of(describe(file, 2, 3), describe(file, 2, 1), describe(file, 2, 30)))
        .as("a keyword, indentation, past the line's end")
        .allSatisfy(
            result -> {
              assertThat(result.isError()).isTrue();
              assertThat(text(result))
                  .contains("must be on a name. Line 2 reads: public void run() {}");
            });
    assertThat(text(describe(file, 9, 1))).isEqualTo("No symbol at line 9: the file is shorter.");
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
  }

  // Without a pinned formatter "already formatted" would be a lie: the answer is an error with the
  // remedy.
  @Test
  void formatFile_noFormatterPinned_returnsErrorWithTheSyncRemedy() throws Exception {
    final Path file = Files.writeString(tmp.resolve("Loose.java"), "class Loose {}");

    final CallToolResult result = call("format_file", Map.of("file", file.toString()));

    assertThat(result.isError()).isTrue();
    assertThat(text(result)).contains("No formatter for this workspace", ".lathe/lathe-sync.sh");
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
                new LatheDiagnostic("jetty/src/main/java/Http.java", diagnostic)))
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

  @Test
  void logged_recordsToolNameAndArguments() {
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
    logger.setLevel(Level.INFO);
    logger.addHandler(handler);
    try {
      call("get_diagnostics", Map.of("file", file));
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(previous);
    }

    assertThat(records)
        .extracting(LogRecord::getMessage)
        .anySatisfy(
            message ->
                assertThat(message).contains("[tool] get_diagnostics", "file=%s".formatted(file)));
  }

  private CallToolResult describe(final Path file, final int line, final int column) {
    return call("describe_symbol", Map.of("file", file.toString(), "line", line, "column", column));
  }

  private static String text(final CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }

  private CallToolResult call(final String name, final Map<String, Object> arguments) {
    final var request = CallToolRequest.builder(name).arguments(arguments).build();
    return tool(name).callHandler().apply(null, request);
  }

  private SyncToolSpecification tool(final String name) {
    return specs.stream().filter(spec -> spec.tool().name().equals(name)).findFirst().orElseThrow();
  }

  @Test
  void noteLines_eachKindOfNote_rendersItsLine_noneWhenNothingToSay() {
    assertThat(LatheMcpTools.noteLines(new LatheNotes(List.of(), List.of(), List.of(), false)))
        .isEmpty();
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
    assertThat(LatheMcpTools.verifyHeadline(0, 0))
        .isEqualTo("No errors — the changed files and their callers compile.");
    assertThat(LatheMcpTools.verifyHeadline(0, 7))
        .isEqualTo(
            "No errors — the changed files and their callers compile; 7 warning(s) in the changed files.");
    assertThat(LatheMcpTools.verifyHeadline(2, 1))
        .isEqualTo("2 error(s); 1 warning(s) in the changed files.");
    assertThat(LatheMcpTools.verifyHeadline(3, 0)).isEqualTo("3 error(s).");
  }

  // A reactor file carries no tag (most answers are reactor code); anything else says where it is.
  @Test
  void locationLine_reactorAndExternal_oneGrepStyleLineTaggedOnlyOutsideTheReactor() {
    final var line = new Range(new Position(411, 8), new Position(411, 20));

    assertThat(
            LatheMcpTools.locationLine(
                new LatheLocation(
                    "file:///ws/app/src/main/java/App.java",
                    "app/src/main/java/App.java",
                    line,
                    LatheLocation.Origin.REACTOR,
                    "client.connect();")))
        .isEqualTo("app/src/main/java/App.java:412: client.connect();");
    assertThat(
            LatheMcpTools.locationLine(
                new LatheLocation(
                    "jar:file:///repo/lib-sources.jar!/Lib.java",
                    "jar:file:///repo/lib-sources.jar!/Lib.java",
                    line,
                    LatheLocation.Origin.EXTERNAL,
                    "void connect() {")))
        .isEqualTo("jar:file:///repo/lib-sources.jar!/Lib.java:412 [EXTERNAL]: void connect() {");
  }
}
