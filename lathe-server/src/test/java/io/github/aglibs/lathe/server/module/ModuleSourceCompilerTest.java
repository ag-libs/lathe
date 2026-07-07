package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.ClassTree;
import io.github.aglibs.lathe.core.typeindex.ClassFileTypeScanner;
import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.SourceAnalysisSession;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import io.github.aglibs.lathe.server.analysis.TransientAnalysis;
import io.github.aglibs.lathe.server.analysis.TransientSource;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;
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

  @Test
  void compile_fullMode_withInnerClass_writtenBinaryNamesContainsBothClasses() throws Exception {
    final Path sourceRoot = td.resolve("src/main/java");
    final Path sourceFile = sourceRoot.resolve("Foo.java");
    Files.createDirectories(sourceFile.getParent());

    final var config =
        TestCompiler.moduleConfig(td.resolve(".lathe"), td.resolve("target/classes"), sourceRoot);

    try (var compiler = new ModuleSourceCompiler(config, new CompilationAdmission(1))) {
      final var result =
          compiler.compile(
              sourceFile.toUri().toString(),
              "class Foo { static class Inner {} }",
              CompileMode.FULL);

      assertThat(result.writtenBinaryNames()).containsExactlyInAnyOrder("Foo", "Foo$Inner");
    }
  }

  @Test
  void compile_fullMode_afterInnerClassRemoved_writtenBinaryNamesExcludesRemovedInner()
      throws Exception {
    final Path sourceRoot = td.resolve("src/main/java");
    final Path sourceFile = sourceRoot.resolve("Foo.java");
    Files.createDirectories(sourceFile.getParent());

    final var config =
        TestCompiler.moduleConfig(td.resolve(".lathe"), td.resolve("target/classes"), sourceRoot);

    try (var compiler = new ModuleSourceCompiler(config, new CompilationAdmission(1))) {
      compiler.compile(
          sourceFile.toUri().toString(), "class Foo { static class Inner {} }", CompileMode.FULL);
      assertThat(config.latheClassesDir().resolve("Foo$Inner.class")).exists();

      final var result =
          compiler.compile(sourceFile.toUri().toString(), "class Foo {}", CompileMode.FULL);

      assertThat(result.writtenBinaryNames()).containsExactly("Foo");
      assertThat(config.latheClassesDir().resolve("Foo$Inner.class"))
          .exists(); // WorkspaceSession calls deleteStaleClassOutputs, not ModuleSourceCompiler
    }
  }

  @Test
  void compile_fullMode_generatesClassWithoutAnalysis() throws Exception {
    final Path sourceRoot = td.resolve("src/main/java");
    final Path sourceFile = sourceRoot.resolve("Sample.java");
    Files.createDirectories(sourceFile.getParent());

    final var config =
        TestCompiler.moduleConfig(td.resolve(".lathe"), td.resolve("target/classes"), sourceRoot);

    try (var compiler = new ModuleSourceCompiler(config, new CompilationAdmission(1))) {
      final var result =
          compiler.compile(
              sourceFile.toUri().toString(),
              "class Sample { String value() { return \"x\"; } }",
              CompileMode.FULL);

      assertThat(result.diagnostics()).isEmpty();
      assertThat(result.fileAnalysis().tree()).isNull();
      assertThat(result.fileAnalysis().elements()).isNull();
      assertThat(config.latheClassesDir().resolve("Sample.class")).exists();
    }
  }

  @Test
  void compile_openMode_fileUnderGeneratedSourcesRoot_compilesInsteadOfThrowing() throws Exception {
    final Path sourceRoot = td.resolve("src/main/java");
    final Path genRoot = td.resolve("target/generated-sources/annotations");
    final Path genFile = genRoot.resolve("gen/GenBuilder.java");
    Files.createDirectories(genFile.getParent());

    final var config =
        new ModuleSourceConfig(
            td.resolve(".lathe/module"),
            "classes",
            td.resolve("target/classes"),
            genRoot,
            List.of(sourceRoot),
            List.of(),
            List.of(),
            List.of(),
            "21",
            "UTF-8",
            false,
            false,
            null,
            List.of());

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

  private static String declaredTypeName(final TransientAnalysis analysis) {
    final var declared = (ClassTree) analysis.analysis().tree().getTypeDecls().getFirst();
    return declared.getSimpleName().toString();
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
              List.of());

      assertThat(outcome.items()).extracting(CompletionItem::getLabel).contains("ReactorOnlyType");
    }
  }
}
