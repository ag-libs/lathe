package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.server.engine.LatheNotes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// The notes a tool's answer ends with: not compiled, not checked, sync required.
class LatheEngineNotesTest extends LatheEngineTestBase {

  private static final String BUILD_FILES_NOTE =
      "build files changed — run `%s`".formatted(LatheLayout.SYNC_SCRIPT_COMMAND);

  @Test
  void notes_agentEditDoesNotCompile_notCompiledWithItsError_noSyncSuggested_untilFixed()
      throws Exception {
    final Path source =
        TestCompiler.writeModuleSource(
            tmp, "com/example/Sample.java", "package com.example; class Sample { int n = 1; }");
    engine = new LatheEngine(tmp);
    // Each answer carries the notes its own request read, after its compile.
    assertThat(engine.diagnostics(source).notes())
        .isEqualTo(new LatheNotes(List.of(), List.of(), List.of(), false));

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

  // A sync cannot add a file outside a synced module's source roots, nor one in a module the
  // build's profiles or JDK leave out.
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
            "%s is outside the modules the last build compiled (a profile may leave its module out), so Lathe cannot check it"
                .formatted(Path.of("newmodule/src/main/java/com/example/Fresh.java")),
            "%s is in no synced module — run `%s`"
                .formatted(tmp.relativize(builtNotSynced), LatheLayout.SYNC_SCRIPT_COMMAND));
    assertThat(notes.syncRequired()).isEmpty();
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
    assertThat(engine.verifyChange(List.of()).deferral())
        .isEqualTo(ReconcileOutcome.DeferReason.BUILD_FILES_PENDING);

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
