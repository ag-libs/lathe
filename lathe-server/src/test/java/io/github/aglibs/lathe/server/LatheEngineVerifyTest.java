package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.aglibs.lathe.analysis.testing.TestJavac;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.LatheEngine.SymbolScope;
import io.github.aglibs.lathe.server.engine.LatheChangeImpact;
import io.github.aglibs.lathe.server.engine.LatheDiagnostic;
import io.github.aglibs.lathe.server.engine.LatheModuleDiagnostics;
import io.github.aglibs.lathe.server.engine.LatheModuleSummary;
import io.github.aglibs.lathe.server.engine.LatheVerifyChange;
import io.github.aglibs.lathe.server.engine.Noted;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;

// verify_change and analyze_change: what a change breaks, and the sync it suggests.
class LatheEngineVerifyTest extends LatheEngineTestBase {
  // The family both ways: an interface method lists what overrides it and, apart, the subinterface
  // that redeclares it, which find_implementations leaves out; the implementation lists the method
  // it overrides.
  @Test
  void analyzeChange_interfaceMethod_reportsOverrideFamilyAndReferences() throws Exception {
    final GreeterFixture greeter = greeterFixture();
    final String ifaceSource = greeter.ifaceSource();
    final Path iface = greeter.iface();
    final Path caller =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Caller.java",
            "package com.example; class Caller { void run(Greeter g) { g.greet(); } }");
    final Path loud =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/LoudGreeter.java",
            "package com.example; interface LoudGreeter extends Greeter { void greet(); }");
    compileIntoMirror(iface, greeter.impl(), caller, loud);
    engine = new LatheEngine(tmp);

    final var pos = positionOf(ifaceSource, "greet");
    final LatheChangeImpact impact =
        engine.analyzeChange(iface, pos.getLine(), pos.getCharacter(), 50).value();
    final String implSource = Files.readString(greeter.impl());
    final var implPos = positionOf(implSource, "greet");
    final LatheChangeImpact ofImpl =
        engine.analyzeChange(greeter.impl(), implPos.getLine(), implPos.getCharacter(), 50).value();

    assertThat(impact.signature()).contains("greet");
    assertThat(impact.method()).isTrue();
    assertThat(impact.overrides()).isEmpty();
    assertThat(impact.overriddenBy())
        .anySatisfy(loc -> assertThat(loc.uri()).endsWith("EnGreeter.java"))
        .noneSatisfy(loc -> assertThat(loc.uri()).endsWith("LoudGreeter.java"));
    assertThat(impact.redeclaredBy())
        .singleElement()
        .satisfies(loc -> assertThat(loc.uri()).endsWith("LoudGreeter.java"));
    assertThat(
            engine
                .findImplementations(iface, pos.getLine(), pos.getCharacter(), 10)
                .value()
                .implementations())
        .noneSatisfy(loc -> assertThat(loc.uri()).endsWith("LoudGreeter.java"));
    assertThat(ofImpl.overrides()).containsExactly("com.example.Greeter#greet()");
    assertThat(impact.productionRefs())
        .as("Caller's one call; the declarations are the family, not uses")
        .isEqualTo(1);
    assertThat(impact.testRefs()).isZero();
    assertThat(impact.affectedModules()).containsExactly("module");
  }

  // The family lists show the first maxResults members; their counts are of all.
  @Test
  void analyzeChange_familyOverTheLimit_listsTheFirstCountsAll() throws Exception {
    final GreeterFixture greeter = greeterFixture();
    final Path second =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/FrGreeter.java",
            "package com.example; class FrGreeter implements Greeter { public void greet() {} }");
    compileIntoMirror(greeter.iface(), greeter.impl(), second);
    engine = new LatheEngine(tmp);
    final var pos = positionOf(greeter.ifaceSource(), "greet");

    final LatheChangeImpact capped =
        engine.analyzeChange(greeter.iface(), pos.getLine(), pos.getCharacter(), 1).value();
    final LatheChangeImpact all =
        engine.analyzeChange(greeter.iface(), pos.getLine(), pos.getCharacter(), 50).value();

    assertThat(capped.overriddenBy()).hasSize(1);
    assertThat(capped.overriddenByCount()).isEqualTo(2);
    assertThat(all.overriddenBy()).hasSize(2);
    assertThat(all.overriddenByCount()).isEqualTo(2);
  }

  // A signature change breaks every implementation: a module that implements the method only with
  // a lambda, which declares no method of its name and never calls it, is affected too, and a test
  // that does is a relevant test.
  @Test
  void analyzeChange_methodImplementedInAModuleThatNeverCallsIt_countsThatModule()
      throws Exception {
    final String ifaceSource =
        "package com.example.api;\npublic interface Greeter {\n  void greet();\n}\n";
    final var api = new LinkedHashMap<String, String>();
    api.put("com/example/api/Greeter.java", ifaceSource);
    api.put(
        "com/example/api/Caller.java",
        "package com.example.api;\nclass Caller {\n  void run(Greeter g) { g.greet(); }\n}\n");
    final Path iface = TestCompiler.writeMirroredModule(tmp, "api", api, List.of(), 1_000L).get(0);
    TestCompiler.writeMirroredModule(
        tmp,
        "impl",
        Map.of(
            "com/example/impl/Wiring.java",
            "package com.example.impl;\nclass Wiring {\n  com.example.api.Greeter greeter = () -> {};\n}\n"),
        List.of("api"),
        1_000L);
    final Path fakeRoot = tmp.resolve("impl/src/integrationTest/java");
    final Path fake =
        TestCompiler.writeAt(
            fakeRoot.resolve("com/example/impl/FakeWiring.java"),
            "package com.example.impl;\nclass FakeWiring {\n  com.example.api.Greeter greeter = () -> {};\n}\n",
            1_000L);
    final Path apiClasses = tmp.resolve(LatheLayout.LATHE_DIR).resolve("api/classes");
    TestJavac.compileToDir(
        tmp.resolve(LatheLayout.LATHE_DIR)
            .resolve("impl")
            .resolve(LatheLayout.sourceSetTree("integrationTest")),
        List.of(apiClasses),
        fake);
    TestCompiler.writeSourceSetParams(
        tmp, "impl", "integrationTest", fakeRoot, List.of(tmp.resolve("api/target/classes")), true);
    engine = new LatheEngine(tmp);

    final var pos = positionOf(ifaceSource, "greet");
    final LatheChangeImpact impact =
        engine.analyzeChange(iface, pos.getLine(), pos.getCharacter(), 50).value();

    assertThat(impact.overriddenBy())
        .anySatisfy(location -> assertThat(location.uri()).endsWith("Wiring.java"));
    assertThat(impact.affectedModules()).containsExactly("api", "impl");
    assertThat(impact.relevantTests())
        .as("a test implementing it breaks too")
        .contains("FakeWiring");
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
                                && d.message().contains("greet")));
  }

  @Test
  void diagnostics_rightAfterExternalEditOfDependency_seesTheEdit() throws Exception {
    final GreetFixture fx = greetFixture();
    stampModule(fx.callee(), fx.caller());
    engine = new LatheEngine(tmp);
    engine.diagnostics(fx.caller()).value(); // the session is warm and idle before the agent's edit

    // An agent renames greet() with its own file tool, then queries the caller at once.
    writeNewer(fx.callee(), "package com.example; class Callee { void welcome() {} }");

    assertThat(engine.diagnostics(fx.caller()).value())
        .anyMatch(d -> d.message().contains("greet"));
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
    compileIntoMirror(old, callee, caller);
    engine = new LatheEngine(tmp);
    assertThat(engine.diagnostics(caller).value())
        .as("the caller has a removal warning and an unused-method hint")
        .extracting(LatheDiagnostic::severity)
        .contains(LatheDiagnostic.Severity.WARNING, LatheDiagnostic.Severity.HINT);

    assertThat(engine.verifyChange(List.of(callee)).perModule())
        .singleElement()
        .satisfies(
            module ->
                assertThat(module.diagnostics())
                    .extracting(d -> d.severity())
                    .containsExactly(LatheDiagnostic.Severity.WARNING));

    writeNewer(callee, "package com.example; class Callee { void welcome() { Old.old(); } }");
    assertThat(engine.verifyChange(List.of(callee)).perModule())
        .singleElement()
        .satisfies(
            module -> {
              assertThat(module.diagnostics())
                  .extracting(d -> d.severity())
                  .containsExactly(
                      LatheDiagnostic.Severity.ERROR, LatheDiagnostic.Severity.WARNING);
              assertThat(module.diagnostics().get(0).message()).contains("greet");
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
    compileIntoMirror(callee, sub);
    stampModule(callee, sub);
    engine = new LatheEngine(tmp);

    renameAt(callee, source, "greet", "welcome");
    // Callee compiled and was stamped by the rename's write, so nothing on disk reads as stale.
    final LatheVerifyChange verify = engine.verifyChange(List.of());

    assertThat(verify.perModule())
        .anySatisfy(
            module ->
                assertThat(module.diagnostics()).anyMatch(d -> d.message().contains("welcome")));
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
                  .anyMatch(d -> d.message().contains("cannot find symbol"));
            });
    assertThat(verify.affectedModules()).containsExactly("app");
    assertThat(verify.suggestedSync())
        .as("builds the dependent, and with -am the changed module too")
        .isEqualTo(LatheLayout.scopedSyncCommand(List.of("app")));

    // Another such edit, but compiled by an unrelated query before verify_change is asked.
    TestCompiler.writeAt(api, API_SOURCE.replace("a()", "c()"), 20_000L);
    engine.diagnostics(api).value();

    assertThat(engine.verifyChange(List.of()).perModule())
        .anySatisfy(
            module ->
                assertThat(module.diagnostics())
                    .anyMatch(d -> d.message().contains("cannot find symbol")));

    // A deleted type: nothing left to diagnose in its file, but its callers break.
    Files.delete(api);
    engine.searchSymbols("Api", SymbolScope.ALL, 10).value();

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
                                .anyMatch(d -> d.message().contains("cannot find symbol")));

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

    assertThat(engine.modules().value())
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
                    .extracting(located -> located.message())
                    .anySatisfy(message -> assertThat(message).contains("getCount()")));
  }

  // A module descriptor is named by no source, yet decides what every source of its module reads.
  @Test
  void verifyChange_moduleInfoDropsARequires_reportsTheModuleSourceThatNeedsIt() throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put("module-info.java", "module app { requires java.sql; }\n");
    sources.put(
        "com/example/Db.java",
        "package com.example;\nclass Db { java.sql.Connection connection; }\n");
    sources.put("com/example/Plain.java", "package com.example;\nclass Plain {}\n");
    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    final Path descriptor = files.get(0);
    engine = new LatheEngine(tmp);

    writeNewer(descriptor, "module app {}\n");

    assertThat(engine.verifyChange(List.of()).perModule())
        .singleElement()
        .satisfies(
            module ->
                assertThat(module.diagnostics())
                    .allMatch(d -> d.path().equals(FileUtil.slashed(tmp.relativize(files.get(1)))))
                    .anyMatch(d -> d.message().contains("java.sql")));

    writeNewer(descriptor, "module app {\n  requires java.sql;\n}\n");

    assertThat(engine.verifyChange(List.of()).perModule()).isEmpty();
  }

  // A change only the build's own compile fails, here a processor's warning under -Werror, which
  // Lathe's analysis compile (no processors) passes: the answer reports the write's failure, names
  // the warning, and says the callers saw the change's old classes. A clean file written with it
  // fails the same compile but for no cause of its own, so it stays clean. Without -Werror it all
  // compiles.
  @Test
  void verifyChange_changeFailsOnlyTheBuildsCompile_reportsTheWriteAndItsCallersOldClasses()
      throws Exception {
    final Path callee = moduleBuiltWithTheNoteProcessor(List.of("-Werror"));
    final Path quiet = callee.resolveSibling("QuietPeer.java");
    engine = new LatheEngine(tmp);

    writeNewer(
        callee, "package com.example;\nclass Callee {\n  void greet() {}\n  void wave() {}\n}\n");
    writeNewer(quiet, "package com.example;\nclass QuietPeer {\n  void hush() {}\n}\n");
    final LatheVerifyChange verify = engine.verifyChange(List.of());

    final String path = FileUtil.slashed(tmp.relativize(callee));
    assertThat(verify.perModule())
        .singleElement()
        .satisfies(
            module ->
                assertThat(module.diagnostics())
                    .allMatch(d -> d.path().equals(path))
                    .anyMatch(
                        d ->
                            d.severity() == LatheDiagnostic.Severity.ERROR
                                && d.message().equals("noted by processor (-Werror)"))
                    .noneMatch(d -> d.message().contains("-Werror specified")));
    assertThat(verify.failedWrites()).containsExactly(path);
    assertThat(engine.diagnostics(callee).notes().notCompiled())
        .contains("%s:2: noted by processor (-Werror)".formatted(tmp.relativize(callee)));
  }

  // What fails only the build's compile (its processors and javac plugins, which the analysis does
  // not run) is in the file's diagnostics too, not only in the notes beside them: the answer never
  // says "no errors" while its notes name one.
  @Test
  void diagnostics_fileFailsOnlyTheBuildsCompile_listsItsErrorBesideTheNote() throws Exception {
    final Path callee = moduleBuiltWithTheNoteProcessor(List.of("-Werror"));
    engine = new LatheEngine(tmp);
    writeNewer(
        callee, "package com.example;\nclass Callee {\n  void greet() {}\n  void wave() {}\n}\n");

    final Noted<List<LatheDiagnostic>> failing = engine.diagnostics(callee);

    assertThat(failing.notes().notCompiled()).isNotEmpty();
    assertThat(failing.value())
        .anyMatch(
            d ->
                d.severity() == LatheDiagnostic.Severity.ERROR
                    && d.message().equals("noted by processor (-Werror)"));
  }

  // A warning only the build's compile reports, which fails nothing, is in the file's diagnostics
  // too; a file the processor leaves alone gets none of it.
  @Test
  void diagnostics_buildOnlyWarning_listedForItsFileOnly() throws Exception {
    final Path callee = moduleBuiltWithTheNoteProcessor(List.of());
    final Path quiet = callee.resolveSibling("QuietPeer.java");
    engine = new LatheEngine(tmp);
    writeNewer(
        callee, "package com.example;\nclass Callee {\n  void greet() {}\n  void wave() {}\n}\n");
    writeNewer(quiet, "package com.example;\nclass QuietPeer {\n  void hush() {}\n}\n");

    assertThat(engine.diagnostics(callee).value())
        .anyMatch(
            d ->
                d.severity() == LatheDiagnostic.Severity.WARNING
                    && d.message().equals("noted by processor"));
    assertThat(engine.diagnostics(quiet).value()).isEmpty();
  }

  // The classes of a change the build's compile fails stay those of its last good compile: its
  // callers are checked against its edited source, so one the change breaks is reported now, not
  // only once the warning is fixed.
  @Test
  void verifyChange_changeFailsTheBuildsCompileAndBreaksACaller_reportsTheCaller()
      throws Exception {
    final Path callee = moduleBuiltWithTheNoteProcessor(List.of("-Werror"));
    final Path caller = callee.resolveSibling("Caller.java");
    engine = new LatheEngine(tmp);

    writeNewer(callee, "package com.example;\nclass Callee {\n  void wave() {}\n}\n");
    final LatheVerifyChange verify = engine.verifyChange(List.of());

    final String callerPath = FileUtil.slashed(tmp.relativize(caller));
    assertThat(verify.failedWrites()).containsExactly(FileUtil.slashed(tmp.relativize(callee)));
    assertThat(verify.perModule())
        .singleElement()
        .satisfies(
            module ->
                assertThat(module.diagnostics())
                    .anyMatch(
                        d ->
                            d.path().equals(callerPath)
                                && d.severity() == LatheDiagnostic.Severity.ERROR
                                && d.message().contains("greet")));
  }

  // A second changed file the failing change breaks is checked with its edited source too, not
  // against its stale classes.
  @Test
  void verifyChange_anotherChangedFileBrokenByAFailingChange_reportsIt() throws Exception {
    final Path callee = moduleBuiltWithTheNoteProcessor(List.of("-Werror"));
    final Path caller = callee.resolveSibling("Caller.java");
    engine = new LatheEngine(tmp);

    writeNewer(callee, "package com.example;\nclass Callee {\n  void wave() {}\n}\n");
    writeNewer(
        caller,
        "package com.example;\nclass Caller {\n  // edited\n  void run(Callee c) { c.greet(); }\n}\n");
    final LatheVerifyChange verify = engine.verifyChange(List.of());

    final String callerPath = FileUtil.slashed(tmp.relativize(caller));
    assertThat(verify.perModule())
        .flatMap(LatheModuleDiagnostics::diagnostics)
        .anyMatch(d -> d.path().equals(callerPath) && d.message().contains("greet"));
  }

  // The failing change's source goes only where it resolves: in a module that cannot see it, it
  // would fail and make javac skip the flow analysis behind "unreported exception".
  @Test
  void verifyChange_failingChangeBesideAnUnrelatedModule_keepsThatModulesFlowErrors()
      throws Exception {
    final Path callee = moduleBuiltWithTheNoteProcessor(List.of("-Werror"));
    final var other = new LinkedHashMap<String, String>();
    other.put("com/other/Y.java", "package com.other;\nclass Y {\n  static void run() {}\n}\n");
    other.put("com/other/Z.java", "package com.other;\nclass Z {\n  void z() { Y.run(); }\n}\n");
    final Path y = TestCompiler.writeMirroredModule(tmp, "other", other, List.of(), 1_000L).get(0);
    final Path z = y.resolveSibling("Z.java");
    engine = new LatheEngine(tmp);

    writeNewer(
        callee, "package com.example;\nclass Callee {\n  void greet() { new Caller(); }\n}\n");
    writeNewer(
        y, "package com.other;\nclass Y {\n  static void run() throws java.io.IOException {}\n}\n");
    final LatheVerifyChange verify = engine.verifyChange(List.of());

    final String zPath = FileUtil.slashed(tmp.relativize(z));
    assertThat(verify.perModule())
        .flatMap(LatheModuleDiagnostics::diagnostics)
        .anyMatch(d -> d.path().equals(zPath) && d.message().contains("unreported exception"));
  }

  @Test
  void verifyChange_buildOnlyWarningWithoutWerror_reportsNoFailedWrite() throws Exception {
    final Path callee = moduleBuiltWithTheNoteProcessor(List.of());
    engine = new LatheEngine(tmp);

    writeNewer(
        callee, "package com.example;\nclass Callee {\n  void greet() {}\n  void wave() {}\n}\n");
    final LatheVerifyChange verify = engine.verifyChange(List.of());

    assertThat(verify.perModule()).isEmpty();
    assertThat(verify.failedWrites()).isEmpty();
  }

  // Callee, a QuietPeer the processor leaves alone, and Callee's Caller, built by a build that runs
  // NoteProcessor with the given extra arguments.
  private Path moduleBuiltWithTheNoteProcessor(final List<String> extraArgs) throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put(
        "com/example/Callee.java", "package com.example;\nclass Callee {\n  void greet() {}\n}\n");
    sources.put("com/example/QuietPeer.java", "package com.example;\nclass QuietPeer {}\n");
    sources.put(
        "com/example/Caller.java",
        "package com.example;\nclass Caller {\n  void run(Callee c) { c.greet(); }\n}\n");
    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    TestCompiler.writeModuleParams(
        tmp,
        "module",
        List.of(tmp.resolve("module/src/main/java")),
        null,
        List.of(),
        List.of(TestCompiler.noteProcessor(tmp)),
        Stream.concat(Stream.of("-processor", "proc.NoteProcessor"), extraArgs.stream()).toList());
    return files.get(0);
  }

  // A caller that reaches the changed type through a chain of calls never names it: it is found by
  // its compiled class, which refers to the type whatever the source spells. A module that uses the
  // chain's start but never the type stays out of the check and the sync.
  @Test
  void verifyChange_callerReachesTheChangedTypeOnlyThroughAChain_reportsIt() throws Exception {
    final var api = new LinkedHashMap<String, String>();
    api.put(
        "com/example/api/Span.java",
        "package com.example.api;\npublic class Span {\n  public int seconds() { return 1; }\n}\n");
    api.put(
        "com/example/api/Holder.java",
        "package com.example.api;\npublic class Holder {\n  public Span span() { return new Span(); }\n}\n");
    final Path span = TestCompiler.writeMirroredModule(tmp, "api", api, List.of(), 1_000L).get(0);
    final Path use =
        TestCompiler.writeMirroredModule(
                tmp,
                "user",
                Map.of(
                    "com/example/user/Use.java",
                    "package com.example.user;\nclass Use {\n"
                        + "  int u(com.example.api.Holder h) { return h.span().seconds(); }\n}\n"),
                List.of("api"),
                1_000L)
            .get(0);
    TestCompiler.writeMirroredModule(
        tmp,
        "bystander",
        Map.of(
            "com/example/by/By.java",
            "package com.example.by;\nclass By {\n  Object b() { return new com.example.api.Holder(); }\n}\n"),
        List.of("api"),
        1_000L);
    engine = new LatheEngine(tmp);

    writeNewer(
        span,
        "package com.example.api;\npublic class Span {\n  public int secs() { return 1; }\n}\n");
    final LatheVerifyChange verify = engine.verifyChange(List.of());

    final String usePath = FileUtil.slashed(tmp.relativize(use));
    assertThat(verify.perModule())
        .flatMap(LatheModuleDiagnostics::diagnostics)
        .anyMatch(d -> d.path().equals(usePath) && d.message().contains("seconds"));
    assertThat(verify.affectedModules()).containsExactly("user");
  }

  // A deleted type has no class left in the mirror: its chained callers are found by its name.
  @Test
  void verifyChange_typeReachedOnlyThroughAChainDeleted_reportsTheCaller() throws Exception {
    final var api = new LinkedHashMap<String, String>();
    api.put(
        "com/example/api/Span.java",
        "package com.example.api;\npublic class Span {\n  public int seconds() { return 1; }\n}\n");
    api.put(
        "com/example/api/Holder.java",
        "package com.example.api;\npublic class Holder {\n  public Span span() { return new Span(); }\n}\n");
    final Path span = TestCompiler.writeMirroredModule(tmp, "api", api, List.of(), 1_000L).get(0);
    final Path use =
        TestCompiler.writeMirroredModule(
                tmp,
                "user",
                Map.of(
                    "com/example/user/Use.java",
                    "package com.example.user;\nclass Use {\n"
                        + "  int u(com.example.api.Holder h) { return h.span().seconds(); }\n}\n"),
                List.of("api"),
                1_000L)
            .get(0);
    engine = new LatheEngine(tmp);

    Files.delete(span);
    final LatheVerifyChange verify = engine.verifyChange(List.of());

    final String usePath = FileUtil.slashed(tmp.relativize(use));
    assertThat(verify.perModule())
        .flatMap(LatheModuleDiagnostics::diagnostics)
        .anyMatch(d -> d.path().equals(usePath));
  }

  // A Kotlin caller, as kotlinc writes jdbi's kotlin-sqlobject tests into the module's classes, is
  // found by its compiled class, whose SourceFile names a .kt file. javac cannot read it, so it is
  // named as not checked instead of failing the check; its module is still one to build, and the
  // Java callers are checked.
  @Test
  void verifyChange_callerCompiledFromKotlin_namesItAsNotCheckedAndChecksTheRest()
      throws Exception {
    final Path span =
        TestCompiler.writeMirroredModule(tmp, "api", spanApi(), List.of(), 1_000L).get(0);
    final var user = new LinkedHashMap<String, String>();
    user.put(
        "com/example/user/Use.java",
        """
        package com.example.user;
        class Use {
          int u(com.example.api.Holder h) { return h.span().seconds(); }
        }
        """);
    user.put(
        "com/example/user/KtUse.java",
        """
        package com.example.user;
        class KtUse {
          int k(com.example.api.Holder h) { return h.span().seconds(); }
        }
        """);
    final List<Path> users =
        TestCompiler.writeMirroredModule(tmp, "user", user, List.of("api"), 1_000L);
    final Path kotlin = compiledFromKotlin("user", users.get(1));
    engine = new LatheEngine(tmp);

    writeNewer(span, SPAN_RENAMED);
    final LatheVerifyChange verify = engine.verifyChange(List.of());

    final String usePath = FileUtil.slashed(tmp.relativize(users.get(0)));
    assertThat(verify.perModule())
        .flatMap(LatheModuleDiagnostics::diagnostics)
        .anyMatch(d -> d.path().equals(usePath) && d.message().contains("seconds"))
        .noneMatch(d -> d.path().endsWith(".kt"));
    assertThat(verify.uncheckedCallers()).containsExactly(FileUtil.slashed(tmp.relativize(kotlin)));
    assertThat(verify.affectedModules()).contains("user");
  }

  // A Kotlin caller in the changed module itself is never a module to build, so only the unchecked
  // list keeps it from passing as compiling.
  @Test
  void verifyChange_kotlinCallerInTheChangedModule_namesItAsNotChecked() throws Exception {
    final var api = new LinkedHashMap<>(spanApi());
    api.put(
        "com/example/api/KtSame.java",
        """
        package com.example.api;
        class KtSame {
          int k(Holder h) { return h.span().seconds(); }
        }
        """);
    final List<Path> sources = TestCompiler.writeMirroredModule(tmp, "api", api, List.of(), 1_000L);
    final Path kotlin = compiledFromKotlin("api", sources.get(2));
    engine = new LatheEngine(tmp);

    writeNewer(sources.get(0), SPAN_RENAMED);
    final LatheVerifyChange verify = engine.verifyChange(List.of());

    assertThat(verify.uncheckedCallers()).containsExactly(FileUtil.slashed(tmp.relativize(kotlin)));
    assertThat(verify.perModule())
        .flatMap(LatheModuleDiagnostics::diagnostics)
        .noneMatch(d -> d.path().endsWith(".kt"));
  }

  private static final String SPAN_RENAMED =
      """
      package com.example.api;
      public class Span {
        public int secs() { return 1; }
      }
      """;

  // Span first, reached by callers only through Holder's chain.
  private static Map<String, String> spanApi() {
    final var api = new LinkedHashMap<String, String>();
    api.put(
        "com/example/api/Span.java",
        """
        package com.example.api;
        public class Span {
          public int seconds() { return 1; }
        }
        """);
    api.put(
        "com/example/api/Holder.java",
        """
        package com.example.api;
        public class Holder {
          public Span span() { return new Span(); }
        }
        """);
    return api;
  }

  // Makes a compiled caller look as kotlinc leaves one: a .kt source beside it, which its class's
  // SourceFile names. The .java stays, so the build's stamps still match the sources.
  private Path compiledFromKotlin(final String module, final Path javaSource) throws Exception {
    final Path sourceRoot = tmp.resolve(module).resolve("src/main/java");
    final String name = javaSource.getFileName().toString();
    final String kotlinName = name.replace(".java", ".kt");
    final Path kotlin = Files.writeString(javaSource.resolveSibling(kotlinName), "// kotlinc\n");
    final String classRel =
        FileUtil.slashed(sourceRoot.relativize(javaSource)).replace(".java", ".class");
    renameSourceFile(
        tmp.resolve(LatheLayout.LATHE_DIR).resolve(module).resolve("classes").resolve(classRel),
        name,
        kotlinName);
    return kotlin;
  }

  // Rewrites a class's SourceFile name, as kotlinc writes one for a .kt file. The name is a
  // length-prefixed constant-pool entry that no offset points at, so it may change length.
  private static void renameSourceFile(final Path classFile, final String from, final String to)
      throws Exception {
    final byte[] bytes = Files.readAllBytes(classFile);
    final byte[] entry = utf8Constant(from);
    int at = -1;
    for (int i = 0; i + entry.length <= bytes.length && at < 0; i++) {
      if (Arrays.equals(bytes, i, i + entry.length, entry, 0, entry.length)) {
        at = i;
      }
    }

    assertThat(at).as("SourceFile constant %s in %s", from, classFile).isNotNegative();
    final var out = new ByteArrayOutputStream();
    out.write(bytes, 0, at);
    out.write(utf8Constant(to));
    out.write(bytes, at + entry.length, bytes.length - at - entry.length);
    Files.write(classFile, out.toByteArray());
  }

  private static byte[] utf8Constant(final String value) {
    final byte[] text = value.getBytes(StandardCharsets.UTF_8);
    final var constant = new byte[text.length + 3];
    constant[0] = 1;
    constant[1] = (byte) (text.length >> 8);
    constant[2] = (byte) text.length;
    System.arraycopy(text, 0, constant, 3, text.length);
    return constant;
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
