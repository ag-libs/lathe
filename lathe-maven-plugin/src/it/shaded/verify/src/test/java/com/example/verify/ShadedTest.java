package com.example.verify;

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

// json shades Gson into its main jar, and app compiles against the relocated classes, which only
// that jar holds. Lathe keeps the shaded jar on app's classpath instead of json's mirror, and the
// project's lathe.sync.phase=package makes the sync script build the jar.
@Timeout(120)
class ShadedTest {

  private static final Path LATHE = LspSession.ROOT.resolve(".lathe");
  private static final Path APP =
      LspSession.ROOT.resolve("app/src/main/java/com/example/app/App.java");

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

  // json's shaded jar is not listed as standing for json's mirror, which lacks the relocated
  // classes; app's plain jar still is.
  @Test
  void workspace_shadedMainJar_isNotMappedToTheMirror() throws Exception {
    assertThat(Files.readString(LATHE.resolve("workspace.json")))
        .doesNotContain("json/target/json-1.0.0-SNAPSHOT.jar")
        .contains("app/target/app-1.0.0-SNAPSHOT.jar");
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
