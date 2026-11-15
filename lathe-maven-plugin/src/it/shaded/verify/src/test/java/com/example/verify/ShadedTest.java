package com.example.verify;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.itsupport.LspSession;
import io.github.aglibs.lathe.itsupport.McpSession;
import io.github.aglibs.lathe.itsupport.Os;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

// json shades Gson into its main jar, and app compiles against the relocated classes, which only
// that jar holds. The editor takes json's mirror and then the jar, so app sees both json's latest
// classes and the relocated ones; a run takes the jar alone, whose json classes name the relocated
// Gson. The project's lathe.sync.phase=package makes the sync script build the jar.
@Timeout(120)
class ShadedTest {

  private static final Path LATHE = LspSession.ROOT.resolve(".lathe");
  private static final Path APP =
      LspSession.ROOT.resolve("app/src/main/java/com/example/app/App.java");
  private static final Path JSON =
      LspSession.ROOT.resolve("json/src/main/java/com/example/json/Json.java");
  private static final Path APP_TEST =
      LspSession.ROOT.resolve("app/src/test/java/com/example/app/AppTest.java");

  private static McpSession mcp;

  @BeforeAll
  static void startServer() throws Exception {
    mcp = McpSession.start();
  }

  @AfterAll
  static void stopServer() {
    mcp.stop();
  }

  // The relocated Gson resolves: no "package com.example.json.shaded.gson does not exist".
  @Test
  void diagnostics_classUsingRelocatedClasses_reportsNoErrors() throws Exception {
    assertThat(mcp.callTool("get_diagnostics", "{\"file\":\"%s\"}".formatted(Os.json(APP))))
        .contains("No errors")
        .doesNotContain("does not exist");
  }

  // app depends on json through the shaded jar, which still stands for json's mirror: a search
  // from json reaches app.
  @Test
  void findReferences_shadedModuleMethod_findsTheDependentsUse() throws Exception {
    assertThat(mcp.callTool("find_references", "{%s}".formatted(McpSession.at(JSON, "quote"))))
        .contains("App.java")
        .doesNotContain("\"isError\":true");
  }

  // The replay keeps json's shaded jar: json's classes there call the relocated Gson.
  @Test
  void runTest_codeReachingTheRelocatedClasses_passes() throws Exception {
    assertThat(mcp.callTool("run_test", "{\"file\":\"%s\"}".formatted(Os.json(APP_TEST))))
        .contains("PASS")
        .doesNotContain("NoClassDefFoundError");
  }

  // The project chose package: the sync builds there, skipping the tests.
  @Test
  void syncScript_projectChosePackage_buildsToPackageSkippingTests() throws Exception {
    assertThat(Files.readString(LATHE.resolve(Os.script("lathe-sync"))))
        .contains("package")
        .contains("-DskipTests")
        .doesNotContain("process-test-classes");
  }
}
