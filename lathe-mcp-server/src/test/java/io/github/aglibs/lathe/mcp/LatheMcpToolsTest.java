package io.github.aglibs.lathe.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.engine.LatheEngine;
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
}
