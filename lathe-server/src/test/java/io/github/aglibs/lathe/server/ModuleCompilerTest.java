package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModuleCompilerTest {

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

    assertThat(ModuleCompiler.modeCompilerArgs(args, ModuleCompiler.Mode.FAST))
        .containsExactly("-Xlint:unchecked", "--add-reads", "com.example=ALL-UNNAMED");
    assertThat(ModuleCompiler.modeCompilerArgs(args, ModuleCompiler.Mode.OPEN))
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

    assertThat(ModuleCompiler.modeCompilerArgs(args, ModuleCompiler.Mode.FULL)).isSameAs(args);
  }
}
