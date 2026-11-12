package com.example.verify;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.itsupport.Os;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

// The build ran with Lathe on -Dmaven.ext.class.path and failOnError off (invoker.properties), and
// core does not compile.
class PersonalActivationTest {

  private static final Path LATHE = Path.of(System.getProperty("user.dir")).resolveSibling(".lathe");

  // Without failOnError off, core's failure would skip app, leaving it uncaptured.
  @Test
  void build_brokenUpstreamModule_capturesEveryModule() {
    assertThat(LATHE.resolve("core").resolve("lsp-params-classes.json")).isRegularFile();
    assertThat(LATHE.resolve("app").resolve("lsp-params-classes.json")).isRegularFile();
    assertThat(LATHE.resolve("app").resolve("lsp-params-test-classes.json")).isRegularFile();
  }

  // Every later sync must bring Lathe in the same way, and stop when a jar it names is gone.
  @Test
  void syncScript_personalActivation_isReplayedAndItsJarsRequired() throws IOException {
    final String script =
        Files.readString(LATHE.resolve(Os.script("lathe-sync")), StandardCharsets.UTF_8);

    assertThat(script)
        .contains("-Dmaven.ext.class.path=")
        .contains("lathe-maven-extension-")
        .contains("gson-")
        .contains("-Dmaven.compiler.failOnError=false")
        .contains("it brought Lathe into this build");
  }

  @Test
  void sync_latheDirectory_ignoresItself() {
    assertThat(LATHE.resolve(".gitignore")).hasContent("*");
  }
}
