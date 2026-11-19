package io.github.aglibs.lathe.mcp;

import static io.github.aglibs.lathe.mcp.McpFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.LatheEngine;
import io.github.aglibs.lathe.server.LatheEngine.TestScope;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.math.BigDecimal;
import java.math.BigInteger;
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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
    assertThat(tool("outline").tool().inputSchema().toString()).contains("file", "type");
  }

  // A column is LSP's, in UTF-16 code units, which an agent counting characters gets wrong past an
  // emoji: every tool that takes one, whatever its schema, says so.
  @Test
  void all_columnInputs_nameTheirUnit() {
    final List<String> withColumn =
        specs.stream()
            .map(spec -> spec.tool())
            .filter(tool -> tool.inputSchema().toString().contains("column"))
            .map(tool -> tool.name())
            .toList();

    assertThat(withColumn).contains("get_definition", "rename_symbol", "call_hierarchy");
    for (final String name : withColumn) {
      assertThat(tool(name).tool().inputSchema().toString()).as(name).contains("UTF-16 code units");
    }
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
  void integerArguments_overflowOrFraction_rejectedBeforeTheEngineStarts() {
    specs =
        LatheMcpTools.all(
            () -> {
              throw new AssertionError("Invalid integers must not start the engine");
            },
            McpJsonDefaults.getMapper());
    final List<Object> invalid =
        List.of(
            2147483648L,
            4294967297L,
            4294967437L,
            Long.MAX_VALUE,
            Long.MIN_VALUE,
            new BigInteger("18446744073709551617"),
            new BigDecimal("1.5"),
            new BigDecimal("1.00000000000000000001"),
            Double.NaN,
            Double.POSITIVE_INFINITY,
            "1",
            true);
    for (final SyncToolSpecification spec : specs) {
      final Map<?, ?> properties = (Map<?, ?>) spec.tool().inputSchema().get("properties");
      for (final String argument : List.of("line", "column", "maxResults")) {
        if (!properties.containsKey(argument)) {
          continue;
        }

        for (final Object value : invalid) {
          final CallToolResult result = call(spec.tool().name(), Map.of(argument, value));
          assertThat(result.isError()).as("%s %s=%s", spec.tool().name(), argument, value).isTrue();
          assertThat(text(result))
              .isEqualTo(
                  "%s '%s' must be an integer between 1 and 2147483647"
                      .formatted(spec.tool().name(), argument));
        }
      }
    }
  }

  @Test
  void integerArguments_validBoundariesAndAbsentOptionals_reachTheTool() throws Exception {
    final Path file = tmp.resolve("A.java");
    Files.writeString(file, "class A {}\n");
    for (final Number value :
        List.of(1, Integer.MAX_VALUE, (long) Integer.MAX_VALUE, new BigDecimal("2147483647.0"))) {
      assertThat(text(call("find_references", Map.of("maxResults", value))))
          .startsWith("find_references requires 'file'");
      assertThat(
              text(
                  call(
                      "describe_symbol",
                      Map.of("file", file.toString(), "line", value, "symbol", "A"))))
          .doesNotContain("must be an integer between");
      assertThat(
              text(
                  call(
                      "describe_symbol",
                      Map.of("file", file.toString(), "line", 1, "column", value))))
          .doesNotContain("must be an integer between");
    }

    assertThat(call("search_symbols", Map.of("query", "A")).isError()).isNotEqualTo(true);
    assertThat(named(file, 1, "A", null).isError()).isNotEqualTo(true);
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
  void runTest_methodWithAnotherScope_returnsErrorNamingTheConflict() {
    final String file = tmp.resolve("FooTest.java").toString();

    for (final String scope : List.of("class", "package")) {
      final CallToolResult result =
          call("run_test", Map.of("file", file, "scope", scope, "method", "m"));

      assertThat(result.isError()).as(scope).isTrue();
      assertThat(text(result))
          .isEqualTo(
              "run_test 'method' runs one method: leave out 'scope' or set it to method, not %s"
                  .formatted(scope));
    }

    assertThat(
            List.of(
                call("run_test", Map.of("file", file, "method", "m")),
                call("run_test", Map.of("file", file, "scope", "class", "method", " "))))
        .as("a method alone, a blank method beside a scope")
        .allSatisfy(result -> assertThat(text(result)).doesNotContain("runs one method"));
    assertThat(text(call("run_test", Map.of("file", file, "scope", "method", "method", ""))))
        .isEqualTo("run_test with scope=method requires a 'method' argument");
  }

  @Test
  void testScope_noScope_methodWhenOneIsNamedElseClass() {
    assertThat(LatheMcpTools.testScope(null, "m")).isEqualTo(TestScope.METHOD);
    assertThat(LatheMcpTools.testScope(null, null)).isEqualTo(TestScope.CLASS);
    assertThat(LatheMcpTools.testScope("Package", "m")).isEqualTo(TestScope.PACKAGE);
    assertThat(LatheMcpTools.testScope("nope", null)).isNull();
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
                  .contains("(columns count UTF-16 code units)")
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

  // The read tools answer only for the workspace and the dependency and JDK sources; any other
  // file is refused as such, not checked and explained as a module a profile left out.
  @Test
  void fileTools_fileOutsideTheWorkspace_returnError(@TempDir final Path outside) throws Exception {
    final Path foreign = Files.writeString(outside.resolve("Foreign.java"), "class Foreign {}");
    final Path loose = Files.writeString(tmp.resolve("Loose.java"), "class Loose {}");
    final String refusal =
        "%s is outside the workspace and its dependency and JDK sources".formatted(foreign);

    assertThat(
            List.of(call("outline", Map.of("file", foreign.toString())), describe(foreign, 1, 7)))
        .allSatisfy(
            result -> {
              assertThat(result.isError()).isTrue();
              assertThat(text(result)).isEqualTo(refusal);
            });
    assertThat(call("outline", Map.of("file", loose.toString())).isError()).isFalse();
    assertThat(text(call("verify_change", Map.of("files", List.of(foreign.toString())))))
        .contains(
            "Not checked: %s is outside the workspace, so Lathe cannot check it"
                .formatted(foreign));
  }

  // A link is judged by the file it points to: one in the workspace to a file outside it is
  // outside too. Symbolic links need privileges on Windows.
  @Test
  @DisabledOnOs(OS.WINDOWS)
  void fileTools_linkInTheWorkspaceToAFileOutside_returnError(@TempDir final Path outside)
      throws Exception {
    final Path target = Files.writeString(outside.resolve("Secret.java"), "class Secret {}");
    final Path link = Files.createSymbolicLink(tmp.resolve("Secret.java"), target);

    final CallToolResult result = call("get_diagnostics", Map.of("file", link.toString()));

    assertThat(result.isError()).isTrue();
    assertThat(text(result))
        .isEqualTo(
            "%s is outside the workspace and its dependency and JDK sources".formatted(link));
  }

  // A blank type names none: the outline is the whole file's, not an unknown type's error.
  @Test
  void outline_blankType_outlinesTheWholeFile() throws Exception {
    final Path file = Files.writeString(tmp.resolve("Loose.java"), "class Loose {}");

    final CallToolResult result = call("outline", Map.of("file", file.toString(), "type", " "));

    assertThat(result.isError()).isFalse();
    assertThat(text(result)).doesNotContain("No type");
  }

  // An agent that also drives an LSP may pass a file: URI: it is the file it names, not a path
  // relative to the workspace. A URI naming a host stands for no local file.
  @Test
  void outline_fileUri_outlinesTheFileItNames_hostUriNotFound() throws Exception {
    final Path file = Files.writeString(tmp.resolve("Loose.java"), "class Loose {}");

    final CallToolResult byUri = call("outline", Map.of("file", file.toUri().toString()));
    final CallToolResult byHost = call("outline", Map.of("file", "file://server/share/Loose.java"));

    assertThat(byUri.isError()).isFalse();
    assertThat(text(byUri)).contains("Loose");
    assertThat(byHost.isError()).isTrue();
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

  // What fails before a tool's own checks still answers as a tool result: a call with no
  // arguments at all, or a path the OS rejects.
  @Test
  void all_noArgumentsOrAnInvalidPath_answerAsAToolResult() {
    assertThat(specs)
        .allSatisfy(
            spec ->
                assertThat(
                        spec.callHandler()
                            .apply(null, CallToolRequest.builder(spec.tool().name()).build()))
                    .isNotNull());
    assertThat(call("get_diagnostics", Map.of("file", "Bad\0Name.java")).isError()).isTrue();
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
  void maxResults_everyToolTakingIt_declaresTheSupportedRange() {
    assertThat(specs)
        .map(spec -> ((Map<?, ?>) spec.tool().inputSchema().get("properties")).get("maxResults"))
        .filteredOn(Objects::nonNull)
        .as("find_references, find_implementations, call_hierarchy, search_symbols, analyze_change")
        .hasSize(5)
        .allSatisfy(
            schema -> assertThat(schema.toString()).contains("minimum=1", "maximum=2147483647"));
  }

  @Test
  void positions_everyToolTakingThem_declaresTheSupportedRange() {
    for (final SyncToolSpecification spec : specs) {
      final Map<?, ?> properties = (Map<?, ?>) spec.tool().inputSchema().get("properties");
      for (final String argument : List.of("line", "column")) {
        if (properties.containsKey(argument)) {
          assertThat(properties.get(argument).toString())
              .contains("minimum=1", "maximum=2147483647");
        }
      }
    }
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
