package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.ClassTree;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.typeindex.ClassFileTypeScanner;
import io.github.aglibs.lathe.server.JavacCrashFixtures;
import io.github.aglibs.lathe.server.LombokFixtures;
import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.analysis.BatchWrite;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.CompilerResult;
import io.github.aglibs.lathe.server.analysis.LearnedStaticImports;
import io.github.aglibs.lathe.server.analysis.SourceAnalysisSession;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import io.github.aglibs.lathe.server.analysis.TransientAnalysis;
import io.github.aglibs.lathe.server.analysis.TransientSource;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.io.TempDir;

class ModuleSourceCompilerTest {

  @TempDir private Path td;

  @Test
  void modeCompilerArgs_interactiveModesDropJavacPluginsAndErrorProneOptions() {
    final var args =
        List.of(
            "-Xlint:unchecked",
            "-Xplugin:ErrorProne",
            "-Xep:DeadException:WARN",
            "-XepDisableWarningsInGeneratedCode",
            "-XepOpt:NullAway:AnnotatedPackages=com.example",
            "--add-reads",
            "com.example=ALL-UNNAMED");

    assertThat(ModuleSourceCompiler.modeCompilerArgs(args, CompileMode.FAST))
        .containsExactly("-Xlint:unchecked", "--add-reads", "com.example=ALL-UNNAMED");
    assertThat(ModuleSourceCompiler.modeCompilerArgs(args, CompileMode.OPEN))
        .containsExactly("-Xlint:unchecked", "--add-reads", "com.example=ALL-UNNAMED");
  }

  @Test
  void modeCompilerArgs_fullModeKeepsJavacPluginsAndErrorProneOptions() {
    final var args =
        List.of(
            "-Xlint:unchecked",
            "-Xplugin:ErrorProne",
            "-Xep:DeadException:WARN",
            "-XepDisableWarningsInGeneratedCode",
            "-XepOpt:NullAway:AnnotatedPackages=com.example");

    assertThat(ModuleSourceCompiler.modeCompilerArgs(args, CompileMode.FULL)).isSameAs(args);
  }

  // -J flags only reach a forked javac executable (maven-compiler-plugin fork=true); the in-process
  // javac API rejects them as "invalid flag", so they are dropped before any compile while every
  // other arg is preserved in order.
  @Test
  void dropForkedLauncherArgs_removesOnlyForkedJvmOptions() {
    assertThat(
            ModuleSourceCompiler.dropForkedLauncherArgs(
                List.of(
                    "-Xlint:unchecked",
                    "-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                    "-J-Xmx2g",
                    "-Xplugin:ErrorProne")))
        .containsExactly("-Xlint:unchecked", "-Xplugin:ErrorProne");

    final var withoutForked = List.of("-Xlint:unchecked", "-Xplugin:ErrorProne");
    assertThat(ModuleSourceCompiler.dropForkedLauncherArgs(withoutForked))
        .containsExactlyElementsOf(withoutForked);
  }

  @Test
  void compileBatch_innerClassAddedThenRemoved_reportsWhatEachWriteWrote_leavesOldOutputs()
      throws Exception {
    final Path sourceRoot = td.resolve("src/main/java");
    final String fooUri = sourceRoot.resolve("Foo.java").toUri().toString();
    Files.createDirectories(sourceRoot);
    final var config =
        TestCompiler.moduleConfig(td.resolve(".lathe"), td.resolve("target/classes"), sourceRoot);

    try (var compiler = new ModuleSourceCompiler(config, new CompilationAdmission(1))) {
      final BatchWrite withInner =
          compiler.compileBatch(
              List.of(new TransientSource(fooUri, "class Foo { static class Inner {} }")),
              () -> {});
      assertThat(withInner.writtenBinaryNames()).containsExactlyInAnyOrder("Foo", "Foo$Inner");
      assertThat(config.latheClassesDir().resolve("Foo.class")).exists();
      assertThat(config.latheClassesDir().resolve("Foo$Inner.class")).exists();

      final BatchWrite withoutInner =
          compiler.compileBatch(List.of(new TransientSource(fooUri, "class Foo {}")), () -> {});
      assertThat(withoutInner.writtenBinaryNames()).containsExactly("Foo");
      assertThat(config.latheClassesDir().resolve("Foo$Inner.class"))
          .as("deleting outputs a write no longer produces is the session's job")
          .exists();
    }
  }

  // A detached compile (a check of edited text, as codeAction/resolve and verify_change run) leaves
  // the document's own compile undisturbed: javac reads text lazily from the per-document temp
  // file,
  // so a tree cached from that compile, which code actions cut text by, keeps reading its own text.
  @Test
  void compileDetached_otherText_cachedTreeKeepsItsOwnText() throws Exception {
    final Path sourceRoot = td.resolve("src/main/java");
    final String fooUri = sourceRoot.resolve("Foo.java").toUri().toString();
    Files.createDirectories(sourceRoot);
    final var config =
        TestCompiler.moduleConfig(td.resolve(".lathe"), td.resolve("target/classes"), sourceRoot);

    try (var compiler = new ModuleSourceCompiler(config, new CompilationAdmission(1))) {
      final var document = compiler.compile(fooUri, "class Foo { int a; }", CompileMode.OPEN);
      final var check = compiler.compileDetached(fooUri, "class Foo { int renamed; }");

      assertThat(check.diagnostics()).isEmpty();
      assertThat(document.fileAnalysis().tree().getSourceFile().getCharContent(false).toString())
          .isEqualTo("class Foo { int a; }");
    }
  }

  // Saving a file whose `try {` line was deleted: javac's Attr fails an assertion on the stray
  // catch. The write fails like any compile error, and the server keeps running.
  @Test
  void compileBatch_javacAssertionOnBrokenSource_writesNothingAndReportsTheSyntaxError()
      throws Exception {
    final Path sourceRoot = td.resolve("src/main/java");
    final String fooUri = sourceRoot.resolve("Foo.java").toUri().toString();
    Files.createDirectories(sourceRoot);
    final var config =
        TestCompiler.moduleConfig(td.resolve(".lathe"), td.resolve("target/classes"), sourceRoot);

    try (var compiler = new ModuleSourceCompiler(config, new CompilationAdmission(1))) {
      final BatchWrite write =
          compiler.compileBatch(
              List.of(new TransientSource(fooUri, JavacCrashFixtures.TRY_LINE_DELETED)), () -> {});

      assertThat(write.writtenBinaryNames()).isEmpty();
      assertThat(config.latheClassesDir().resolve("Foo.class")).doesNotExist();
      assertThat(write.diagnosticsByUri().get(fooUri))
          .extracting(diagnostic -> diagnostic.getMessage().getLeft())
          .anyMatch(message -> message.contains("'catch' without 'try'"));
    }
  }

  // macOS temp dirs live under the /var -> /private/var symlink; javac reports outputs at the
  // resolved path, which must still map back to binary names.
  @Test
  void compileBatch_workspaceBehindSymlink_reportsBinaryNames() throws Exception {
    final Path root =
        Files.createSymbolicLink(td.resolve("link"), Files.createDirectory(td.resolve("real")));
    final Path sourceRoot = root.resolve("src/main/java");
    final String fooUri = sourceRoot.resolve("com/example/Foo.java").toUri().toString();
    Files.createDirectories(sourceRoot);
    final var config =
        TestCompiler.moduleConfig(
            root.resolve(".lathe"), root.resolve("target/classes"), sourceRoot);

    try (var compiler = new ModuleSourceCompiler(config, new CompilationAdmission(1))) {
      final BatchWrite write =
          compiler.compileBatch(
              List.of(
                  new TransientSource(
                      fooUri, "package com.example; class Foo { static class Inner {} }")),
              () -> {});

      assertThat(write.writtenBinaryNames())
          .containsExactlyInAnyOrder("com.example.Foo", "com.example.Foo$Inner");
    }
  }

  // Lombok changes the class it compiles in place, so an analysis runs it, alone, when a build has
  // it; a build with <proc>none</proc> runs no processor, and neither does its analysis.
  @Test
  @EnabledForJreRange(maxVersion = LombokFixtures.MAX_JDK)
  void compile_lombokOnTheProcessorPath_analysisSeesGeneratedMembersUnlessProcNone() {
    assertThat(lombokAnalysisErrors(null)).isEmpty();
    assertThat(lombokAnalysisErrors(LatheFlags.PROC_NONE))
        .anyMatch(message -> message.contains("getName()"));
  }

  private List<String> lombokAnalysisErrors(final String proc) {
    final Path sourceRoot = td.resolve("src/main/java");
    final String uri = sourceRoot.resolve("com/example/Person.java").toUri().toString();
    final String source =
        """
        package com.example;
        @lombok.Data
        class Person {
          private String name;
          String greeting() { return "Hello, " + getName(); }
        }
        """;
    try (var compiler =
        new ModuleSourceCompiler(
            LombokFixtures.moduleConfig(td, sourceRoot, proc), new CompilationAdmission(1))) {
      return errorMessages(compiler.compile(uri, source, CompileMode.OPEN));
    }
  }

  private static List<String> errorMessages(final CompilerResult result) {
    return result.diagnostics().stream()
        .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
        .map(diagnostic -> diagnostic.getMessage(null))
        .toList();
  }

  // Lombok reads lombok.config from the source's directory up, and Lathe compiles a copy of the
  // source elsewhere: the copy must see the same chain, the package's own config and the reactor's.
  @Test
  @EnabledForJreRange(maxVersion = LombokFixtures.MAX_JDK)
  void compile_lombokConfigInPackageAndReactor_appliesToAnalysisAndWrite() throws Exception {
    final Path sourceRoot = td.resolve("src/main/java");
    final Path pkg = Files.createDirectories(sourceRoot.resolve("com/example"));
    Files.writeString(td.resolve("lombok.config"), "lombok.log.fieldName = LOGGER\n");
    Files.writeString(pkg.resolve("lombok.config"), "lombok.accessors.prefix += f\n");
    final String uri = pkg.resolve("Counter.java").toUri().toString();
    final String source =
        """
        package com.example;
        @lombok.Getter
        @lombok.extern.java.Log
        class Counter {
          private int fCount;
          int twice() {
            LOGGER.fine("twice");
            return getCount() * 2;
          }
        }
        """;

    try (var compiler =
        new ModuleSourceCompiler(
            LombokFixtures.moduleConfig(td, sourceRoot, null), new CompilationAdmission(1))) {
      assertThat(errorMessages(compiler.compile(uri, source, CompileMode.OPEN))).isEmpty();
      assertThat(
              compiler
                  .compileBatch(List.of(new TransientSource(uri, source)), () -> {})
                  .writtenBinaryNames())
          .containsExactly("com.example.Counter");
    }
  }

  @Test
  void compile_openMode_fileUnderGeneratedSourcesRoot_compilesInsteadOfThrowing() throws Exception {
    final Path sourceRoot = td.resolve("src/main/java");
    final var config =
        TestCompiler.moduleConfig(
            td.resolve(".lathe/module"),
            td.resolve("target/classes"),
            sourceRoot,
            td.resolve("target/generated-sources/annotations"));
    // EG-052: an opened generated file lives under the .lathe mirror, not Maven's target/ copy.
    final Path genFile = config.generatedSourcesDir().resolve("gen/GenBuilder.java");
    Files.createDirectories(genFile.getParent());

    try (var compiler = new ModuleSourceCompiler(config, new CompilationAdmission(1))) {
      final var result =
          compiler.compile(
              genFile.toUri().toString(),
              "package gen; public class GenBuilder {}",
              CompileMode.OPEN);

      assertThat(result.diagnostics()).isEmpty();
    }
  }

  @Test
  void analyzeBatch_multipleFiles_mapsEachAnalysisToItsUri() throws Exception {
    final Path sourceRoot = td.resolve("src/main/java");
    final Path fileA = sourceRoot.resolve("A.java");
    final Path fileB = sourceRoot.resolve("B.java");
    Files.createDirectories(sourceRoot);

    final var config =
        TestCompiler.moduleConfig(td.resolve(".lathe"), td.resolve("target/classes"), sourceRoot);

    try (var compiler = new ModuleSourceCompiler(config, new CompilationAdmission(1))) {
      final List<TransientAnalysis> analyses =
          compiler.analyzeBatch(
              List.of(
                  new TransientSource(fileA.toUri().toString(), "class A { String a; }"),
                  new TransientSource(fileB.toUri().toString(), "class B { String b; }")),
              () -> {});

      assertThat(analyses)
          .extracting(TransientAnalysis::uri)
          .containsExactlyInAnyOrder(fileA.toUri().toString(), fileB.toUri().toString());
      for (final var analysis : analyses) {
        final var expected = analysis.uri().endsWith("A.java") ? "A" : "B";
        assertThat(declaredTypeName(analysis)).isEqualTo(expected);
      }
    }
  }

  @Test
  void analyzeBatch_fileWithSyntaxError_stillReturnsValidFile() throws Exception {
    final Path sourceRoot = td.resolve("src/main/java");
    final Path broken = sourceRoot.resolve("Broken.java");
    final Path valid = sourceRoot.resolve("Valid.java");
    Files.createDirectories(sourceRoot);

    final var config =
        TestCompiler.moduleConfig(td.resolve(".lathe"), td.resolve("target/classes"), sourceRoot);

    try (var compiler = new ModuleSourceCompiler(config, new CompilationAdmission(1))) {
      final List<TransientAnalysis> analyses =
          compiler.analyzeBatch(
              List.of(
                  new TransientSource(broken.toUri().toString(), "class Broken { void m( { } }"),
                  new TransientSource(valid.toUri().toString(), "class Valid { String v; }")),
              () -> {});

      final var validAnalysis =
          analyses.stream()
              .filter(a -> a.uri().equals(valid.toUri().toString()))
              .findFirst()
              .orElseThrow();
      assertThat(validAnalysis.analysis().tree()).isNotNull();
      assertThat(declaredTypeName(validAnalysis)).isEqualTo("Valid");
    }
  }

  @Test
  void analyzeOpenBatch_eachSourceOwnResultInOrder_withTokens_syntaxErrorStaysLocal_parseFlagged()
      throws Exception {
    final Path sourceRoot = td.resolve("src/main/java");
    Files.createDirectories(sourceRoot);
    final var config =
        TestCompiler.moduleConfig(td.resolve(".lathe"), td.resolve("target/classes"), sourceRoot);
    final List<TransientSource> sources =
        List.of(
            new TransientSource(
                sourceRoot.resolve("Broken.java").toUri().toString(),
                "class Broken { void m( { } }"),
            new TransientSource(
                sourceRoot.resolve("Valid.java").toUri().toString(), "class Valid { String v; }"),
            // Resolves Valid from the batch, as an open sibling, without any compiled class.
            new TransientSource(
                sourceRoot.resolve("User.java").toUri().toString(), "class User { Valid v; }"),
            // Parses, but does not attribute: an error that is not a parse error.
            new TransientSource(
                sourceRoot.resolve("Typo.java").toUri().toString(), "class Typo { Missing m; }"));

    try (var compiler = new ModuleSourceCompiler(config, new CompilationAdmission(1))) {
      final List<CompilerResult> results = compiler.analyzeOpenBatch(sources, () -> {});

      assertThat(results).hasSize(4);
      assertThat(hasError(results.get(0))).isTrue();
      for (final CompilerResult result : results.subList(1, 3)) {
        assertThat(hasError(result)).isFalse();
        assertThat(result.fileAnalysis().tree()).isNotNull();
        assertThat(result.fileAnalysis().semanticTokens()).isNotEmpty();
      }

      assertThat(results)
          .as("parsed cleanly: only the file whose parse failed is flagged, in the batch")
          .extracting(CompilerResult::parsedCleanly)
          .containsExactly(false, true, true, true);
      assertThat(
              List.of(sources.get(0), sources.get(3)).stream()
                  .map(source -> compiler.compile(source.uri(), source.content(), CompileMode.OPEN))
                  .map(CompilerResult::parsedCleanly)
                  .toList())
          .as("and in a single-file analysis")
          .containsExactly(false, true);
    }
  }

  private static String declaredTypeName(final TransientAnalysis analysis) {
    final var declared = (ClassTree) analysis.analysis().tree().getTypeDecls().get(0);
    return declared.getSimpleName().toString();
  }

  @Test
  void compileBatch_sealedRootAndNewPermittedSubtype_resolveAgainstEachOther() throws Exception {
    final Path sourceRoot = td.resolve("src/main/java");
    final Path shape = sourceRoot.resolve("shapes/Shape.java");
    final Path circle = sourceRoot.resolve("shapes/Circle.java");
    final Path square = sourceRoot.resolve("shapes/Square.java");
    Files.createDirectories(shape.getParent());

    // A consistent, already-mirrored baseline: Shape permits Circle only.
    Files.writeString(shape, "package shapes; sealed interface Shape permits Circle {}");
    Files.writeString(circle, "package shapes; final class Circle implements Shape {}");
    final var config =
        TestCompiler.moduleConfig(td.resolve(".lathe"), td.resolve("target/classes"), sourceRoot);
    TestCompiler.compileToDir(config.latheClassesDir(), shape, circle);

    // The edit: Shape now permits a brand-new Square absent from the mirror.
    final String editedShape = "package shapes; sealed interface Shape permits Circle, Square {}";
    final String newSquare = "package shapes; final class Square implements Shape {}";

    try (var compiler = new ModuleSourceCompiler(config, new CompilationAdmission(1))) {
      // Neither file compiles on its own -- each needs the other's fresh bytecode (the deadlock).
      assertThat(
              compiler
                  .compile(shape.toUri().toString(), editedShape, CompileMode.FULL)
                  .diagnostics())
          .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
      assertThat(
              compiler
                  .compile(square.toUri().toString(), newSquare, CompileMode.FULL)
                  .diagnostics())
          .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);

      // Batched into one task, they resolve against one another and both .class files are written.
      final var result =
          compiler.compileBatch(
              List.of(
                  new TransientSource(shape.toUri().toString(), editedShape),
                  new TransientSource(square.toUri().toString(), newSquare)),
              () -> {});

      assertThat(result.failedUris()).isEmpty();
      assertThat(result.writtenBinaryNames()).contains("shapes.Shape", "shapes.Square");
      assertThat(config.latheClassesDir().resolve("shapes/Shape.class")).exists();
      assertThat(config.latheClassesDir().resolve("shapes/Square.class")).exists();
    }
  }

  @Test
  void compileBatch_oneBrokenSource_reportsOnlyItAsFailed_andWritesNothing() throws Exception {
    final Path sourceRoot = td.resolve("src/main/java");
    Files.createDirectories(sourceRoot);
    final var config =
        TestCompiler.moduleConfig(td.resolve(".lathe"), td.resolve("target/classes"), sourceRoot);
    final String brokenUri = sourceRoot.resolve("Broken.java").toUri().toString();

    try (var compiler = new ModuleSourceCompiler(config, new CompilationAdmission(1))) {
      final BatchWrite result =
          compiler.compileBatch(
              List.of(
                  new TransientSource(brokenUri, "class Broken { int b() { return missing(); } }"),
                  new TransientSource(
                      sourceRoot.resolve("Good.java").toUri().toString(), "class Good {}")),
              () -> {});

      // javac writes no class when any source fails, which is why the session retries without it.
      assertThat(result.failedUris()).containsExactly(brokenUri);
      assertThat(result.writtenBinaryNames()).isEmpty();
    }
  }

  // A module that ships a processor has it in its own class mirror, which is on its processor path.
  // With <proc>none</proc> the build never runs it; without, it runs, and its error (no source,
  // like -Werror's "warnings found") fails every source in the write.
  @Test
  void compileBatch_ownProcessorInClassMirror_runsOnlyWithoutProcNone() throws Exception {
    final Path sourceRoot = td.resolve("src/main/java");
    Files.createDirectories(sourceRoot);
    final var config =
        TestCompiler.moduleConfig(td.resolve(".lathe"), td.resolve("target/classes"), sourceRoot);
    final Path processor = td.resolve("proc/OwnProcessor.java");
    Files.createDirectories(processor.getParent());
    Files.writeString(
        processor,
        """
        import java.util.Set;
        import javax.annotation.processing.*;
        import javax.lang.model.SourceVersion;
        import javax.lang.model.element.TypeElement;
        import javax.tools.Diagnostic;

        @SupportedAnnotationTypes("*")
        public class OwnProcessor extends AbstractProcessor {
          @Override
          public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            if (!round.processingOver()) {
              processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "own processor ran");
            }
            return false;
          }

          @Override
          public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
          }
        }
        """);
    TestCompiler.compileToDir(config.latheClassesDir(), processor);
    final Path services =
        config.latheClassesDir().resolve("META-INF/services/javax.annotation.processing.Processor");
    Files.createDirectories(services.getParent());
    Files.writeString(services, "OwnProcessor\n");
    final List<TransientSource> sources =
        List.of(
            new TransientSource(sourceRoot.resolve("A.java").toUri().toString(), "class A {}"),
            new TransientSource(sourceRoot.resolve("B.java").toUri().toString(), "class B {}"));

    try (var compiler =
        new ModuleSourceCompiler(
            withProc(config, LatheFlags.PROC_NONE), new CompilationAdmission(1))) {
      final BatchWrite write = compiler.compileBatch(sources, () -> {});

      assertThat(write.failedUris()).isEmpty();
      assertThat(write.writtenBinaryNames()).containsExactlyInAnyOrder("A", "B");
    }

    try (var compiler = new ModuleSourceCompiler(config, new CompilationAdmission(1))) {
      final BatchWrite write = compiler.compileBatch(sources, () -> {});

      assertThat(write.failedUris())
          .containsExactlyInAnyOrderElementsOf(sources.stream().map(TransientSource::uri).toList());
      assertThat(write.diagnosticsByUri().values())
          .allSatisfy(
              diagnostics ->
                  assertThat(diagnostics)
                      .anySatisfy(
                          diagnostic ->
                              assertThat(diagnostic.getMessage().getLeft())
                                  .contains("own processor ran")));
    }
  }

  private static ModuleSourceConfig withProc(final ModuleSourceConfig config, final String proc) {
    return new ModuleSourceConfig(
        config.moduleDir(),
        config.sourceTree(),
        config.outputDir(),
        config.originalGenSourcesDir(),
        config.sourceRoots(),
        config.classpath(),
        config.modulepath(),
        config.processorPath(),
        config.release(),
        config.encoding(),
        config.parameters(),
        config.enablePreview(),
        proc,
        config.compilerArgs(),
        config.analysisMode(),
        config.externalOutput(),
        config.reactorRewrite());
  }

  private static boolean hasError(final CompilerResult result) {
    return result.diagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
  }

  // MODULE_SYSTEM sets no class/module path: a type from the host JDK's java.base (AbstractList)
  // must still resolve, and declaring into java.util only compiles because --patch-module overlays
  // the source onto the base module.
  @Test
  void compile_moduleSystemMode_resolvesHostModuleTypesWithoutClasspath() throws Exception {
    final Path sourceRoot = td.resolve("src");
    final Path source = sourceRoot.resolve("java/util/LatheProbe.java");
    Files.createDirectories(source.getParent());
    final var config =
        TestCompiler.moduleSystemConfig(td.resolve(".lathe/java.base"), sourceRoot, "java.base");

    try (var compiler = new ModuleSourceCompiler(config, new CompilationAdmission(1))) {
      final var result =
          compiler.compile(
              source.toUri().toString(),
              "package java.util; public abstract class LatheProbe extends AbstractList<Object> {}",
              CompileMode.OPEN);

      assertThat(result.diagnostics()).noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
    }
  }

  // An analysis with siblings resolves cross-file references in MODULE_SYSTEM: a producer absent
  // from the host java.base is seen only because the batch writes it into the --patch-module
  // overlay dir alongside the consumer.
  @Test
  void analyzeOpenBatch_moduleSystemMode_siblingResolvedViaPatchOverlay() throws Exception {
    final Path sourceRoot = td.resolve("src");
    final Path consumer = sourceRoot.resolve("java/util/LatheConsumer.java");
    final Path producer = sourceRoot.resolve("java/util/LatheProducer.java");
    Files.createDirectories(consumer.getParent());
    final String consumerSrc =
        "package java.util; public class LatheConsumer { int use() { return new LatheProducer().ping(); } }";
    final String producerSrc =
        "package java.util; public class LatheProducer { public int ping() { return 1; } }";
    final var config =
        TestCompiler.moduleSystemConfig(td.resolve(".lathe/java.base"), sourceRoot, "java.base");

    try (var compiler = new ModuleSourceCompiler(config, new CompilationAdmission(1))) {
      // The producer is in neither the host java.base nor the overlay, so the consumer alone fails.
      assertThat(
              compiler
                  .compile(consumer.toUri().toString(), consumerSrc, CompileMode.OPEN)
                  .diagnostics())
          .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);

      final List<TransientSource> sources =
          List.of(
              new TransientSource(consumer.toUri().toString(), consumerSrc),
              new TransientSource(producer.toUri().toString(), producerSrc));
      assertThat(compiler.analyzeOpenBatch(sources, () -> {}))
          .extracting(ModuleSourceCompilerTest::hasError)
          .containsExactly(false, false);
    }
  }

  @Test
  void complete_reactorOutputTypeInSameModule_suggestsIndexedType() throws Exception {
    final Path sourceRoot = td.resolve("module/src/main/java");
    final Path reactorSource = sourceRoot.resolve("example/ReactorOnlyType.java");
    Files.createDirectories(reactorSource.getParent());
    Files.writeString(reactorSource, "package example; public class ReactorOnlyType {}");

    final var config =
        TestCompiler.moduleConfig(
            td.resolve(".lathe/module"), td.resolve("module/target/classes"), sourceRoot);
    TestCompiler.compileToDir(config.latheClassesDir(), reactorSource);
    final var typeIndex =
        WorkspaceTypeIndex.build(
            List.of(), List.of(ClassFileTypeScanner.scanDirectory(config.latheClassesDir())));

    final String content = "package example; class Test { ReactorOnlyT field; }";
    final String markedContent = "package example; class Test { ReactorOnlyT§ field; }";
    final int cursor = markedContent.indexOf('§');
    final Path sourceFile = sourceRoot.resolve("example/Test.java");

    try (final var ctx =
        new SourceAnalysisSession(new ModuleSourceCompiler(config, new CompilationAdmission(1)))) {
      ctx.compile(sourceFile.toUri().toString(), content, 1, CompileMode.OPEN);

      final var outcome =
          ctx.complete(
              sourceFile.toUri().toString(),
              content,
              1,
              SourceLocator.offsetToPosition(content, cursor),
              null,
              typeIndex,
              List.of(),
              LearnedStaticImports.empty());

      assertThat(outcome.items()).extracting(CompletionItem::getLabel).contains("ReactorOnlyType");
    }
  }
}
