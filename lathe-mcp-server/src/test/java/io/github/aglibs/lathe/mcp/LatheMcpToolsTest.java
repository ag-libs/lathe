package io.github.aglibs.lathe.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.engine.LatheEngine;
import io.github.aglibs.lathe.server.engine.LatheNotes;
import io.github.aglibs.lathe.server.engine.LatheVerifyChange;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;
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
            "verify_change");

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

  private CallToolResult call(final String name, final Map<String, Object> arguments) {
    final var request = CallToolRequest.builder(name).arguments(arguments).build();
    return tool(name).callHandler().apply(null, request);
  }

  private SyncToolSpecification tool(final String name) {
    return specs.stream().filter(spec -> spec.tool().name().equals(name)).findFirst().orElseThrow();
  }

  @Test
  void noteLines_eachKindOfNote_rendersItsLine_noneWhenNothingToSay() {
    assertThat(LatheMcpTools.noteLines(new LatheNotes(List.of(), List.of(), false))).isEmpty();
    assertThat(
            LatheMcpTools.noteLines(
                new LatheNotes(
                    List.of("a/A.java:3: cannot find symbol", "b/B.java:1: ';' expected"),
                    List.of("build files changed — run `.lathe/lathe-sync.sh`"),
                    true)))
        .containsExactly(
            "Not compiled: a/A.java:3: cannot find symbol; b/B.java:1: ';' expected — fix the code; cross-module answers use their last good classes.",
            "Sync required: build files changed — run `.lathe/lathe-sync.sh`",
            "A build is running: this answer reflects Lathe's classes from before it; re-query when it ends.");
    assertThat(LatheMcpServer.INSTRUCTIONS)
        .as("the routing instructions explain both notes")
        .contains("\"Not compiled:\"")
        .contains("\"Sync required:\"")
        .doesNotContain("Stale:");
  }

  @Test
  void crossModuleNote_manyDependents_namesTheFirstTenAndCountsTheRest() {
    final List<String> twelve = IntStream.rangeClosed(1, 12).mapToObj("mod%d"::formatted).toList();
    final String sync = ".lathe/lathe-sync.sh core";

    assertThat(
            LatheMcpTools.crossModuleNote(new LatheVerifyChange(null, List.of(), twelve, sync, 0)))
        .contains("12 module(s) depend on your change (mod1, mod2,")
        .contains("mod10, and 2 more)")
        .doesNotContain("mod11")
        .endsWith(sync);
    assertThat(
            LatheMcpTools.crossModuleNote(
                new LatheVerifyChange(null, List.of(), List.of("a", "b"), sync, 0)))
        .contains("2 module(s) depend on your change (a, b); their callers were checked here")
        .doesNotContain("more");
    assertThat(
            LatheMcpTools.crossModuleNote(new LatheVerifyChange(null, List.of(), twelve, null, 0)))
        .isEmpty();
  }

  @Test
  void crossModuleNote_callersPastTheCap_saysHowManyWereNotChecked() {
    final String sync = ".lathe/lathe-sync.sh app";

    assertThat(
            LatheMcpTools.crossModuleNote(
                new LatheVerifyChange(null, List.of(), List.of("app"), sync, 7)))
        .contains("1 module(s) depend on your change (app); 7 of their callers were not checked")
        .endsWith(sync);
  }
}
