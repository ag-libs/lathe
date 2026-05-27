package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.analysis.CompileMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
  void compile_fullMode_generatesClassWithoutAnalysis() throws Exception {
    final Path sourceRoot = td.resolve("src/main/java");
    final Path sourceFile = sourceRoot.resolve("Sample.java");
    Files.createDirectories(sourceFile.getParent());

    final var config =
        new ModuleSourceConfig(
            td.resolve(".lathe"),
            "classes",
            td.resolve("target/classes"),
            null,
            List.of(sourceRoot),
            List.of(),
            List.of(),
            List.of(),
            null,
            "UTF-8",
            false,
            false,
            null,
            List.of());

    try (var compiler = new ModuleSourceCompiler(config)) {
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
}
