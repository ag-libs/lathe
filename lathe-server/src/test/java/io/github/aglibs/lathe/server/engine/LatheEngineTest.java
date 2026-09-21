package io.github.aglibs.lathe.server.engine;

import static io.github.aglibs.lathe.server.analysis.SourceLocator.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.engine.LatheEngine.CallDirection;
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
    final GreetFixture fx = greetFixture();
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(fx.callerSource(), fx.callerSource().indexOf("greet()"));
    final List<LatheLocation> targets =
        engine.definition(fx.caller(), pos.getLine(), pos.getCharacter());

    assertThat(targets).hasSize(1);
    final LatheLocation target = targets.getFirst();
    assertThat(target.uri()).endsWith("Callee.java");
    assertThat(target.origin()).isEqualTo(LatheLocation.Origin.REACTOR);
    assertThat(target.snippet()).contains("void greet");
  }

  @Test
  void references_methodUsedInAnotherFile_findsUsageWithSnippet() throws Exception {
    final GreetFixture fx = greetFixture();
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(fx.calleeSource(), fx.calleeSource().indexOf("greet"));
    final LatheReferences references =
        engine.references(fx.callee(), pos.getLine(), pos.getCharacter(), 50);

    assertThat(references.references())
        .anyMatch(r -> r.uri().endsWith("Caller.java") && r.snippet().contains("greet"));
  }

  @Test
  void callHierarchy_findsIncomingCallerAndOutgoingCallee() throws Exception {
    final GreetFixture fx = greetFixture();
    engine = new LatheEngine(tmp);

    final var calleePos = offsetToPosition(fx.calleeSource(), fx.calleeSource().indexOf("greet"));
    final LatheCallHierarchy incoming =
        engine.callHierarchy(
            fx.callee(), calleePos.getLine(), calleePos.getCharacter(), CallDirection.INCOMING, 50);
    assertThat(incoming.incoming()).isTrue();
    assertThat(incoming.calls())
        .anySatisfy(
            call -> {
              assertThat(call.location().uri()).endsWith("Caller.java");
              assertThat(call.location().snippet()).contains("greet");
            });

    final var callerPos = offsetToPosition(fx.callerSource(), fx.callerSource().indexOf("run"));
    final LatheCallHierarchy outgoing =
        engine.callHierarchy(
            fx.caller(), callerPos.getLine(), callerPos.getCharacter(), CallDirection.OUTGOING, 50);
    assertThat(outgoing.incoming()).isFalse();
    assertThat(outgoing.calls()).anySatisfy(call -> assertThat(call.name()).contains("greet"));
  }

  @Test
  void describe_method_returnsSignatureMarkdown() throws Exception {
    final GreetFixture fx = greetFixture();
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(fx.calleeSource(), fx.calleeSource().indexOf("greet"));
    final String markup = engine.describe(fx.callee(), pos.getLine(), pos.getCharacter());

    assertThat(markup).contains("greet");
  }

  @Test
  void searchSymbols_byName_findsReactorType() throws Exception {
    greetFixture();
    engine = new LatheEngine(tmp);

    final List<LatheSymbol> symbols = engine.searchSymbols("Callee", 50);

    assertThat(symbols).anySatisfy(symbol -> assertThat(symbol.name()).contains("Callee"));
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
    final GreetFixture fx = greetFixture();
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(fx.calleeSource(), fx.calleeSource().indexOf("greet"));
    final LatheRename rename =
        engine.rename(fx.callee(), pos.getLine(), pos.getCharacter(), "welcome");

    assertThat(rename.newName()).isEqualTo("welcome");
    assertThat(rename.totalEdits()).isGreaterThanOrEqualTo(2);
    assertThat(rename.files())
        .extracting(LatheFileEdit::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Callee.java"))
        .anySatisfy(uri -> assertThat(uri).endsWith("Caller.java"));
    assertThat(Files.readString(fx.callee())).contains("void welcome()").doesNotContain("greet");
    assertThat(Files.readString(fx.caller())).contains("c.welcome()").doesNotContain("greet");
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

  // Callee.greet() called from Caller.run(), compiled into .lathe so cross-file resolution works.
  private GreetFixture greetFixture() throws Exception {
    final String calleeSource =
        """
        package com.example;
        class Callee {
          void greet() {}
        }
        """;
    final Path callee =
        TestCompiler.writeModuleSource(tmp, "com/example/Callee.java", calleeSource);
    final String callerSource =
        "package com.example; class Caller { void run(Callee c) { c.greet(); } }";
    final Path caller =
        TestCompiler.writeModuleSource(tmp, "com/example/Caller.java", callerSource);
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), callee, caller);
    return new GreetFixture(callee, caller, calleeSource, callerSource);
  }

  private record GreetFixture(Path callee, Path caller, String calleeSource, String callerSource) {}
}
