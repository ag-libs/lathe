package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.ClassFileTypeScanner;
import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.SourceAnalysisSession;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
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
              typeIndex);

      assertThat(outcome.items()).extracting(CompletionItem::getLabel).contains("ReactorOnlyType");
    }
  }
}
