package io.github.aglibs.lathe.server.engine;

import static io.github.aglibs.lathe.server.analysis.SourceLocator.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.BuildFileData;
import io.github.aglibs.lathe.core.schema.DependencyData;
import io.github.aglibs.lathe.core.schema.SourceStatus;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.engine.LatheEngine.CallDirection;
import io.github.aglibs.lathe.server.run.LaunchOutcome;
import io.github.aglibs.lathe.server.run.TestResult;
import io.github.aglibs.lathe.server.run.TranscriptLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheEngineTest {

  private static final String BUILD_FILES_NOTE =
      "build files changed — run `%s`".formatted(LatheLayout.SYNC_SCRIPT_COMMAND);
  private static final String API_SOURCE =
      "package com.example.api;\npublic class Api { public int a() { return 1; } }\n";

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
    final LatheLocation target = targets.get(0);
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
  void findImplementations_interface_findsImplementingClass() throws Exception {
    final String ifaceSource =
        """
        package com.example;
        interface Greeter {
          void greet();
        }
        """;
    final Path iface = TestCompiler.writeModuleSource(tmp, "com/example/Greeter.java", ifaceSource);
    final String implSource =
        "package com.example; class EnGreeter implements Greeter { public void greet() {} }";
    final Path impl = TestCompiler.writeModuleSource(tmp, "com/example/EnGreeter.java", implSource);
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), iface, impl);
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(ifaceSource, ifaceSource.indexOf("Greeter"));
    final LatheImplementations impls =
        engine.findImplementations(iface, pos.getLine(), pos.getCharacter(), 50);

    assertThat(impls.implementations())
        .anySatisfy(loc -> assertThat(loc.uri()).endsWith("EnGreeter.java"));
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
  void describe_methodInADependencySource_returnsSignatureAndJavadoc() throws Exception {
    final Path sourceDir = tmp.resolve("sources/lib");
    final String source =
        """
        package com.lib;

        public class Lib {
          /** Doubles the given amount. */
          public int twice(int amount) {
            return amount * 2;
          }
        }
        """;
    final Path lib = sourceDir.resolve("com/lib/Lib.java");
    Files.createDirectories(lib.getParent());
    Files.writeString(lib, source);
    Files.createDirectories(tmp.resolve(LatheLayout.LATHE_DIR));
    Json.write(
        new WorkspaceManifestData(
            LatheLayout.SCHEMA_VERSION,
            tmp.toString(),
            null,
            List.of(),
            null,
            List.of(
                new DependencyData(
                    "com.lib:lib:1",
                    tmp.resolve("repo/lib.jar").toString(),
                    SourceStatus.PRESENT,
                    sourceDir.toString(),
                    List.of(),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            null),
        tmp.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON));
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(source, source.indexOf("twice"));

    assertThat(engine.describe(lib, pos.getLine(), pos.getCharacter()))
        .contains("twice(int amount)")
        .contains("Doubles the given amount.");
    assertThat(engine.diagnostics(lib))
        .as("a dependency's diagnostics are not the agent's")
        .isEmpty();
  }

  @Test
  void searchSymbolsAndFindImplementations_typeAddedOnDiskMidSession_foundByTheNextQuery()
      throws Exception {
    final String ifaceSource = "package com.example;\ninterface Greeter {\n  void greet();\n}\n";
    final Path iface = TestCompiler.writeModuleSource(tmp, "com/example/Greeter.java", ifaceSource);
    final Path impl =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/EnGreeter.java",
            "package com.example; class EnGreeter implements Greeter { public void greet() {} }");
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), iface, impl);
    stampModule(iface, impl);
    engine = new LatheEngine(tmp);
    assertThat(engine.searchSymbols("FrGreeter", 50)).isEmpty();

    // An agent writes a new implementation with its own file tool, then queries at once.
    TestCompiler.writeModuleSource(
        tmp,
        "com/example/FrGreeter.java",
        "package com.example; class FrGreeter implements Greeter { public void greet() {} }");

    assertThat(engine.searchSymbols("FrGreeter", 50))
        .anySatisfy(symbol -> assertThat(symbol.name()).isEqualTo("FrGreeter"));
    final var pos = offsetToPosition(ifaceSource, ifaceSource.indexOf("Greeter"));
    assertThat(
            engine
                .findImplementations(iface, pos.getLine(), pos.getCharacter(), 50)
                .implementations())
        .anySatisfy(loc -> assertThat(loc.uri()).endsWith("FrGreeter.java"));
  }

  @Test
  void searchSymbols_byName_findsReactorType() throws Exception {
    greetFixture();
    engine = new LatheEngine(tmp);

    final List<LatheSymbol> symbols = engine.searchSymbols("Callee", 50);

    assertThat(symbols).anySatisfy(symbol -> assertThat(symbol.name()).contains("Callee"));
  }

  @Test
  void notes_agentEditDoesNotCompile_notCompiledWithItsError_noSyncSuggested_untilFixed()
      throws Exception {
    final Path source =
        TestCompiler.writeModuleSource(
            tmp, "com/example/Sample.java", "package com.example; class Sample { int n = 1; }");
    engine = new LatheEngine(tmp);
    engine.diagnostics(source);
    assertThat(engine.notes(List.of(source)))
        .isEqualTo(new LatheNotes(List.of(), List.of(), false));

    Files.writeString(source, "package com.example; class Sample { Missing m; }");
    engine.diagnostics(source);
    final LatheNotes broken = engine.notes(List.of(source));
    assertThat(broken.notCompiled())
        .singleElement()
        .asString()
        .startsWith("%s:1: ".formatted(Path.of("module/src/main/java/com/example/Sample.java")))
        .contains("cannot find symbol");
    assertThat(broken.syncRequired()).as("code to fix, not a sync").isEmpty();

    Files.writeString(source, "package com.example; class Sample { int m; }");
    engine.diagnostics(source);
    assertThat(engine.notes(List.of(source)).notCompiled()).isEmpty();
  }

  @Test
  void notes_fileInNoSyncedModule_syncRequiredWithTheCommand() throws Exception {
    TestCompiler.writeModuleSource(
        tmp, "com/example/Sample.java", "package com.example; class Sample {}");
    final Path unsynced =
        TestCompiler.writeAt(
            tmp.resolve("newmodule/src/main/java/com/example/Fresh.java"),
            "package com.example; class Fresh {}",
            1_000L);
    engine = new LatheEngine(tmp);

    assertThat(engine.notes(List.of(unsynced)).syncRequired())
        .containsExactly(
            "%s is in no synced module — run `%s`"
                .formatted(
                    Path.of("newmodule/src/main/java/com/example/Fresh.java"),
                    LatheLayout.SYNC_SCRIPT_COMMAND));
  }

  @Test
  void notes_latheWrittenByAnotherVersion_syncRequiredWithTheCommand() throws Exception {
    writeManifest("0", List.of());
    engine = new LatheEngine(tmp);

    assertThat(engine.notes(List.of()).syncRequired())
        .singleElement()
        .asString()
        .contains("another Lathe version")
        .contains("`%s`".formatted(LatheLayout.SYNC_SCRIPT_COMMAND));
  }

  @Test
  void notes_buildFileEditedMidSession_syncRequiredUntilItsContentIsBack_editsStillCompile()
      throws Exception {
    final GreetFixture fx = greetFixture();
    stampModule(fx.callee(), fx.caller());
    final Path pom = TestCompiler.writeAt(tmp.resolve("module/pom.xml"), "<project/>", 1_000L);
    writeManifest(LatheLayout.SCHEMA_VERSION, List.of(syncedBuildFile(pom)));
    engine = new LatheEngine(tmp);
    engine.diagnostics(fx.caller());
    assertThat(engine.notes(List.of()).syncRequired()).isEmpty();

    Files.writeString(pom, "<project><!-- edited --></project>");
    engine.diagnostics(fx.caller());
    assertThat(engine.notes(List.of()).syncRequired())
        .as("seen by the query itself, not on the next tick")
        .containsExactly(BUILD_FILES_NOTE);

    writeNewer(fx.callee(), "package com.example; class Callee { void welcome() {} }");
    assertThat(engine.diagnostics(fx.caller()))
        .as("the agent's edits are still compiled while the sync is pending")
        .anyMatch(d -> d.getMessage().getLeft().contains("greet"));
    assertThat(engine.notes(List.of()).syncRequired()).containsExactly(BUILD_FILES_NOTE);
    assertThat(engine.verifyChange(List.of()).deferral()).isEqualTo("BUILD_FILES_PENDING");

    Files.writeString(pom, "<project/>");
    engine.diagnostics(fx.caller());
    assertThat(engine.notes(List.of()).syncRequired())
        .as("the POM is back to what the sync saw")
        .isEmpty();
  }

  @Test
  void notes_buildFileChangedBeforeTheSessionStarted_syncRequiredOnlyIfItsContentDiffers()
      throws Exception {
    final GreetFixture fx = greetFixture();
    final Path pom = TestCompiler.writeAt(tmp.resolve("module/pom.xml"), "<project/>", 1_000L);
    writeManifest(LatheLayout.SCHEMA_VERSION, List.of(syncedBuildFile(pom)));
    TestCompiler.writeAt(pom, "<project/>", 99_000_000_000L);
    engine = new LatheEngine(tmp);
    engine.diagnostics(fx.caller());
    assertThat(engine.notes(List.of()).syncRequired()).as("only touched").isEmpty();
    engine.close();

    Files.writeString(pom, "<project><!-- edited --></project>");
    engine = new LatheEngine(tmp);
    engine.diagnostics(fx.caller());

    assertThat(engine.notes(List.of()).syncRequired()).containsExactly(BUILD_FILES_NOTE);
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
  void rename_thenRenameBack_afterAQueryOnTheCaller_restoresTheCaller() throws Exception {
    final GreetFixture fx = crossPackageGreetFixture();
    engine = new LatheEngine(tmp);
    // A query leaves no open document behind to answer the rename back from its old text.
    engine.diagnostics(fx.caller());

    final var pos = offsetToPosition(fx.calleeSource(), fx.calleeSource().indexOf("greet"));
    engine.rename(fx.callee(), pos.getLine(), pos.getCharacter(), "welcome");
    assertThat(Files.readString(fx.caller())).contains("c.welcome()");

    // The renamed caller was written to disk, never opened: only a candidate index that follows
    // the disk finds it again.
    final LatheRename back = engine.rename(fx.callee(), pos.getLine(), pos.getCharacter(), "greet");

    assertThat(back.files())
        .extracting(LatheFileEdit::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Caller.java"));
    assertThat(Files.readString(fx.callee())).isEqualTo(fx.calleeSource());
    assertThat(Files.readString(fx.caller())).isEqualTo(fx.callerSource());
  }

  @Test
  void references_queriedFileThenEditedOnDisk_findsTheNewUse() throws Exception {
    final GreetFixture fx = crossPackageGreetFixture();
    engine = new LatheEngine(tmp);
    final var pos = offsetToPosition(fx.calleeSource(), fx.calleeSource().indexOf("greet"));
    engine.diagnostics(fx.caller());
    final int before =
        engine.references(fx.callee(), pos.getLine(), pos.getCharacter(), 50).total();

    Files.writeString(
        fx.caller(), fx.callerSource().replace("c.greet();", "c.greet(); c.greet();"));

    assertThat(engine.references(fx.callee(), pos.getLine(), pos.getCharacter(), 50).total())
        .isEqualTo(before + 1);
  }

  @Test
  void rename_methodAlsoCalledFromGeneratedSource_leavesTheGeneratedSourceAlone() throws Exception {
    final GreetFixture fx = greetFixture();
    // An annotation processor's output that calls the method: it regenerates from the renamed
    // source, so editing it would only collide with the regenerated copy.
    TestCompiler.writeModuleParams(
        tmp,
        "module",
        tmp.resolve("module/src/main/java"),
        tmp.resolve("module/target/generated-sources/annotations"));
    final String generatedSource =
        "package com.example; class CalleeHelper { void run(Callee c) { c.greet(); } }";
    final Path generated =
        TestCompiler.writeAt(
            tmp.resolve(".lathe/module")
                .resolve(LatheLayout.generatedSourcesDir("classes"))
                .resolve("com/example/CalleeHelper.java"),
            generatedSource,
            1_000L);
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(fx.calleeSource(), fx.calleeSource().indexOf("greet"));
    final LatheRename rename =
        engine.rename(fx.callee(), pos.getLine(), pos.getCharacter(), "welcome");

    assertThat(rename.files())
        .extracting(LatheFileEdit::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Caller.java"))
        .noneSatisfy(uri -> assertThat(uri).endsWith("CalleeHelper.java"));
    assertThat(Files.readString(generated)).isEqualTo(generatedSource);
  }

  @Test
  void rename_symbolDeclaredOutsideTheProject_refusesWithTheReasonAndEditsNothing()
      throws Exception {
    final String source =
        """
        package com.example;
        class Sample {
          int size(String text) { return text.length(); }
          @Override public String toString() { return "sample"; }
        }
        """;
    final Path file = TestCompiler.writeModuleSource(tmp, "com/example/Sample.java", source);
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), file);
    engine = new LatheEngine(tmp);

    // A JDK type, a JDK method, and a project method overriding a JDK one.
    List.of("String text", "length", "toString")
        .forEach(
            symbol -> {
              final var pos = offsetToPosition(source, source.indexOf(symbol));
              assertThatThrownBy(
                      () -> engine.rename(file, pos.getLine(), pos.getCharacter(), "renamed"))
                  .as(symbol)
                  .hasMessageContaining("outside the project");
            });
    assertThat(Files.readString(file)).isEqualTo(source);
  }

  @Test
  void rename_symbolDeclaredInGeneratedSource_refusesWithTheReasonAndEditsNothing()
      throws Exception {
    final Path generated =
        TestCompiler.writeAt(
            tmp.resolve(".lathe/module")
                .resolve(LatheLayout.generatedSourcesDir("classes"))
                .resolve("com/example/SampleBuilder.java"),
            "package com.example; class SampleBuilder { void name() {} }",
            1_000L);
    final String source =
        "package com.example; class Sample { void run(SampleBuilder b) { b.name(); } }";
    final Path file = TestCompiler.writeModuleSource(tmp, "com/example/Sample.java", source);
    // After the source, whose write declares the module without generated sources.
    TestCompiler.writeModuleParams(
        tmp,
        "module",
        tmp.resolve("module/src/main/java"),
        tmp.resolve("module/target/generated-sources/annotations"));
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), file, generated);
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(source, source.indexOf("name"));
    assertThatThrownBy(() -> engine.rename(file, pos.getLine(), pos.getCharacter(), "label"))
        .hasMessageContaining("generated source");
    assertThat(Files.readString(file)).isEqualTo(source);
  }

  // A nested type is found by its binary name (Outer$Inner): from the same package, and from
  // another package that imports only the outer type and spells it Outer.Inner.
  @Test
  void references_nestedType_findsUsesInItsPackageAndInAPackageImportingTheOuterType()
      throws Exception {
    final NestedFixture fx = nestedFixture();
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(fx.outerSource(), fx.outerSource().indexOf("Inner"));
    final LatheReferences references =
        engine.references(fx.outer(), pos.getLine(), pos.getCharacter(), 50);

    assertThat(references.references())
        .extracting(LatheLocation::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("User.java"))
        .anySatisfy(uri -> assertThat(uri).endsWith("Other.java"));
  }

  @Test
  void rename_nestedType_renamesTheDeclarationAndEveryUse() throws Exception {
    final NestedFixture fx = nestedFixture();
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(fx.userSource(), fx.userSource().indexOf("Inner"));
    engine.rename(fx.user(), pos.getLine(), pos.getCharacter(), "Nested");

    assertThat(Files.readString(fx.outer())).contains("class Nested").doesNotContain("Inner");
    assertThat(Files.readString(fx.user())).contains("Outer.Nested a");
    assertThat(Files.readString(fx.other())).contains("Outer.Nested b");
  }

  @Test
  void findImplementations_nestedInterface_findsTheImplementingClass() throws Exception {
    final NestedFixture fx = nestedFixture();
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(fx.outerSource(), fx.outerSource().indexOf("Listener"));
    final LatheImplementations implementations =
        engine.findImplementations(fx.outer(), pos.getLine(), pos.getCharacter(), 50);

    assertThat(implementations.implementations())
        .extracting(LatheLocation::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Other.java"));
  }

  @Test
  void rename_publicTypeUsedInDownstreamModule_movesFileAndMirrorsOnlyTheNewClass()
      throws Exception {
    final String apiSource =
        "package com.example.api;\npublic class Api { public int a() { return 1; } }\n";
    final Path api =
        TestCompiler.writeMirroredModule(
                tmp, "api", Map.of("com/example/api/Api.java", apiSource), List.of(), 1_000L)
            .get(0);
    final Path app =
        TestCompiler.writeMirroredModule(
                tmp,
                "app",
                Map.of(
                    "com/example/app/App.java",
                    "package com.example.app;\nimport com.example.api.Api;\n"
                        + "class App { int u() { return new Api().a(); } }\n"),
                List.of("api"),
                1_000L)
            .get(0);
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(apiSource, apiSource.indexOf("Api {"));
    final LatheRename rename = engine.rename(api, pos.getLine(), pos.getCharacter(), "Renamed");

    final Path renamed = api.resolveSibling("Renamed.java");
    assertThat(api).doesNotExist();
    assertThat(Files.readString(renamed)).contains("public class Renamed");
    assertThat(Files.readString(app)).contains("new Renamed()").doesNotContain("Api()");
    assertThat(rename.files())
        .extracting(LatheFileEdit::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Renamed.java"))
        .anySatisfy(uri -> assertThat(uri).endsWith("App.java"));

    // The commit swapped the mirror's class and compiled the dependent against it, in one go.
    final Path apiClasses = tmp.resolve(".lathe/api/classes/com/example/api");
    assertThat(apiClasses.resolve("Renamed.class")).exists();
    assertThat(apiClasses.resolve("Api.class")).doesNotExist();
    assertThat(CompiledStamps.load(tmp.resolve(".lathe/app"), "classes"))
        .containsEntry(
            Path.of("com/example/app/App.java"), Files.getLastModifiedTime(app).toMillis());
    assertThat(tmp.resolve(".lathe/lathe.lock")).doesNotExist();
  }

  @Test
  void rename_moreFilesThanTheBulkThreshold_compilesEveryFileIntoTheMirror() throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put("com/example/Callee.java", "package com.example; class Callee { void greet() {} }");
    for (int i = 0; i < 55; i++) {
      sources.put(
          "com/example/Caller%d.java".formatted(i),
          "package com.example; class Caller%d { void run(Callee c) { c.greet(); } }".formatted(i));
    }

    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    engine = new LatheEngine(tmp);

    final String calleeSource = sources.get("com/example/Callee.java");
    final var pos = offsetToPosition(calleeSource, calleeSource.indexOf("greet"));
    engine.rename(files.get(0), pos.getLine(), pos.getCharacter(), "welcome");

    // Every touched file is compiled and stamped before rename returns, not deferred to a sync.
    final Map<Path, Long> stamps = CompiledStamps.load(tmp.resolve(".lathe/module"), "classes");
    for (final Path file : files) {
      assertThat(stamps)
          .containsEntry(
              tmp.resolve("module/src/main/java").relativize(file),
              Files.getLastModifiedTime(file).toMillis());
    }
  }

  @Test
  void latheTestRun_completedOutcome_mapsCountsAndFailure() {
    final var outcome =
        LaunchOutcome.completed(
            0,
            List.of(new TranscriptLine(TranscriptLine.Stream.STDOUT, "ok")),
            List.of(
                new TestResult(
                    "com.example.FooTest",
                    "passes",
                    "",
                    "passed",
                    "",
                    -1,
                    -1,
                    "passes()",
                    null,
                    null,
                    List.of(),
                    null),
                new TestResult(
                    "com.example.FooTest",
                    "fails",
                    "",
                    "failed",
                    "java.lang.AssertionError: boom",
                    42,
                    -1,
                    "fails()",
                    null,
                    null,
                    List.of(),
                    null),
                new TestResult(
                    "com.example.FooTest",
                    "skips",
                    "",
                    "skipped",
                    "",
                    -1,
                    -1,
                    "skips()",
                    null,
                    null,
                    List.of(),
                    null),
                // A class whose @BeforeAll broke: its own record, without a method.
                new TestResult(
                    "com.example.BrokenTest",
                    "",
                    "",
                    "failed",
                    "setup exploded",
                    9,
                    -1,
                    "BrokenTest",
                    null,
                    null,
                    List.of(),
                    null)));

    final LatheTestRun run = LatheTestRun.from(outcome);

    assertThat(run.launched()).isTrue();
    assertThat(run.total()).isEqualTo(4);
    assertThat(run.passed()).isEqualTo(1);
    assertThat(run.failed()).isEqualTo(2);
    assertThat(run.skipped()).isEqualTo(1);
    assertThat(run.failures())
        .anySatisfy(
            f -> {
              assertThat(f.test()).isEqualTo("com.example.FooTest#fails");
              assertThat(f.summary()).contains("boom");
              assertThat(f.line()).isEqualTo(42);
            })
        .anySatisfy(f -> assertThat(f.test()).isEqualTo("com.example.BrokenTest"));
  }

  @Test
  void latheTestRun_blockedOutcome_carriesReasons() {
    final LatheTestRun run = LatheTestRun.from(LaunchOutcome.blocked(List.of("no runner jar")));

    assertThat(run.launched()).isFalse();
    assertThat(run.blockedReasons()).containsExactly("no runner jar");
    assertThat(run.total()).isZero();
  }

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
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), iface, impl, caller);
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
                    .anyMatch(d -> d.getMessage().getLeft().contains("greet")));
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

  @Test
  void verifyChange_referrerWithOnlyAHint_reportsClean() throws Exception {
    final Path callee =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Callee.java",
            "package com.example; class Callee { void greet() {} }");
    final Path caller =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Caller.java",
            "package com.example; class Caller { void run(Callee c) { c.greet(); } private void"
                + " unused() {} }");
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), callee, caller);
    engine = new LatheEngine(tmp);
    assertThat(engine.diagnostics(caller))
        .as("the referrer has an unused-method hint")
        .anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Hint);

    final LatheVerifyChange verify = engine.verifyChange(List.of(callee));

    assertThat(verify.perModule()).isEmpty();
  }

  @Test
  void verifyChange_noFilesAfterARenameThatBreaksAnUneditedSubclass_reportsIt() throws Exception {
    final String source = "package com.example;\nclass Callee {\n  void greet() {}\n}\n";
    final Path callee = TestCompiler.writeModuleSource(tmp, "com/example/Callee.java", source);
    // No use of greet(): the rename leaves Sub alone, but then its welcome() clashes on the
    // return type.
    final Path sub =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Sub.java",
            "package com.example;\nclass Sub extends Callee {\n  int welcome() { return 1; }\n}\n");
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), callee, sub);
    stampModule(callee, sub);
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(source, source.indexOf("greet"));
    engine.rename(callee, pos.getLine(), pos.getCharacter(), "welcome");
    // Callee compiled and was stamped by the rename's write, so nothing on disk reads as stale.
    final LatheVerifyChange verify = engine.verifyChange(List.of());

    assertThat(verify.perModule())
        .anySatisfy(
            module ->
                assertThat(module.diagnostics())
                    .anyMatch(d -> d.getMessage().getLeft().contains("welcome")));
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
                  .anyMatch(d -> d.getMessage().getLeft().contains("cannot find symbol"));
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
                    .anyMatch(d -> d.getMessage().getLeft().contains("cannot find symbol")));

    // A deleted type: nothing left to diagnose in its file, but its callers break.
    Files.delete(api);
    engine.searchSymbols("Api", 10);

    assertThat(engine.verifyChange(List.of()).perModule())
        .singleElement()
        .satisfies(module -> assertThat(module.module()).isEqualTo("app"));
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
  }

  @Test
  void verifyChange_twoOfFiveModulesDepend_suggestsTheScopedSyncOfTheDependents() throws Exception {
    final Path api = apiReactor(List.of("app1", "app2"), List.of("tool1", "tool2"));
    engine = new LatheEngine(tmp);

    TestCompiler.writeAt(api, API_SOURCE.replace("a()", "b()"), 10_000L);

    assertThat(engine.verifyChange(List.of()).suggestedSync())
        .as("the dependents, not the changed module: -am builds api along with them")
        .isEqualTo(LatheLayout.scopedSyncCommand(List.of("app1", "app2")));
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

  // Record a compile stamp per source so a freshly started engine sees the module as already
  // synced; a later writeNewer() edit is then the only thing that reads as changed.
  private void stampModule(final Path... sources) throws Exception {
    final Path moduleDir = tmp.resolve(".lathe/module");
    final Path sourceRoot = tmp.resolve("module/src/main/java");
    for (final Path source : sources) {
      CompiledStamps.record(
          moduleDir,
          "classes",
          sourceRoot.relativize(source),
          Files.getLastModifiedTime(source).toMillis());
    }
  }

  private BuildFileData syncedBuildFile(final Path file) throws Exception {
    return new BuildFileData(FileUtil.slashed(tmp.relativize(file)), FileUtil.fingerprint(file));
  }

  private void writeManifest(final String schemaVersion, final List<BuildFileData> buildFiles)
      throws Exception {
    Files.createDirectories(tmp.resolve(LatheLayout.LATHE_DIR));
    Json.write(
        new WorkspaceManifestData(
            schemaVersion,
            tmp.toString(),
            null,
            List.of(),
            null,
            List.of(),
            buildFiles,
            List.of(),
            List.of(),
            null),
        tmp.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON));
  }

  private void writeNewer(final Path source, final String content) throws Exception {
    Files.writeString(source, content);
    final long stamp =
        CompiledStamps.load(tmp.resolve(".lathe/module"), "classes").values().stream()
            .max(Long::compareTo)
            .orElse(0L);
    Files.setLastModifiedTime(source, FileTime.fromMillis(stamp + 10_000));
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

  // Public Callee.greet() called from Caller in another package, so only the reactor-wide
  // candidate index (no same-package scan) leads a search from Callee to Caller.
  private GreetFixture crossPackageGreetFixture() throws Exception {
    final String calleeSource =
        "package com.example;\npublic class Callee {\n  public void greet() {}\n}\n";
    final Path callee =
        TestCompiler.writeModuleSource(tmp, "com/example/Callee.java", calleeSource);
    final String callerSource =
        "package com.other;\nclass Caller { void run(com.example.Callee c) { c.greet(); } }\n";
    final Path caller = TestCompiler.writeModuleSource(tmp, "com/other/Caller.java", callerSource);
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), callee, caller);
    return new GreetFixture(callee, caller, calleeSource, callerSource);
  }

  // Outer declares a nested class and a nested interface; User uses the class from its package,
  // Other from another package through an import of Outer only, and implements the interface.
  private NestedFixture nestedFixture() throws Exception {
    final String outerSource =
        """
        package com.example;
        public class Outer {
          public static class Inner {}
          public interface Listener {}
        }
        """;
    final Path outer = TestCompiler.writeModuleSource(tmp, "com/example/Outer.java", outerSource);
    final String userSource = "package com.example; class User { Outer.Inner a; }";
    final Path user = TestCompiler.writeModuleSource(tmp, "com/example/User.java", userSource);
    final Path other =
        TestCompiler.writeModuleSource(
            tmp,
            "com/other/Other.java",
            """
            package com.other;
            import com.example.Outer;
            class Other implements Outer.Listener { Outer.Inner b; }
            """);
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), outer, user, other);
    return new NestedFixture(outer, user, other, outerSource, userSource);
  }

  private record NestedFixture(
      Path outer, Path user, Path other, String outerSource, String userSource) {}

  private record GreetFixture(Path callee, Path caller, String calleeSource, String callerSource) {}
}
