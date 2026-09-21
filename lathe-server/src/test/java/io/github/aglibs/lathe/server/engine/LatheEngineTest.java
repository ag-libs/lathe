package io.github.aglibs.lathe.server.engine;

import static io.github.aglibs.lathe.server.analysis.SourceLocator.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.run.LaunchOutcome;
import io.github.aglibs.lathe.server.run.TestResult;
import io.github.aglibs.lathe.server.run.TranscriptLine;
import java.nio.file.Files;
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

  @Test
  void references_methodUsedInAnotherFile_findsUsageWithSnippet() throws Exception {
    final String calleeContent =
        """
        package com.example;
        class Callee {
          void greet() {}
        }
        """;
    final Path callee =
        TestCompiler.writeModuleSource(tmp, "com/example/Callee.java", calleeContent);
    final String callerContent =
        "package com.example; class Caller { void run(Callee c) { c.greet(); } }";
    final Path caller =
        TestCompiler.writeModuleSource(tmp, "com/example/Caller.java", callerContent);
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), callee, caller);
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(calleeContent, calleeContent.indexOf("greet"));
    final LatheReferences references =
        engine.references(callee, pos.getLine(), pos.getCharacter(), 50);

    assertThat(references.references())
        .anyMatch(r -> r.uri().endsWith("Caller.java") && r.snippet().contains("greet"));
  }

  @Test
  void staleModules_moduleNotYetSynced_reportsModule() throws Exception {
    // Source with no compile stamp (never built) counts as stale.
    TestCompiler.writeModuleSource(
        tmp, "com/example/Sample.java", "package com.example; class Sample {}");
    engine = new LatheEngine(tmp);

    assertThat(engine.staleModules()).isNotEmpty();
  }

  @Test
  void staleModules_noConfiguredModules_returnsEmpty() {
    engine = new LatheEngine(tmp);

    assertThat(engine.staleModules()).isEmpty();
  }

  @Test
  void rename_methodUsedInAnotherFile_rewritesBothFilesOnDisk() throws Exception {
    final String calleeContent =
        """
        package com.example;
        class Callee {
          void greet() {}
        }
        """;
    final Path callee =
        TestCompiler.writeModuleSource(tmp, "com/example/Callee.java", calleeContent);
    final String callerContent =
        "package com.example; class Caller { void run(Callee c) { c.greet(); } }";
    final Path caller =
        TestCompiler.writeModuleSource(tmp, "com/example/Caller.java", callerContent);
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), callee, caller);
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(calleeContent, calleeContent.indexOf("greet"));
    final LatheRename rename = engine.rename(callee, pos.getLine(), pos.getCharacter(), "welcome");

    assertThat(rename.newName()).isEqualTo("welcome");
    assertThat(rename.totalEdits()).isGreaterThanOrEqualTo(2);
    assertThat(rename.files())
        .extracting(LatheFileEdit::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Callee.java"))
        .anySatisfy(uri -> assertThat(uri).endsWith("Caller.java"));
    assertThat(Files.readString(callee)).contains("void welcome()").doesNotContain("greet");
    assertThat(Files.readString(caller)).contains("c.welcome()").doesNotContain("greet");
  }

  @Test
  void latheTestRun_completedOutcome_mapsCountsAndFailure() {
    final var outcome =
        LaunchOutcome.completed(
            0,
            List.of(new TranscriptLine(TranscriptLine.Stream.STDOUT, "ok")),
            List.of(
                new TestResult("com.example.FooTest", "passes", "", "passed", "", -1, null),
                new TestResult(
                    "com.example.FooTest",
                    "fails",
                    "",
                    "failed",
                    "java.lang.AssertionError: boom",
                    42,
                    null),
                new TestResult("com.example.FooTest", "skips", "", "skipped", "", -1, null)));

    final LatheTestRun run = LatheTestRun.from(outcome);

    assertThat(run.launched()).isTrue();
    assertThat(run.total()).isEqualTo(3);
    assertThat(run.passed()).isEqualTo(1);
    assertThat(run.failed()).isEqualTo(1);
    assertThat(run.skipped()).isEqualTo(1);
    assertThat(run.failures())
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.test()).isEqualTo("com.example.FooTest#fails");
              assertThat(f.summary()).contains("boom");
              assertThat(f.line()).isEqualTo(42);
            });
  }

  @Test
  void latheTestRun_blockedOutcome_carriesReasons() {
    final LatheTestRun run = LatheTestRun.from(LaunchOutcome.blocked(List.of("no runner jar")));

    assertThat(run.launched()).isFalse();
    assertThat(run.blockedReasons()).containsExactly("no runner jar");
    assertThat(run.total()).isZero();
  }
}
