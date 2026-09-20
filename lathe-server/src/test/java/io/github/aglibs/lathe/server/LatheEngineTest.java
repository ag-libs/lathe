package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.server.analysis.SourceLocator.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheEngineTest {

  @TempDir private Path tmp;
  private LatheEngine engine;

  @AfterEach
  void close() {
    if (engine != null) {
      engine.close();
    }
  }

  @Test
  void diagnostics_cleanSource_returnsNoDiagnostics() throws Exception {
    final Path source =
        TestCompiler.writeModuleSource(
            tmp, "com/example/Sample.java", "package com.example; class Sample { int n = 1; }");
    engine = new LatheEngine(tmp);

    final List<Diagnostic> diagnostics = engine.diagnostics(source);

    assertThat(diagnostics).isEmpty();
  }

  @Test
  void diagnostics_typeError_returnsCompilerDiagnostic() throws Exception {
    final Path source =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Sample.java",
            "package com.example; class Sample { int n = \"nope\"; }");
    engine = new LatheEngine(tmp);

    final List<Diagnostic> diagnostics = engine.diagnostics(source);

    assertThat(diagnostics).isNotEmpty();
    assertThat(diagnostics)
        .anyMatch(d -> d.getMessage().getLeft().toLowerCase().contains("incompatible types"));
  }

  @Test
  void definition_crossFileInModule_resolvesTargetWithSnippet() throws Exception {
    final Path callee =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Callee.java",
            """
            package com.example;
            class Callee {
              void greet() {}
            }
            """);
    final String callerContent =
        "package com.example; class Caller { void run(Callee c) { c.greet(); } }";
    final Path caller =
        TestCompiler.writeModuleSource(tmp, "com/example/Caller.java", callerContent);
    // Cross-file resolution is against the synced .lathe/ bytecode, so Callee must be compiled.
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), callee, caller);
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(callerContent, callerContent.indexOf("greet()"));
    final List<LatheLocation> targets =
        engine.definition(caller, pos.getLine(), pos.getCharacter());

    assertThat(targets).hasSize(1);
    final LatheLocation target = targets.getFirst();
    assertThat(target.uri()).endsWith("Callee.java");
    assertThat(target.origin()).isEqualTo(LatheLocation.Origin.REACTOR);
    assertThat(target.snippet()).contains("void greet");
  }
}
