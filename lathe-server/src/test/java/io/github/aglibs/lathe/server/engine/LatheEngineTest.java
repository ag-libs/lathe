package io.github.aglibs.lathe.server.engine;

import static io.github.aglibs.lathe.server.analysis.SourceLocator.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.BuildFileData;
import io.github.aglibs.lathe.core.schema.DependencyData;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.SourceStatus;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import io.github.aglibs.lathe.server.FormatterFixtures;
import io.github.aglibs.lathe.server.LombokFixtures;
import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.analysis.MissingImportsResult.MissingImport;
import io.github.aglibs.lathe.server.engine.LatheEngine.CallDirection;
import io.github.aglibs.lathe.server.engine.LatheEngine.SymbolScope;
import io.github.aglibs.lathe.server.engine.LatheEngine.TestScope;
import io.github.aglibs.lathe.server.run.LaunchOutcome;
import io.github.aglibs.lathe.server.run.RunTarget;
import io.github.aglibs.lathe.server.run.RunnableKind;
import io.github.aglibs.lathe.server.run.TestResult;
import io.github.aglibs.lathe.server.run.TranscriptLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
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
  void definition_crossFileInModule_resolvesTargetWithItsWorkspacePathAndLine() throws Exception {
    final GreetFixture fx = greetFixture();
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(fx.callerSource(), fx.callerSource().indexOf("greet()"));
    final List<LatheLocation> targets =
        engine.definition(fx.caller(), pos.getLine(), pos.getCharacter());

    assertThat(targets).hasSize(1);
    final LatheLocation target = targets.get(0);
    assertThat(target.uri()).endsWith("Callee.java");
    assertThat(target.origin()).isEqualTo(LatheLocation.Origin.REACTOR);
    assertThat(target.path()).isEqualTo(FileUtil.slashed(tmp.relativize(fx.callee())));
    assertThat(target.snippet()).as("the target line alone").isEqualTo("void greet() {}");
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
            fx.callee(),
            calleePos.getLine(),
            calleePos.getCharacter(),
            CallDirection.INCOMING,
            SymbolScope.ALL,
            50);
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
            fx.caller(),
            callerPos.getLine(),
            callerPos.getCharacter(),
            CallDirection.OUTGOING,
            SymbolScope.REACTOR,
            50);
    assertThat(outgoing.incoming()).isFalse();
    assertThat(outgoing.calls())
        .as("the call site in the caller, not the callee's declaration")
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.name()).contains("greet");
              assertThat(call.location().uri()).endsWith("Caller.java");
              assertThat(call.location().range().getStart().getCharacter())
                  .isEqualTo(fx.callerSource().indexOf("greet()"));
            });
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

  // Line 2 is "  void greet() {}".
  @Test
  void onName_positionsAroundAMethod_trueOnlyOnANameNotAKeywordBlankOrPastTheEnd()
      throws Exception {
    final GreetFixture fx = greetFixture();
    engine = new LatheEngine(tmp);

    assertThat(onName(fx.callee(), 2, 7)).as("the first letter of greet").isTrue();
    assertThat(onName(fx.callee(), 2, 11)).as("its last letter").isTrue();
    assertThat(onName(fx.callee(), 1, 6)).as("Callee").isTrue();
    assertThat(onName(fx.callee(), 2, 2)).as("the keyword void").isFalse();
    assertThat(onName(fx.callee(), 2, 0)).as("indentation").isFalse();
    assertThat(onName(fx.callee(), 2, 12)).as("the parenthesis").isFalse();
    assertThat(onName(fx.callee(), 2, 40)).as("past the line's end").isFalse();
    assertThat(onName(fx.callee(), 9, 0)).as("past the file's end").isFalse();
  }

  // A word in a comment or a string looks like a name, but javac would answer there for the
  // enclosing class, method or variable.
  @Test
  void onName_wordsInCommentsAndStrings_falseButTrueOnANameAndAJavadocLink() throws Exception {
    final String javadoc = "/** Greets, see {@link Object}. */";
    final String method = "  String greet() { return \"greet the world\"; }";
    final Path file =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Words.java",
            String.join(
                "\n",
                "package com.example;",
                "",
                javadoc,
                "class Words {",
                "  // greet the world",
                method,
                "}",
                ""));
    engine = new LatheEngine(tmp);

    assertThat(onName(file, 2, javadoc.indexOf("Greets"))).as("Javadoc text").isFalse();
    assertThat(onName(file, 2, javadoc.indexOf("Object"))).as("a {@link}").isTrue();
    assertThat(onName(file, 4, 5)).as("a line comment").isFalse();
    assertThat(onName(file, 5, method.indexOf("greet"))).as("the method's name").isTrue();
    assertThat(onName(file, 5, method.indexOf("\"greet") + 1)).as("a string").isFalse();
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
    assertThat(engine.searchSymbols("FrGreeter", SymbolScope.ALL, 50)).isEmpty();

    // An agent writes a new implementation with its own file tool, then queries at once.
    TestCompiler.writeModuleSource(
        tmp,
        "com/example/FrGreeter.java",
        "package com.example; class FrGreeter implements Greeter { public void greet() {} }");

    assertThat(engine.searchSymbols("FrGreeter", SymbolScope.ALL, 50))
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

    final List<LatheSymbol> symbols = engine.searchSymbols("Callee", SymbolScope.ALL, 50);

    assertThat(symbols).anySatisfy(symbol -> assertThat(symbol.name()).contains("Callee"));
    assertThat(engine.searchSymbols("*Callee*", SymbolScope.REACTOR, 50))
        .as("a glob, as agents write by habit")
        .extracting(LatheSymbol::name)
        .contains("Callee");
    assertThat(engine.searchSymbols("*", SymbolScope.ALL, 50)).isEmpty();
  }

  // No project code can use a type in a JDK package its module does not export to everyone.
  @Test
  void jdkInternal_containers_onlyTheJdksUnexportedPackages() {
    assertThat(LatheEngine.jdkInternal("com.sun.tools.javac.comp")).isTrue();
    assertThat(LatheEngine.jdkInternal("com.sun.tools.javac.util.JCDiagnostic"))
        .as("an enclosing type in an internal package")
        .isTrue();
    assertThat(LatheEngine.jdkInternal("com.sun.source.tree")).as("exported").isFalse();
    assertThat(LatheEngine.jdkInternal("java.util.Map")).isFalse();
    assertThat(LatheEngine.jdkInternal("com.example.app")).as("not the JDK's").isFalse();
    assertThat(LatheEngine.jdkInternal(null)).isFalse();
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
        .isEqualTo(new LatheNotes(List.of(), List.of(), List.of(), false));

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
    writeManifest("0", List.of());
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

    final LatheRename rename = renameAt(fx.callee(), fx.calleeSource(), "greet", "welcome");

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

    renameAt(fx.callee(), fx.calleeSource(), "greet", "welcome");
    assertThat(Files.readString(fx.caller())).contains("c.welcome()");

    // The renamed caller was written to disk, never opened: only a candidate index that follows
    // the disk finds it again.
    final LatheRename back = renameAt(fx.callee(), fx.calleeSource(), "greet", "greet");

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

    final LatheRename rename = renameAt(fx.callee(), fx.calleeSource(), "greet", "welcome");

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
              assertThatThrownBy(() -> renameAt(file, source, symbol, "renamed"))
                  .as(symbol)
                  .hasMessageContaining("outside the project");
            });
    assertThat(Files.readString(file)).isEqualTo(source);
  }

  // The scan lists a nested class before its enclosing one; running the class runs the outermost,
  // which JUnit runs with its @Nested classes, and every top-level class of the file, not only the
  // first. A method in the outer class wins a name clash.
  @Test
  void selectTargets_nestedAndSecondTopLevelClass_runsEveryOutermostClassAndOneMethod() {
    final List<RunTarget> targets =
        List.of(
            target("demo.Outer$Inner#run()", RunnableKind.TEST_METHOD, "run"),
            target("demo.Outer$Inner", RunnableKind.TEST_CLASS, "Inner"),
            target("demo.Outer#run()", RunnableKind.TEST_METHOD, "run"),
            target("demo.Outer", RunnableKind.TEST_CLASS, "Outer"),
            target("demo.Second", RunnableKind.TEST_CLASS, "Second"));
    final Path file = tmp.resolve("Outer.java");

    assertThat(LatheEngine.selectTargets(targets, TestScope.CLASS, null, file))
        .extracting(RunTarget::id)
        .containsExactly("demo.Outer", "demo.Second");
    assertThat(LatheEngine.selectTargets(targets, TestScope.METHOD, "run", file))
        .extracting(RunTarget::id)
        .containsExactly("demo.Outer#run()");
    assertThatThrownBy(() -> LatheEngine.selectTargets(targets, TestScope.METHOD, "missing", file))
        .hasMessage("no test method 'missing' in Outer.java");
  }

  // A file with no test says why: its module is outside the build, or it is the class under test,
  // and then its test class is named.
  @Test
  void runTest_fileWithNoTest_namesItsTestClass_orWhyNoModuleCompilesIt() throws Exception {
    final List<Path> sources =
        TestCompiler.writeMirroredModule(
            tmp,
            "module",
            Map.of(
                "com/example/Shop.java", "package com.example; class Shop {}",
                "com/example/ShopTest.java", "package com.example; class ShopTest {}",
                "com/example/Lone.java", "package com.example; class Lone {}"),
            List.of(),
            1_000L);
    final Path outsideTheBuild =
        TestCompiler.writeAt(
            tmp.resolve("newmodule/src/main/java/com/example/Fresh.java"),
            "package com.example; class Fresh {}",
            1_000L);
    engine = new LatheEngine(tmp);

    assertThatThrownBy(() -> engine.runTest(sourceNamed(sources, "Shop"), TestScope.CLASS, null))
        .as("a location's path, written with / on every OS")
        .hasMessage(
            "no test in Shop.java; its test class may be module/src/main/java/com/example/ShopTest.java");
    assertThatThrownBy(() -> engine.runTest(sourceNamed(sources, "Lone"), TestScope.CLASS, null))
        .hasMessage("no test in Lone.java");
    assertThatThrownBy(() -> engine.runTest(outsideTheBuild, TestScope.CLASS, null))
        .hasMessageContaining("is outside the modules the last build compiled");
  }

  private static Path sourceNamed(final List<Path> sources, final String type) {
    return sources.stream()
        .filter(source -> FileUtil.javaTypeName(source).equals(type))
        .findFirst()
        .orElseThrow();
  }

  private static RunTarget target(final String id, final RunnableKind kind, final String label) {
    final var start = new Position(0, 0);
    return new RunTarget(id, "", kind, label, "app", "file:///Outer.java", new Range(start, start));
  }

  @Test
  void rename_toATakenOrInvalidName_refusesWithTheReasonAndEditsNothing() throws Exception {
    final String source =
        """
        package com.example;
        class Sample {
          int count;
          int total;
          int sum() { return count + total; }
        }
        """;
    final Path file = TestCompiler.writeModuleSource(tmp, "com/example/Sample.java", source);
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), file);
    engine = new LatheEngine(tmp);

    assertThatThrownBy(() -> renameAt(file, source, "count", "total"))
        .hasMessageContaining(
            "`count` cannot be renamed to `total`: Sample already declares field `total`");
    assertThatThrownBy(() -> renameAt(file, source, "count", "a.b"))
        .hasMessageContaining("`a.b` is not a valid name");
    assertThat(Files.readString(file)).isEqualTo(source);
  }

  // Task has no Task.java of its own; its members and its nested type's are still the project's,
  // while its override of a JDK method stays refused.
  @Test
  void rename_memberOfASecondaryTopLevelType_renamesItAndStillRefusesAJdkOverride()
      throws Exception {
    final String source =
        """
        package com.example;
        class Sample {
          void use(Task task, Task.Step step) { task.run(); step.next(); }
        }
        interface Task {
          void run();
          class Step { void next() {} @Override public String toString() { return "step"; } }
        }
        """;
    final Path file = TestCompiler.writeModuleSource(tmp, "com/example/Sample.java", source);
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), file);
    engine = new LatheEngine(tmp);

    assertThatThrownBy(() -> renameAt(file, source, "toString", "renamed"))
        .hasMessageContaining("outside the project");
    // From each first occurrence, a use, on the text the previous rename left.
    for (final String name : List.of("run", "next", "Step", "Task")) {
      renameAt(file, Files.readString(file), name, "%sRenamed".formatted(name));
    }

    assertThat(Files.readString(file))
        .contains("void use(TaskRenamed task, TaskRenamed.StepRenamed step)")
        .contains("task.runRenamed(); step.nextRenamed();")
        .contains("interface TaskRenamed {")
        .contains("void runRenamed();")
        .contains("class StepRenamed { void nextRenamed() {}");
  }

  // Renaming the interface method also edits the override, which then no longer overrides its
  // superclass's run: Thread's outside the project (Task), Base's inside it (Step). Starting from
  // the override hits the same conflict. A diamond whose every parent is in the family renames.
  @Test
  void rename_overrideAlsoOverridingAnUnrelatedSupertype_refusesButAFamilyDiamondRenames()
      throws Exception {
    final Map<String, String> sources =
        Map.of(
            "Task", "package com.example; interface Task { void run(); }",
            "Step", "package com.example; interface Step { void run(); }",
            "Base", "package com.example; class Base { public void run() {} }",
            "Worker",
                "package com.example; class Worker extends Thread implements Task {"
                    + " @Override public void run() {} }",
            "Job",
                "package com.example; class Job extends Base implements Step {"
                    + " @Override public void run() {} }",
            "Plan", "package com.example; interface Plan { void run(); }",
            "Phase", "package com.example; interface Phase extends Plan { void run(); }",
            "Impl",
                "package com.example; class Impl implements Plan, Phase {"
                    + " @Override public void run() {} }");
    TestCompiler.writeMirroredModule(
        tmp,
        "module",
        sources.entrySet().stream()
            .collect(
                Collectors.toMap(
                    source -> "com/example/%s.java".formatted(source.getKey()),
                    Map.Entry::getValue)),
        List.of(),
        1_000L);
    engine = new LatheEngine(tmp);

    Map.of("Task", "Worker.java", "Step", "Job.java", "Job", "Job.java")
        .forEach(
            (start, override) ->
                assertThat(
                        catchThrowable(
                            () ->
                                renameAt(exampleFile(start), sources.get(start), "run", "execute")))
                    .as(start)
                    .hasMessageContaining("override in %s".formatted(override)));
    sources.forEach(
        (type, source) -> assertThat(exampleFile(type)).as(type).content().isEqualTo(source));

    renameAt(exampleFile("Plan"), sources.get("Plan"), "run", "execute");
    List.of("Plan", "Phase", "Impl")
        .forEach(
            type ->
                assertThat(exampleFile(type))
                    .as(type)
                    .content()
                    .contains("void execute()")
                    .doesNotContain("run"));
  }

  private Path exampleFile(final String type) {
    return tmp.resolve("module/src/main/java/com/example/%s.java".formatted(type));
  }

  // Whether a position tool answers at a position, rather than refusing it as not on a name.
  private boolean onName(final Path file, final int line, final int column) {
    try {
      engine.describe(file, line, column);
      return true;
    } catch (final LatheEngine.NotOnName e) {
      return false;
    }
  }

  // Renames the symbol at the first occurrence of `at` in the file's source.
  private LatheRename renameAt(
      final Path file, final String source, final String at, final String newName) {
    final var pos = offsetToPosition(source, source.indexOf(at));
    return engine.rename(file, pos.getLine(), pos.getCharacter(), newName);
  }

  // A static import names every member of its name. Renaming one overload, the import must stay for
  // the others, and the renamed calls in that file would be left without one; a member alone under
  // its name renames its import.
  @Test
  void rename_overloadOfAStaticallyImportedName_refuses_soleMemberRenamesItsImport()
      throws Exception {
    final String util =
        """
        package com.example;
        public class Util {
          public static int go(int i) { return i; }
          public static String go(String s) { return s; }
          public static int solo() { return 1; }
        }
        """;
    final String user =
        """
        package com.example.app;
        import static com.example.Util.go;
        import static com.example.Util.solo;
        class User {
          int u() { return go(1) + go("x").length() + solo(); }
        }
        """;
    final Path utilFile = TestCompiler.writeModuleSource(tmp, "com/example/Util.java", util);
    final Path userFile = TestCompiler.writeModuleSource(tmp, "com/example/app/User.java", user);
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), utilFile, userFile);
    engine = new LatheEngine(tmp);

    assertThatThrownBy(() -> renameAt(utilFile, util, "go(int", "goInt"))
        .hasMessageContaining("`go` cannot be renamed: User.java imports it statically");
    assertThat(Files.readString(userFile)).isEqualTo(user);

    renameAt(utilFile, util, "solo", "single");
    assertThat(Files.readString(userFile))
        .contains("import static com.example.Util.single;")
        .contains("+ single()");
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

    assertThatThrownBy(() -> renameAt(file, source, "name", "label"))
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

    renameAt(fx.user(), fx.userSource(), "Inner", "Nested");

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

    final LatheRename rename = renameAt(api, apiSource, "Api {", "Renamed");

    final Path renamed = api.resolveSibling("Renamed.java");
    assertThat(api).doesNotExist();
    assertThat(Files.readString(renamed)).contains("public class Renamed");
    assertThat(Files.readString(app)).contains("new Renamed()").doesNotContain("Api()");
    assertThat(rename.files())
        .extracting(LatheFileEdit::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Renamed.java"))
        .anySatisfy(uri -> assertThat(uri).endsWith("App.java"));
    assertThat(rename.paths()).contains(renamed, app);
    assertThat(engine.notes(rename.paths()).notChecked()).isEmpty();

    // The commit swapped the mirror's class and compiled the dependent against it, in one go.
    final Path apiClasses = tmp.resolve(".lathe/api/classes/com/example/api");
    assertThat(apiClasses.resolve("Renamed.class")).exists();
    assertThat(apiClasses.resolve("Api.class")).doesNotExist();
    assertThat(TestCompiler.stampedMtimes(tmp.resolve(".lathe/app"), "classes"))
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

    renameAt(files.get(0), sources.get("com/example/Callee.java"), "greet", "welcome");

    // Every touched file is compiled and stamped before rename returns, not deferred to a sync.
    final Map<Path, Long> stamps =
        TestCompiler.stampedMtimes(tmp.resolve(".lathe/module"), "classes");
    for (final Path file : files) {
      assertThat(stamps)
          .containsEntry(
              tmp.resolve("module/src/main/java").relativize(file),
              Files.getLastModifiedTime(file).toMillis());
    }
  }

  @Test
  @EnabledForJreRange(minVersion = FormatterFixtures.GOOGLE_JAVA_FORMAT_MIN_JDK)
  void format_pinnedFormatter_rewritesTheChangedLinesOnce_thenAlreadyFormatted() throws Exception {
    pinGoogleJavaFormat();
    final Path source =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Sample.java",
            "package com.example;\n\nclass Sample {\n  int n = 1;\n\n  int  twice( ) {return n*2;}\n}\n");
    engine = new LatheEngine(tmp);

    final Optional<LatheFormat> formatted = engine.format(source);

    assertThat(formatted)
        .hasValueSatisfying(
            format -> {
              assertThat(format.path()).isEqualTo(FileUtil.slashed(tmp.relativize(source)));
              assertThat(format.firstLine()).isEqualTo(6);
              assertThat(format.lastLine()).isEqualTo(8);
            });
    assertThat(Files.readString(source)).contains("  int twice() {\n    return n * 2;\n  }\n");
    assertThat(engine.format(source)).isEmpty();
  }

  @Test
  @EnabledForJreRange(minVersion = FormatterFixtures.GOOGLE_JAVA_FORMAT_MIN_JDK)
  void format_fileThatDoesNotParse_refusesWithTheReasonAndLeavesItAlone() throws Exception {
    pinGoogleJavaFormat();
    final String broken = "package com.example;\nclass Sample { int n = ; }\n";
    final Path source = TestCompiler.writeModuleSource(tmp, "com/example/Sample.java", broken);
    engine = new LatheEngine(tmp);

    assertThatThrownBy(() -> engine.format(source))
        .hasMessageStartingWith("Could not format Sample.java");
    assertThat(Files.readString(source)).isEqualTo(broken);
  }

  // A sync resolves a pinned formatter's jars; it cannot pin one the project does not have.
  @Test
  void format_noFormatter_namesTheStyleFile_unresolvedOne_namesTheSync() throws Exception {
    final Path source =
        TestCompiler.writeModuleSource(
            tmp, "com/example/Sample.java", "package com.example; class Sample {}");
    engine = new LatheEngine(tmp);

    assertThatThrownBy(() -> engine.format(source))
        .hasMessageStartingWith("No formatter for this workspace")
        .hasMessageContaining(LatheLayout.STYLE_SHARED_FILE)
        .hasMessageNotContaining(LatheLayout.SYNC_SCRIPT_COMMAND);

    engine.close();
    Files.writeString(
        tmp.resolve(LatheLayout.STYLE_SHARED_FILE), "{\"formatter\": {\"engine\": \"google\"}}");
    engine = new LatheEngine(tmp);

    assertThatThrownBy(() -> engine.format(source))
        .hasMessage(
            "The project's formatter is not resolved yet: run `%s`."
                .formatted(LatheLayout.SYNC_SCRIPT_COMMAND));
  }

  @Test
  void fixImports_mixedNames_addsTheUnambiguousOnes_leavesTheRestToTheAgent() throws Exception {
    TestCompiler.writeModuleSource(
        tmp, "com/example/util/Helper.java", "package com.example.util; public class Helper {}");
    TestCompiler.writeModuleSource(
        tmp, "com/example/a/Widget.java", "package com.example.a; public class Widget {}");
    TestCompiler.writeModuleSource(
        tmp, "com/example/b/Widget.java", "package com.example.b; public class Widget {}");
    final Path source =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/app/App.java",
            "package com.example.app;\nclass App {\n  Helper h;\n  Widget w;\n  Fooo f;\n}\n");
    engine = new LatheEngine(tmp);

    final LatheImports imports = engine.fixImports(source);

    assertThat(imports.path()).isEqualTo(FileUtil.slashed(tmp.relativize(source)));
    assertThat(imports.added()).containsExactly("com.example.util.Helper");
    assertThat(imports.unresolved())
        .extracting(MissingImport::name, MissingImport::candidates)
        .containsExactlyInAnyOrder(
            tuple("Widget", List.of("com.example.a.Widget", "com.example.b.Widget")),
            tuple("Fooo", List.of()));
    assertThat(Files.readString(source))
        .startsWith("package com.example.app;\n\nimport com.example.util.Helper;\nclass App {");

    final LatheImports again = engine.fixImports(source);

    assertThat(again.added()).as("nothing left to add").isEmpty();
    assertThat(again.unresolved()).extracting(MissingImport::name).contains("Widget", "Fooo");
  }

  // A call that another file of the same scope imports statically gets that static import, learned
  // from the workspace's own import lines with nothing configured.
  @Test
  void fixImports_memberImportedStaticallyElsewhere_addsTheStaticImport() throws Exception {
    TestCompiler.writeModuleSource(
        tmp,
        "com/example/Checked.java",
        "package com.example;\nimport static java.util.Objects.requireNonNull;\n"
            + "class Checked { Checked(Object o) { requireNonNull(o); } }\n");
    final Path source =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Unchecked.java",
            "package com.example;\nclass Unchecked { Unchecked(Object o) { requireNonNull(o); } }\n");
    engine = new LatheEngine(tmp);

    final LatheImports imports = engine.fixImports(source);

    assertThat(imports.added()).containsExactly("static java.util.Objects.requireNonNull");
    assertThat(imports.unresolved()).isEmpty();
    assertThat(Files.readString(source))
        .startsWith(
            "package com.example;\n\nimport static java.util.Objects.requireNonNull;\n"
                + "class Unchecked {");
  }

  @Test
  void fixImports_nothingMissing_writesNothing() throws Exception {
    final String clean =
        "package com.example;\nimport java.util.List;\nclass Sample { List<String> s; }\n";
    final Path source = TestCompiler.writeModuleSource(tmp, "com/example/Sample.java", clean);
    engine = new LatheEngine(tmp);

    final LatheImports imports = engine.fixImports(source);

    assertThat(imports.added()).isEmpty();
    assertThat(imports.unresolved()).isEmpty();
    assertThat(Files.readString(source)).isEqualTo(clean);
  }

  @Test
  void outline_typeWithNestedType_listsEveryDeclarationAtItsDepthWithItsLine() throws Exception {
    final Path source =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Shop.java",
            """
            package com.example;

            public class Shop {
              private int stock = 3;

              public Shop() {}

              int sell(int count) {
                return stock -= count;
              }

              record Item(String name) {}
            }
            """);
    engine = new LatheEngine(tmp);

    final List<LatheOutlineItem> outline = engine.outline(source);

    assertThat(outline)
        .extracting(
            LatheOutlineItem::kind,
            LatheOutlineItem::name,
            LatheOutlineItem::depth,
            item -> item.location().range().getStart().getLine() + 1,
            item -> item.location().snippet())
        .containsExactly(
            tuple("class", "Shop", 0, 3, "public class Shop {"),
            tuple("field", "stock", 1, 4, "private int stock = 3;"),
            tuple("constructor", "Shop", 1, 6, "public Shop() {}"),
            tuple("method", "sell", 1, 8, "int sell(int count) {"),
            tuple("struct", "Item", 1, 12, "record Item(String name) {}"),
            tuple("field", "name", 2, 12, "record Item(String name) {}"));
    assertThat(outline)
        .allSatisfy(
            item ->
                assertThat(item.location().path())
                    .isEqualTo(FileUtil.slashed(tmp.relativize(source))));
  }

  // The style file lathe:sync writes for a project whose Spotless step pins google-java-format.
  private void pinGoogleJavaFormat() throws Exception {
    Json.write(
        new WorkspaceStyleData(
            new FormatterSpec(
                LatheFlags.FORMATTER_GOOGLE,
                List.of(),
                "",
                Map.of(),
                FormatterFixtures.googleJavaFormatClasspath()),
            null),
        tmp.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.STYLE_FILE));
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

    final LatheTestRun run = LatheTestRun.from(outcome, List.of("com.example.FooTest"));

    assertThat(run.launched()).isTrue();
    assertThat(run.ran()).containsExactly("com.example.FooTest");
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
    final LatheTestRun run =
        LatheTestRun.from(LaunchOutcome.blocked(List.of("no runner jar")), List.of());

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
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), old, callee, caller);
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

  // Record a compile stamp per source so a freshly started engine sees the module as already
  // synced; a later writeNewer() edit is then the only thing that reads as changed.
  private void stampModule(final Path... sources) throws Exception {
    final Path sourceRoot = tmp.resolve("module/src/main/java");
    CompiledStamps.record(
        tmp.resolve(".lathe/module"),
        "classes",
        Arrays.stream(sources)
            .collect(
                Collectors.toUnmodifiableMap(
                    sourceRoot::relativize,
                    source -> IOUtil.unchecked(() -> TestCompiler.buildStamp(source)))));
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
        TestCompiler.stampedMtimes(tmp.resolve(".lathe/module"), "classes").values().stream()
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
