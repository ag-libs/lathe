package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.server.analysis.SourceLocator.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.LatheEngine.SymbolScope;
import io.github.aglibs.lathe.server.analysis.testing.TestJavac;
import io.github.aglibs.lathe.server.engine.LatheChangeImpact;
import io.github.aglibs.lathe.server.engine.LatheModuleSummary;
import io.github.aglibs.lathe.server.engine.LatheVerifyChange;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;

// verify_change and analyze_change: what a change breaks, and the sync it suggests.
class LatheEngineVerifyTest extends LatheEngineTestBase {
  private static final String API_SOURCE =
      "package com.example.api;\npublic class Api { public int a() { return 1; } }\n";

  @Test
  void analyzeChange_interfaceMethod_reportsOverrideFamilyAndReferences() throws Exception {
    final String ifaceSource =
        """
        package com.example;
        interface Greeter {
          void greet();
        }
        """;
    final Path iface = TestCompiler.writeModuleSource(tmp, "com/example/Greeter.java", ifaceSource);
    final Path impl =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/EnGreeter.java",
            "package com.example; class EnGreeter implements Greeter { public void greet() {} }");
    final Path caller =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Caller.java",
            "package com.example; class Caller { void run(Greeter g) { g.greet(); } }");
    TestJavac.compileToDir(tmp.resolve(".lathe/module/classes"), iface, impl, caller);
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(ifaceSource, ifaceSource.indexOf("greet"));
    final LatheChangeImpact impact = engine.analyzeChange(iface, pos.getLine(), pos.getCharacter());

    assertThat(impact.signature()).contains("greet");
    assertThat(impact.overrideFamily())
        .anySatisfy(loc -> assertThat(loc.uri()).endsWith("EnGreeter.java"));
    assertThat(impact.productionRefs()).isGreaterThanOrEqualTo(1);
    assertThat(impact.testRefs()).isZero();
    assertThat(impact.affectedModules()).containsExactly("module");
  }

  @Test
  void verifyChange_externalEditBreaksSibling_reportsDiagnostic() throws Exception {
    final GreetFixture fx = greetFixture();
    stampModule(fx.callee(), fx.caller());
    engine = new LatheEngine(tmp);

    // Rename greet() on disk without touching the caller: the caller no longer compiles.
    writeNewer(fx.callee(), "package com.example; class Callee { void welcome() {} }");
    final LatheVerifyChange verify = engine.verifyChange(List.of());

    assertThat(verify.deferral()).isNull();
    assertThat(verify.perModule())
        .anySatisfy(
            module ->
                assertThat(module.diagnostics())
                    .anyMatch(
                        d ->
                            d.path().equals(FileUtil.slashed(tmp.relativize(fx.caller())))
                                && d.diagnostic().getMessage().getLeft().contains("greet")));
  }

  @Test
  void diagnostics_rightAfterExternalEditOfDependency_seesTheEdit() throws Exception {
    final GreetFixture fx = greetFixture();
    stampModule(fx.callee(), fx.caller());
    engine = new LatheEngine(tmp);
    engine.diagnostics(fx.caller()); // the session is warm and idle before the agent's edit

    // An agent renames greet() with its own file tool, then queries the caller at once.
    writeNewer(fx.callee(), "package com.example; class Callee { void welcome() {} }");

    assertThat(engine.diagnostics(fx.caller()))
        .anyMatch(d -> d.getMessage().getLeft().contains("greet"));
  }

  // A caller's warnings predate the change, and its hints say nothing about whether it compiles:
  // only the changed file's warning is reported, after the caller's error.
  @Test
  void verifyChange_callerWithAWarningAndAHint_reportsTheChangedFilesWarningAfterErrors()
      throws Exception {
    final Path old =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Old.java",
            "package com.example; class Old { @Deprecated(forRemoval = true) static void old() {}"
                + " }");
    final Path callee =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Callee.java",
            "package com.example; class Callee { void greet() { Old.old(); } }");
    final Path caller =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Caller.java",
            "package com.example; class Caller { void run(Callee c) { c.greet(); Old.old(); }"
                + " private void unused() {} }");
    TestJavac.compileToDir(tmp.resolve(".lathe/module/classes"), old, callee, caller);
    engine = new LatheEngine(tmp);
    assertThat(engine.diagnostics(caller))
        .as("the caller has a removal warning and an unused-method hint")
        .extracting(Diagnostic::getSeverity)
        .contains(DiagnosticSeverity.Warning, DiagnosticSeverity.Hint);

    assertThat(engine.verifyChange(List.of(callee)).perModule())
        .singleElement()
        .satisfies(
            module ->
                assertThat(module.diagnostics())
                    .extracting(d -> d.diagnostic().getSeverity())
                    .containsExactly(DiagnosticSeverity.Warning));

    writeNewer(callee, "package com.example; class Callee { void welcome() { Old.old(); } }");
    assertThat(engine.verifyChange(List.of(callee)).perModule())
        .singleElement()
        .satisfies(
            module -> {
              assertThat(module.diagnostics())
                  .extracting(d -> d.diagnostic().getSeverity())
                  .containsExactly(DiagnosticSeverity.Error, DiagnosticSeverity.Warning);
              assertThat(module.diagnostics().get(0).diagnostic().getMessage().getLeft())
                  .contains("greet");
            });
  }

  @Test
  void verifyChange_noFilesAfterARenameThatBreaksAnUneditedSubclass_reportsIt() throws Exception {
    final String source = "package com.example;\nclass Callee {\n  void greet() {}\n}\n";
    final Path callee = TestCompiler.writeModuleSource(tmp, "com/example/Callee.java", source);
    // No use of greet(): the rename leaves Sub alone, but then its static welcome() cannot hide
    // the renamed instance method; a compile error, which the rename leaves to the diagnostics.
    final Path sub =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Sub.java",
            "package com.example;\nclass Sub extends Callee {\n  static void welcome() {}\n}\n");
    TestJavac.compileToDir(tmp.resolve(".lathe/module/classes"), callee, sub);
    stampModule(callee, sub);
    engine = new LatheEngine(tmp);

    renameAt(callee, source, "greet", "welcome");
    // Callee compiled and was stamped by the rename's write, so nothing on disk reads as stale.
    final LatheVerifyChange verify = engine.verifyChange(List.of());

    assertThat(verify.perModule())
        .anySatisfy(
            module ->
                assertThat(module.diagnostics())
                    .anyMatch(d -> d.diagnostic().getMessage().getLeft().contains("welcome")));
  }

  @Test
  void verifyChange_apiChangeBreaksDependentModule_reportedAlsoAfterAnotherQueryCompiledIt()
      throws Exception {
    final Path api = apiReactor(List.of("app"), List.of());
    engine = new LatheEngine(tmp);

    // The agent renames a() in api only, with its own file tool: app no longer compiles.
    TestCompiler.writeAt(api, API_SOURCE.replace("a()", "b()"), 10_000L);
    final LatheVerifyChange verify = engine.verifyChange(List.of());

    assertThat(verify.perModule())
        .singleElement()
        .satisfies(
            module -> {
              assertThat(module.module()).isEqualTo("app");
              assertThat(module.diagnostics())
                  .anyMatch(
                      d -> d.diagnostic().getMessage().getLeft().contains("cannot find symbol"));
            });
    assertThat(verify.affectedModules()).containsExactly("app");
    assertThat(verify.suggestedSync())
        .as("builds the dependent, and with -am the changed module too")
        .isEqualTo(LatheLayout.scopedSyncCommand(List.of("app")));

    // Another such edit, but compiled by an unrelated query before verify_change is asked.
    TestCompiler.writeAt(api, API_SOURCE.replace("a()", "c()"), 20_000L);
    engine.diagnostics(api);

    assertThat(engine.verifyChange(List.of()).perModule())
        .anySatisfy(
            module ->
                assertThat(module.diagnostics())
                    .anyMatch(
                        d -> d.diagnostic().getMessage().getLeft().contains("cannot find symbol")));

    // A deleted type: nothing left to diagnose in its file, but its callers break.
    Files.delete(api);
    engine.searchSymbols("Api", SymbolScope.ALL, 10);

    assertThat(engine.verifyChange(List.of()).perModule())
        .singleElement()
        .satisfies(module -> assertThat(module.module()).isEqualTo("app"));
  }

  // The first check writes the change into the mirror; a later one, in this session or the next,
  // must still see it as unbuilt, or it would call a broken tree clean.
  @Test
  void verifyChange_breakLeftInPlace_reportedByEveryCheckAndSessionUntilReverted()
      throws Exception {
    final Path api = apiReactor(List.of("app"), List.of());
    engine = new LatheEngine(tmp);
    TestCompiler.writeAt(api, API_SOURCE.replace("a()", "b()"), 10_000L);
    final Predicate<LatheVerifyChange> breaksApp =
        verify ->
            verify.perModule().stream()
                .anyMatch(
                    module ->
                        module.module().equals("app")
                            && module.diagnostics().stream()
                                .anyMatch(
                                    d ->
                                        d.diagnostic()
                                            .getMessage()
                                            .getLeft()
                                            .contains("cannot find symbol")));

    assertThat(engine.verifyChange(List.of())).matches(breaksApp, "first check");
    assertThat(engine.verifyChange(List.of())).matches(breaksApp, "second check");
    engine.close();
    engine = new LatheEngine(tmp);
    assertThat(engine.verifyChange(List.of())).matches(breaksApp, "a new session");

    TestCompiler.writeAt(api, API_SOURCE, 20_000L);
    assertThat(engine.verifyChange(List.of()).perModule()).as("reverted").isEmpty();

    // A deleted type the build compiled: its callers stay broken, check after check.
    Files.delete(api);
    final Predicate<LatheVerifyChange> appBroken =
        verify -> verify.perModule().stream().anyMatch(module -> module.module().equals("app"));
    assertThat(engine.verifyChange(List.of())).matches(appBroken, "deleted: first check");
    assertThat(engine.verifyChange(List.of())).matches(appBroken, "deleted: second check");
    TestCompiler.writeAt(api, API_SOURCE, 30_000L);
    assertThat(engine.verifyChange(List.of()).perModule()).as("restored").isEmpty();
  }

  @Test
  void verifyChange_noExternalChange_reportsNoDiagnostics() throws Exception {
    final GreetFixture fx = greetFixture();
    stampModule(fx.callee(), fx.caller());
    engine = new LatheEngine(tmp);

    final LatheVerifyChange verify = engine.verifyChange(List.of());

    assertThat(verify.deferral()).isNull();
    assertThat(verify.perModule()).isEmpty();
    assertThat(verify.suggestedSync()).isNull();
  }

  @Test
  void verifyChange_changeFullyReverted_reportsNothingAndSuggestsNoSync() throws Exception {
    final Path api = apiReactor(List.of("app"), List.of());
    engine = new LatheEngine(tmp);
    TestCompiler.writeAt(api, API_SOURCE.replace("a()", "b()"), 10_000L);
    final Path extra =
        TestCompiler.writeAt(
            api.resolveSibling("Extra.java"),
            "package com.example.api;\npublic class Extra {}\n",
            10_000L);
    assertThat(engine.verifyChange(List.of()).suggestedSync()).isNotNull();

    // The whole change undone: Api back to what the build compiled, and a type the build never saw
    // deleted again.
    TestCompiler.writeAt(api, API_SOURCE, 20_000L);
    Files.delete(extra);
    final LatheVerifyChange verify = engine.verifyChange(List.of());

    assertThat(verify.perModule()).isEmpty();
    assertThat(verify.suggestedSync())
        .as("back to what the build compiled: nothing to verify or sync")
        .isNull();

    // A branch switch and back: a built file deleted (its callers break), then restored as it was.
    Files.delete(api);
    assertThat(engine.verifyChange(List.of()).perModule())
        .singleElement()
        .satisfies(module -> assertThat(module.module()).isEqualTo("app"));
    TestCompiler.writeAt(api, API_SOURCE, 30_000L);
    assertThat(engine.verifyChange(List.of()).suggestedSync())
        .as("restored as the build compiled it")
        .isNull();
  }

  @Test
  void modules_reactor_listsEachModuleWithItsUpstreamAndItsTestCapture() throws Exception {
    apiReactor(List.of("app1"), List.of("tool1"));
    Files.writeString(
        tmp.resolve(LatheLayout.LATHE_DIR).resolve("app1").resolve(LatheLayout.TEST_LAUNCH_FILE),
        "{}");
    engine = new LatheEngine(tmp);

    assertThat(engine.modules())
        .extracting(
            LatheModuleSummary::module,
            LatheModuleSummary::dependsOn,
            LatheModuleSummary::testsCaptured)
        .containsExactly(
            tuple("api", List.of(), false),
            tuple("app1", List.of("api"), true),
            tuple("tool1", List.of(), false));
  }

  // A dependent that never names the changed type cannot break, so it is not built.
  @Test
  void verifyChange_twoOfSixModulesUseTheChange_suggestsTheScopedSyncOfThoseDependents()
      throws Exception {
    final Path api = apiReactor(List.of("app1", "app2"), List.of("tool1", "tool2"));
    TestCompiler.writeMirroredModule(
        tmp,
        "idle",
        Map.of("com/example/idle/Idle.java", "package com.example.idle;\nclass Idle {}\n"),
        List.of("api"),
        1_000L);
    engine = new LatheEngine(tmp);

    TestCompiler.writeAt(api, API_SOURCE.replace("a()", "b()"), 10_000L);

    final LatheVerifyChange verify = engine.verifyChange(List.of());
    assertThat(verify.affectedModules()).containsExactly("app1", "app2");
    assertThat(verify.suggestedSync())
        .as("the dependents, not the changed module: -am builds api along with them")
        .isEqualTo(LatheLayout.scopedSyncCommand(List.of("app1", "app2")));

    TestCompiler.writeAt(
        api.resolveSibling(LatheLayout.PACKAGE_INFO_JAVA), "package com.example.api;\n", 20_000L);
    assertThat(engine.verifyChange(List.of()).affectedModules())
        .as("a package declaration no file names reaches every dependent")
        .containsExactly("app1", "app2", "idle");
  }

  // Lombok reads lombok.config when it compiles: an edit to it changes the classes below it, though
  // every source is still what the build compiled.
  @Test
  @EnabledForJreRange(maxVersion = LombokFixtures.MAX_JDK)
  void verifyChange_lombokConfigEdited_checksTheSourcesBelowIt() throws Exception {
    final Path root = tmp.resolve("module/src/main/java");
    final Path config =
        TestCompiler.writeAt(
            tmp.resolve("module").resolve(LatheLayout.LOMBOK_CONFIG_FILE),
            "lombok.accessors.prefix += f\n",
            1_000L);
    final Path person =
        TestCompiler.writeAt(
            root.resolve("com/example/Person.java"),
            "package com.example;\n@lombok.Getter class Person { private int fCount; }\n",
            1_000L);
    final Path use =
        TestCompiler.writeAt(
            root.resolve("com/example/Use.java"),
            "package com.example;\nclass Use { int u(final Person p) { return p.getCount(); } }\n",
            1_000L);
    TestCompiler.writeModuleParams(
        tmp,
        "module",
        List.of(root),
        null,
        List.of(LombokFixtures.jar()),
        List.of(LombokFixtures.jar()),
        List.of());
    // The first session compiles the mirror; the build then records what it compiled.
    engine = new LatheEngine(tmp);
    engine.verifyChange(List.of());
    engine.close();
    stampModule(person, use);
    engine = new LatheEngine(tmp);
    assertThat(engine.verifyChange(List.of(person, use)).perModule()).isEmpty();

    Files.writeString(config, "");

    assertThat(engine.verifyChange(List.of(person, use)).perModule())
        .singleElement()
        .satisfies(
            module ->
                assertThat(module.diagnostics())
                    .extracting(located -> located.diagnostic().getMessage().getLeft())
                    .anySatisfy(message -> assertThat(message).contains("getCount()")));
  }

  @Test
  void verifyChange_moreThanHalfTheReactorDepends_suggestsTheFullSync() throws Exception {
    final Path api = apiReactor(List.of("app1", "app2"), List.of());
    engine = new LatheEngine(tmp);

    TestCompiler.writeAt(api, API_SOURCE.replace("a()", "b()"), 10_000L);

    assertThat(engine.verifyChange(List.of()).suggestedSync())
        .isEqualTo(LatheLayout.SYNC_SCRIPT_COMMAND);
  }

  // An api module, modules that call it, and modules that do not, all compiled into the mirror.
  private Path apiReactor(final List<String> dependents, final List<String> unrelated)
      throws Exception {
    final Path api =
        TestCompiler.writeMirroredModule(
                tmp, "api", Map.of("com/example/api/Api.java", API_SOURCE), List.of(), 1_000L)
            .get(0);
    for (final String module : dependents) {
      TestCompiler.writeMirroredModule(
          tmp,
          module,
          Map.of(
              "com/example/%s/Use.java".formatted(module),
              "package com.example.%s;\nclass Use { int u() { return new com.example.api.Api().a(); } }\n"
                  .formatted(module)),
          List.of("api"),
          1_000L);
    }

    for (final String module : unrelated) {
      TestCompiler.writeMirroredModule(
          tmp,
          module,
          Map.of(
              "com/example/%s/Other.java".formatted(module),
              "package com.example.%s;\nclass Other {}\n".formatted(module)),
          List.of(),
          1_000L);
    }

    return api;
  }
}
