package com.example.verify;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.itsupport.LspSession;
import com.example.itsupport.McpSession;
import com.example.itsupport.Os;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

/**
 * Smoke test for the MCP launcher. Spawns the MCP launcher script (the classpath launcher) and
 * drives it over stdio with newline-delimited JSON-RPC, proving the launcher starts, the MCP server
 * initializes, the tools are exposed, and in-process javac — running as unnamed-module code on the
 * classpath — answers {@code get_diagnostics}.
 */
// A message the server can't parse gets no reply, and a blocked read would hang the fixture build.
@Timeout(value = 60, unit = SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
class McpSmokeTest {

  private static final Path ROOT = LspSession.ROOT;
  private static final Path STRING_UTILS =
      ROOT.resolve("core/src/main/java/com/example/core/StringUtils.java");

  private static McpSession mcp;

  @BeforeAll
  @Timeout(value = 60, unit = SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
  static void start() throws Exception {
    mcp = McpSession.start();
    assertThat(mcp.initResponse)
        .contains("\"name\":\"lathe\"")
        .contains("protocolVersion")
        .contains("ALWAYS use rename_symbol"); // routing guidance served in instructions
  }

  @AfterAll
  static void stop() {
    if (mcp != null) {
      mcp.stop();
    }
  }

  @Test
  void toolsList_exposesLatheTools() throws Exception {
    final String response = mcp.request("tools/list", "{}");

    assertThat(response).contains("get_diagnostics").contains("get_definition");
  }

  @Test
  void getDiagnostics_reactorSource_compilesInProcess() throws Exception {
    final Path greeter = ROOT.resolve("core/src/main/java/com/example/core/Greeter.java");

    final String response =
        mcp.callTool("get_diagnostics", "{\"file\":\"%s\"}".formatted(Os.json(greeter)));

    assertThat(response).contains("Greeter.java").doesNotContain("\"isError\":true");
  }

  @Test
  void findReferences_crossModule_findsUsageInAnotherModule() throws Exception {
    // upper() is declared in core and called from app/Main.java — a cross-module reference.
    final String response =
        mcp.callTool("find_references", "{%s}".formatted(McpSession.at(STRING_UTILS, "upper")));

    assertThat(response).contains("Main.java").doesNotContain("\"isError\":true");
  }

  @Test
  void renameSymbol_crossModule_rewritesDeclarationAndUsageOnDisk() throws Exception {
    // Renames upper() in core; the declaration and the app/Main.java usage must both change.
    // The rename mutates the shared fixture copy, so restore both files afterward to keep the
    // other smoke tests (which reference upper()) independent of this one.
    final Path main = ROOT.resolve("app/src/main/java/com/example/app/Main.java");
    final String stringUtilsOriginal = Files.readString(STRING_UTILS);
    final String mainOriginal = Files.readString(main);

    try {
      final String response =
          mcp.callTool(
              "rename_symbol",
              "{%s,\"newName\":\"upperCase\"}".formatted(McpSession.at(STRING_UTILS, "upper")));

      assertThat(response)
          .contains("StringUtils.java")
          .contains("Main.java")
          .doesNotContain("\"isError\":true");
      // The rename compiles the files it changed before returning, so its own result is current
      // and carries no note.
      assertThat(response).doesNotContain("Not compiled:").doesNotContain("Sync required:");
      assertThat(Files.readString(STRING_UTILS)).contains("String upperCase(");
      assertThat(Files.readString(main)).contains("StringUtils.upperCase(");
    } finally {
      Files.writeString(STRING_UTILS, stringUtilsOriginal);
      Files.writeString(main, mainOriginal);
    }
  }

  @Test
  void callHierarchy_incoming_findsCrossModuleCaller() throws Exception {
    // upper() is declared in core and called from app/Main.java — its caller is cross-module.
    final String response =
        mcp.callTool(
            "call_hierarchy",
            "{%s,\"direction\":\"incoming\"}".formatted(McpSession.at(STRING_UTILS, "upper")));

    assertThat(response).contains("Main.java").doesNotContain("\"isError\":true");
  }

  @Test
  void findImplementations_interface_findsCrossModuleImpls() throws Exception {
    // Greeter (core) is implemented by CasualGreeter/FormalGreeter in app — a cross-module lookup.
    final Path greeter = ROOT.resolve("core/src/main/java/com/example/core/Greeter.java");
    final String response =
        mcp.callTool("find_implementations", "{%s}".formatted(McpSession.at(greeter, "Greeter")));

    assertThat(response).contains("CasualGreeter").doesNotContain("\"isError\":true");
  }

  @Test
  void describeSymbol_reactorMethod_returnsSignature() throws Exception {
    final String response =
        mcp.callTool("describe_symbol", "{%s}".formatted(McpSession.at(STRING_UTILS, "upper")));

    assertThat(response).contains("upper").doesNotContain("\"isError\":true");
  }

  @Test
  void searchSymbols_byName_findsReactorType() throws Exception {
    final String response =
        mcp.callTool("search_symbols", "{\"query\":\"StringUtils\"}");

    assertThat(response).contains("StringUtils").doesNotContain("\"isError\":true");
  }

  @Test
  void runTest_reactorTestClass_replaysAndPasses() throws Exception {
    // HelloTest in the jpms module is all-passing and its test-launch.json is captured by the build.
    final Path helloTest = ROOT.resolve("jpms/src/test/java/com/example/jpms/HelloTest.java");

    final String response =
        mcp.callTool("run_test", "{\"file\":\"%s\"}".formatted(Os.json(helloTest)));

    assertThat(response)
        .contains("PASS")
        .contains("Ran: com.example.jpms.HelloTest")
        .doesNotContain("\"isError\":true");
  }
}
