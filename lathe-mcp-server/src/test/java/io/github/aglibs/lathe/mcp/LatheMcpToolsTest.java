package io.github.aglibs.lathe.mcp;

import static io.github.aglibs.lathe.mcp.McpFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.LatheEngine;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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
  void maxResults_belowOne_returnsErrorFromEveryToolThatTakesIt() {
    for (final String tool :
        List.of("find_references", "find_implementations", "call_hierarchy", "search_symbols")) {
      for (final int max : List.of(0, -1)) {
        assertThat(text(call(tool, Map.of("maxResults", max))))
            .isEqualTo("%s 'maxResults' must be at least 1".formatted(tool));
      }
    }

    assertThat(text(call("find_references", Map.of("maxResults", 1))))
        .as("a valid value reaches the tool's own argument check")
        .startsWith("find_references requires 'file'");
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
}
