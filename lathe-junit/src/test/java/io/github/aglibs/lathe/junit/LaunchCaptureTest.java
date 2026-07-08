package io.github.aglibs.lathe.junit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.schema.LaunchMode;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class LaunchCaptureTest {

  @Test
  void toLaunchData_classpathFork_excludesOwnJar() {
    final Path ownJar = Path.of("/repo/lathe-junit.jar");
    final String classPath =
        String.join(File.pathSeparator, "/repo/lathe-junit.jar", "/repo/junit.jar");

    final var data = LaunchCapture.toLaunchData("/jdk", classPath, List.of("-Dfoo=bar"), ownJar);

    assertThat(data.mode()).isEqualTo(LaunchMode.CLASSPATH);
    assertThat(data.classPath()).containsExactly("/repo/junit.jar");
    assertThat(data.jvmArgs()).containsExactly("-Dfoo=bar");
  }

  @Test
  void toLaunchData_modularFork_capturesModulePathAndPatchModule() {
    final var data =
        LaunchCapture.toLaunchData(
            "/jdk",
            "/repo/junit.jar",
            List.of(
                "--module-path=/mods/a.jar%s/mods/b.jar".formatted(File.pathSeparator),
                "--patch-module=com.example.app=/workspace/app/target/test-classes"),
            null);

    assertThat(data.mode()).isEqualTo(LaunchMode.MODULE);
    assertThat(data.mainModule()).isEqualTo("com.example.app");
    assertThat(data.modulePath()).containsExactly("/mods/a.jar", "/mods/b.jar");
    assertThat(data.patchModules())
        .containsEntry("com.example.app", "/workspace/app/target/test-classes");
  }

  @Test
  void toLaunchData_splitOptions_capturesValues() {
    final var data =
        LaunchCapture.toLaunchData(
            "/jdk",
            "",
            List.of(
                "--add-opens",
                "com.example.app/com.example.app=ALL-UNNAMED",
                "--add-reads",
                "com.example.app=ALL-UNNAMED",
                "--add-exports",
                "com.example.app/com.example.internal=ALL-UNNAMED",
                "--add-modules",
                "ALL-MODULE-PATH,com.example.app"),
            null);

    assertThat(data.addOpens()).containsExactly("com.example.app/com.example.app=ALL-UNNAMED");
    assertThat(data.addReads()).containsExactly("com.example.app=ALL-UNNAMED");
    assertThat(data.addExports())
        .containsExactly("com.example.app/com.example.internal=ALL-UNNAMED");
    assertThat(data.addModules()).containsExactly("ALL-MODULE-PATH", "com.example.app");
  }

  @Test
  void toLaunchData_unrecognizedArgs_preservesInJvmArgs() {
    final var data =
        LaunchCapture.toLaunchData(
            "/jdk", "", List.of("-Xmx512m", "-javaagent:/agent.jar", "--enable-preview"), null);

    assertThat(data.jvmArgs())
        .containsExactly("-Xmx512m", "-javaagent:/agent.jar", "--enable-preview");
  }
}
