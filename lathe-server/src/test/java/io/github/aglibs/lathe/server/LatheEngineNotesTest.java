package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.testing.TestJavac;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.server.engine.LatheNotes;
import io.github.aglibs.lathe.server.engine.LatheVerifyChange;
import io.github.aglibs.lathe.server.engine.UnreadSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// The notes a tool's answer ends with: not compiled, not checked, sync required.
class LatheEngineNotesTest extends LatheEngineTestBase {

  // WS-46: an agent asking about a file of a module built for a Java the server's JDK does not know
  // is told it is not checked, why, and how to fix it; any answer about uses names the module as
  // not searched.
  @Test
  void notes_fileOfAModuleBuiltForANewerJava_notCheckedWithTheReasonAndTheFix() throws Exception {
    final Path newer =
        TestCompiler.writeMirroredModule(
                tmp,
                "newer",
                Map.of("com/example/A.java", "package com.example; class A {}"),
                List.of(),
                1_000L)
            .get(0);
    TestCompiler.writeModuleParams(
        tmp,
        "newer",
        List.of(tmp.resolve("newer/src/main/java")),
        null,
        List.of(),
        List.of(),
        List.of("--release", "99"));
    engine = new LatheEngine(tmp);

    final LatheNotes notes = engine.notes(List.of(newer));

    assertThat(notes.unreadSources())
        .containsExactly(new UnreadSources("Java 99", List.of("newer")));
    assertThat(notes.notChecked())
        .singleElement()
        .satisfies(
            reason ->
                assertThat(reason)
                    .startsWith("%s: ".formatted(Path.of("newer/src/main/java/com/example/A.java")))
                    .contains("this file's module newer is built for Java 99")
                    .contains("LATHE_JAVA_HOME"));
  }

  private static final String BUILD_FILES_NOTE =
      "build files changed — run `%s`".formatted(LatheLayout.SYNC_SCRIPT_COMMAND);

  // A module with Kotlin or Groovy sources, which Lathe does not read, is named by its workspace
  // path under each language for an answer about uses, Kotlin first; the next test shows a
  // workspace without any names none.
  @Test
  void notes_moduleWithKotlinAndGroovySources_namesItUnderEach() throws Exception {
    final Path source =
        TestCompiler.writeMirroredModule(
                tmp,
                "module",
                Map.of("com/example/Sample.java", "package com.example; class Sample {}"),
                List.of(),
                1_000L)
            .get(0);
    Files.createDirectories(tmp.resolve("module/src/test/kotlin"));
    Files.createDirectories(tmp.resolve("module/src/test/groovy"));
    engine = new LatheEngine(tmp);

    assertThat(engine.notes(List.of(source)).unreadSources())
        .containsExactly(
            new UnreadSources("Kotlin", List.of("module")),
            new UnreadSources("Groovy", List.of("module")));
  }

  // Without workspace.json (a sync of part of the build) the server has no module graph, so every
  // answer says a sync of the whole build is needed; once the manifest is there, none does.
  @Test
  void notes_workspaceWithoutTheManifest_syncRequiredNamingIt_untilWritten() throws Exception {
    final Path source =
        TestCompiler.writeModuleSource(
            tmp, "com/example/Sample.java", "package com.example; class Sample { int n = 1; }");
    Files.delete(tmp.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON));
    engine = new LatheEngine(tmp);

    assertThat(engine.diagnostics(source).notes().syncRequired())
        .containsExactly(LatheLayout.MANIFEST_MISSING_REMEDIATION);
    assertThat(engine.diagnostics(source).notes().moduleGraphMissing()).isTrue();

    engine.close();
    TestCompiler.recordBuildFiles(tmp, List.of());
    engine = new LatheEngine(tmp);
    assertThat(engine.diagnostics(source).notes().syncRequired()).isEmpty();
    assertThat(engine.diagnostics(source).notes().moduleGraphMissing()).isFalse();
  }

  @Test
  void notes_agentEditDoesNotCompile_notCompiledWithItsError_noSyncSuggested_untilFixed()
      throws Exception {
    final Path source =
        TestCompiler.writeModuleSource(
            tmp, "com/example/Sample.java", "package com.example; class Sample { int n = 1; }");
    engine = new LatheEngine(tmp);
    // Each answer carries the notes its own request read, after its compile.
    assertThat(engine.diagnostics(source).notes())
        .isEqualTo(new LatheNotes(List.of(), List.of(), false, List.of(), false, List.of()));

    Files.writeString(source, "package com.example; class Sample { Missing m; }");
    final LatheNotes broken = engine.diagnostics(source).notes();
    assertThat(broken.notCompiled())
        .singleElement()
        .asString()
        .startsWith("%s:1: ".formatted(Path.of("module/src/main/java/com/example/Sample.java")))
        .contains("cannot find symbol");
    assertThat(broken.syncRequired()).as("code to fix, not a sync").isEmpty();
    assertThat(engine.notes(List.of(source))).as("a later read agrees").isEqualTo(broken);

    Files.writeString(source, "package com.example; class Sample { int m; }");
    assertThat(engine.outline(source).notes().notCompiled()).isEmpty();
  }

  // The sync that adds a missing dependency also compiles the file that needed it, so the file is
  // not stale and no later write clears its failure; a file the dependency does not fix keeps its
  // note.
  @Test
  void notes_syncAddsTheMissingDependency_notCompiledOnlyWhatStillFails() throws Exception {
    final Path fixed =
        TestCompiler.writeModuleSource(
            tmp, "com/example/Fixed.java", "package com.example; class Fixed {}");
    final Path broken =
        TestCompiler.writeModuleSource(
            tmp, "com/example/Broken.java", "package com.example; class Broken {}");
    engine = new LatheEngine(tmp);
    engine.diagnostics(fixed).value();

    Files.writeString(fixed, "package com.example; class Fixed { com.example.api.Api api; }");
    Files.writeString(broken, "package com.example; class Broken { com.example.api.Gone gone; }");
    assertThat(engine.diagnostics(fixed).notes().notCompiled()).hasSize(2);

    // In a sync's order: the params first, then the workspace.json rewrite that writing the api
    // module makes, which the server reloads on. Written after it, the params would be missed by a
    // reload a scan tick started in between.
    TestCompiler.writeModuleParams(
        tmp,
        "module",
        tmp.resolve("module/src/main/java"),
        null,
        List.of(tmp.resolve("api/target/classes")));
    TestCompiler.writeMirroredModule(
        tmp, "api", Map.of("com/example/api/Api.java", API_SOURCE), List.of(), 1_000L);
    TestJavac.compileToDir(
        tmp.resolve(".lathe/module/classes"), List.of(tmp.resolve(".lathe/api/classes")), fixed);
    stampModule(fixed);

    assertThat(engine.diagnostics(fixed).value()).isEmpty();
    assertThat(engine.notes(List.of()).notCompiled())
        .singleElement()
        .asString()
        .startsWith("%s:1: ".formatted(Path.of("module/src/main/java/com/example/Broken.java")));
  }

  // A build that compiles the failed file without changing the workspace (no reload) stamps it.
  @Test
  void notes_buildCompilesTheFailedFile_notCompiledGoneWithoutAReload() throws Exception {
    final Path libraryClasses = Files.createDirectories(tmp.resolve("lib-classes"));
    final Path fixed =
        TestCompiler.writeModuleSource(
            tmp, "com/example/Fixed.java", "package com.example; class Fixed {}");
    TestCompiler.writeModuleParams(
        tmp, "module", tmp.resolve("module/src/main/java"), null, List.of(libraryClasses));
    engine = new LatheEngine(tmp);
    engine.diagnostics(fixed).value();

    Files.writeString(fixed, "package com.example; class Fixed { com.lib.Lib lib; }");
    assertThat(engine.diagnostics(fixed).notes().notCompiled()).hasSize(1);

    final Path library =
        TestCompiler.writeAt(
            tmp.resolve("lib-src/com/lib/Lib.java"),
            "package com.lib; public class Lib {}",
            1_000L);
    TestJavac.compileToDir(libraryClasses, library);
    TestJavac.compileToDir(tmp.resolve(".lathe/module/classes"), List.of(libraryClasses), fixed);
    stampModule(fixed);

    assertThat(engine.diagnostics(fixed).notes().notCompiled()).isEmpty();
  }

  // A sync cannot add a file outside a synced module's source roots, nor one in a module the
  // build's profiles or JDK leave out, or in one whose Java the build compiles none of (MC-112: a
  // Kotlin module's test-suite), which Lathe cannot tell apart.
  @Test
  void notes_fileNoModuleCompiles_notCheckedWithItsCause() throws Exception {
    TestCompiler.writeMirroredModule(
        tmp,
        "module",
        Map.of("com/example/Sample.java", "package com.example; class Sample {}"),
        List.of(),
        1_000L);
    final Path inBuiltModule =
        TestCompiler.writeAt(
            tmp.resolve("module/src/it/java/com/example/Probe.java"),
            "package com.example; class Probe {}",
            1_000L);
    final Path outsideTheBuild =
        TestCompiler.writeAt(
            tmp.resolve("newmodule/src/main/java/com/example/Fresh.java"),
            "package com.example; class Fresh {}",
            1_000L);
    final Path builtNotSynced =
        TestCompiler.writeMirroredModule(
                tmp,
                "other",
                Map.of("com/example/Other.java", "package com.example; class Other {}"),
                List.of(),
                1_000L)
            .get(0);
    FileUtil.deleteDir(tmp.resolve(LatheLayout.LATHE_DIR).resolve("other"));
    engine = new LatheEngine(tmp);

    final LatheNotes notes = engine.notes(List.of(inBuiltModule, outsideTheBuild, builtNotSynced));

    assertThat(notes.notChecked())
        .containsExactly(
            "%s is in no source root of module module, so a sync does not add it"
                .formatted(Path.of("module/src/it/java/com/example/Probe.java")),
            "%s is outside the modules the last build compiled (a profile may leave its module out, or its module has no Java the build compiles), so Lathe cannot check it"
                .formatted(Path.of("newmodule/src/main/java/com/example/Fresh.java")),
            "%s is in no synced module — run `%s`"
                .formatted(tmp.relativize(builtNotSynced), LatheLayout.SYNC_SCRIPT_COMMAND));
    assertThat(notes.syncRequired()).isEmpty();
  }

  // MC-103: a file the build's annotation processing wrote is not checked where the build put it;
  // the note names Lathe's copy of it, the test tree's for a test file, or says a sync makes one.
  @Test
  void notes_buildsProcessorOutput_namesLathesCopy() throws Exception {
    TestCompiler.writeMirroredModule(
        tmp,
        "module",
        Map.of("com/example/A.java", "package com.example; class A {}"),
        List.of(),
        1_000L);
    final Path mainOutput = tmp.resolve("module/target/generated-sources/annotations");
    final Path testOutput = tmp.resolve("module/target/generated-test-sources/test-annotations");
    TestCompiler.writeModuleParams(tmp, "module", tmp.resolve("module/src/main/java"), mainOutput);
    TestCompiler.writeTestTreeParams(
        tmp, "module", tmp.resolve("module/src/test/java"), testOutput);
    final Path latheModule = tmp.resolve(LatheLayout.LATHE_DIR).resolve("module");
    final Path built = generated(mainOutput, "Gen");
    final Path copy =
        generated(
            latheModule.resolve(LatheLayout.generatedSourcesDir(LatheLayout.CLASSES_DIR)), "Gen");
    final Path builtTest = generated(testOutput, "GenTest");
    final Path testCopy =
        generated(
            latheModule.resolve(LatheLayout.generatedSourcesDir(LatheLayout.TEST_CLASSES_DIR)),
            "GenTest");
    final Path noCopy = generated(mainOutput, "Fresh");
    engine = new LatheEngine(tmp);

    assertThat(engine.notes(List.of(built, builtTest, noCopy)).notChecked())
        .containsExactly(
            "%s is processor output the build writes; Lathe checks its copy %s — pass that path"
                .formatted(tmp.relativize(built), tmp.relativize(copy)),
            "%s is processor output the build writes; Lathe checks its copy %s — pass that path"
                .formatted(tmp.relativize(builtTest), tmp.relativize(testCopy)),
            "%s is processor output the build writes, and Lathe has no copy of it yet — run `%s`"
                .formatted(tmp.relativize(noCopy), LatheLayout.SYNC_SCRIPT_COMMAND));
  }

  private static Path generated(final Path dir, final String name) throws Exception {
    return TestCompiler.writeAt(
        dir.resolve("com/example/%s.java".formatted(name)),
        "package com.example; class %s {}".formatted(name),
        1_000L);
  }

  // A deleted source is a change verify_change checks; a path the build never had is a typo, and
  // must not pass as "no change".
  @Test
  void notes_missingFile_notCheckedOnlyWhenTheBuildNeverCompiledIt() throws Exception {
    final Path deleted =
        TestCompiler.writeMirroredModule(
                tmp,
                "module",
                Map.of("com/example/Sample.java", "package com.example; class Sample {}"),
                List.of(),
                1_000L)
            .get(0);
    Files.delete(deleted);
    final Path mistyped = deleted.resolveSibling("Smaple.java");
    engine = new LatheEngine(tmp);

    assertThat(engine.notes(List.of(deleted, mistyped)).notChecked())
        .containsExactly(
            "%s does not exist, and the last build did not compile it"
                .formatted(Path.of("module/src/main/java/com/example/Smaple.java")));
  }

  @Test
  void notes_latheWrittenByAnotherVersion_syncRequiredWithTheCommand() throws Exception {
    Files.createDirectories(tmp.resolve(LatheLayout.LATHE_DIR));
    Json.write(
        new WorkspaceManifestData(
            "0",
            tmp.toString(),
            null,
            List.of(),
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null),
        tmp.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON));
    engine = new LatheEngine(tmp);

    assertThat(engine.notes(List.of()).syncRequired())
        .singleElement()
        .asString()
        .startsWith(".lathe/ was written by another Lathe version")
        .contains("`%s`".formatted(LatheLayout.SYNC_SCRIPT_COMMAND));
  }

  @Test
  void notes_unreadableManifest_syncRequiredWithTheCommand() throws Exception {
    Files.createDirectories(tmp.resolve(LatheLayout.LATHE_DIR));
    Files.writeString(
        tmp.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON), "not json");
    engine = new LatheEngine(tmp);

    assertThat(engine.notes(List.of()).syncRequired())
        .singleElement()
        .asString()
        .contains("could not be read")
        .contains("`%s`".formatted(LatheLayout.SYNC_SCRIPT_COMMAND));
  }

  @Test
  void notes_buildFileEditedMidSession_syncRequiredUntilItsContentIsBack_editsStillCompile()
      throws Exception {
    final GreetFixture fx = greetFixture();
    stampModule(fx.callee(), fx.caller());
    final Path pom = TestCompiler.writeAt(tmp.resolve("module/pom.xml"), "<project/>", 1_000L);
    TestCompiler.recordBuildFiles(tmp, List.of(pom));
    engine = new LatheEngine(tmp);
    engine.diagnostics(fx.caller()).value();
    assertThat(engine.notes(List.of()).syncRequired()).isEmpty();

    Files.writeString(pom, "<project><!-- edited --></project>");
    engine.diagnostics(fx.caller()).value();
    assertThat(engine.notes(List.of()).syncRequired())
        .as("seen by the query itself, not on the next tick")
        .containsExactly(BUILD_FILES_NOTE);

    writeNewer(fx.callee(), "package com.example; class Callee { void welcome() {} }");
    assertThat(engine.diagnostics(fx.caller()).value())
        .as("the agent's edits are still compiled while the sync is pending")
        .anyMatch(d -> d.message().contains("greet"));
    assertThat(engine.notes(List.of()).syncRequired()).containsExactly(BUILD_FILES_NOTE);
    final LatheVerifyChange deferred = engine.verifyChange(List.of());
    assertThat(deferred.deferral()).isEqualTo(ReconcileOutcome.DeferReason.BUILD_FILES_PENDING);
    assertThat(deferred.suggestedSync())
        .as("a sync is what the check waits for")
        .isEqualTo(LatheLayout.SYNC_SCRIPT_COMMAND);

    Files.writeString(pom, "<project/>");
    engine.diagnostics(fx.caller()).value();
    assertThat(engine.notes(List.of()).syncRequired())
        .as("the POM is back to what the sync saw")
        .isEmpty();
  }

  @Test
  void notes_buildFileChangedBeforeTheSessionStarted_syncRequiredOnlyIfItsContentDiffers()
      throws Exception {
    final GreetFixture fx = greetFixture();
    final Path pom = TestCompiler.writeAt(tmp.resolve("module/pom.xml"), "<project/>", 1_000L);
    TestCompiler.recordBuildFiles(tmp, List.of(pom));
    TestCompiler.writeAt(pom, "<project/>", 99_000_000_000L);
    engine = new LatheEngine(tmp);
    engine.diagnostics(fx.caller()).value();
    assertThat(engine.notes(List.of()).syncRequired()).as("only touched").isEmpty();
    engine.close();

    Files.writeString(pom, "<project><!-- edited --></project>");
    engine = new LatheEngine(tmp);
    engine.diagnostics(fx.caller()).value();

    assertThat(engine.notes(List.of()).syncRequired()).containsExactly(BUILD_FILES_NOTE);
  }
}
