package com.example.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

// The parent declares maven-compiler-plugin with <extensions>true</extensions>, as Quarkus' does.
class CompilerExtensionsTest {

  private static final Path LATHE = Path.of(System.getProperty("user.dir")).resolveSibling(".lathe");

  // Only Lathe's compiler writes a module's compile parameters and mirrors its classes: plain javac
  // would build core as well, and leave neither.
  @Test
  void build_compilerPluginAsBuildExtension_compilesThroughLathe() {
    assertThat(LATHE.resolve("core").resolve("lsp-params-classes.json")).isRegularFile();
    assertThat(LATHE.resolve("core/classes/com/example/core/Core.class")).isRegularFile();
  }
}
