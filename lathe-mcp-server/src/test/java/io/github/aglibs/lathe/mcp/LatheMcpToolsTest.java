package io.github.aglibs.lathe.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.engine.LatheEngine;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
            "get_diagnostics", "get_definition", "find_references", "rename_symbol");

    assertThat(tool("get_diagnostics").tool().inputSchema().toString()).contains("file");
    assertThat(tool("get_definition").tool().inputSchema().toString())
        .contains("file", "line", "column");
    assertThat(tool("find_references").tool().inputSchema().toString())
        .contains("file", "line", "column", "maxResults");
    assertThat(tool("rename_symbol").tool().inputSchema().toString())
        .contains("file", "line", "column", "newName");
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

  private CallToolResult call(final String name, final Map<String, Object> arguments) {
    final var request = CallToolRequest.builder(name).arguments(arguments).build();
    return tool(name).callHandler().apply(null, request);
  }

  private SyncToolSpecification tool(final String name) {
    return specs.stream().filter(spec -> spec.tool().name().equals(name)).findFirst().orElseThrow();
  }
}
