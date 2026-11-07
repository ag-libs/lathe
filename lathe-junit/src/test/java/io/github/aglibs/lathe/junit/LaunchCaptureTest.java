package io.github.aglibs.lathe.junit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.schema.LaunchMode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LaunchCaptureTest {

  // A Gradle test task of a source set beyond test names its tree; without one, or with an empty
  // value, the launch is the module's test launch. A named tree the module lacks (inherited by a
  // nested build, or a source set without Java) gets no launch at all.
  @Test
  void launchFile_treeNamedOrNot_namedTreesOwnFileElseTheTestLaunch(@TempDir final Path moduleDir)
      throws IOException {
    Files.writeString(moduleDir.resolve("lsp-params-classes-integrationTest.json"), "{}");
    Files.writeString(moduleDir.resolve("lsp-params-test-classes.json"), "{}");

    assertThat(LaunchCapture.launchFile(moduleDir, "classes-integrationTest"))
        .contains("test-launch-classes-integrationTest.json");
    assertThat(LaunchCapture.launchFile(moduleDir, "test-classes")).contains("test-launch.json");
    assertThat(LaunchCapture.launchFile(moduleDir, null)).contains("test-launch.json");
    assertThat(LaunchCapture.launchFile(moduleDir, " ")).contains("test-launch.json");
    assertThat(LaunchCapture.launchFile(moduleDir, "classes-functionalTest")).isEmpty();
  }

  @Test
  void toLaunchData_classpathFork_excludesOwnJar() {
    final Path ownJar = Path.of("/repo/lathe-junit.jar");
    final String classPath =
        String.join(File.pathSeparator, "/repo/lathe-junit.jar", "/repo/junit.jar");

    final var data =
        LaunchCapture.toLaunchData("/jdk", classPath, List.of("-Dfoo=bar"), ownJar, "");

    assertThat(data.mode()).isEqualTo(LaunchMode.CLASSPATH);
    assertThat(data.classPath()).containsExactly(Path.of("/repo/junit.jar").toString());
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
            null,
            "");

    assertThat(data.mode()).isEqualTo(LaunchMode.MODULE);
    assertThat(data.mainModule()).isEqualTo("com.example.app");
    assertThat(data.modulePath())
        .containsExactly(Path.of("/mods/a.jar").toString(), Path.of("/mods/b.jar").toString());
    assertThat(data.patchModules())
        .containsEntry("com.example.app", "/workspace/app/target/test-classes");
  }

  // A Gradle test worker's launch, with this jar loaded as an agent: the worker jar, the agent and
  // Gradle's own properties go, and so does the capture-only switch a Lathe sync gives the test
  // task (a replay runs the tests); what the build set stays, its doubled separators collapsed.
  @Test
  void toLaunchData_gradleWorkerWithAgent_keepsOnlyTheBuildsLaunch() {
    final Path ownJar = Path.of("/repo/lathe-junit.jar");
    final String worker = "/gradle/caches/9.8.1/workerMain/gradle-worker.jar";

    final var data =
        LaunchCapture.toLaunchData(
            "/jdk",
            String.join(File.pathSeparator, worker, "/app//build/classes/java/test"),
            List.of(
                "-javaagent:/repo/lathe-junit.jar",
                "-Dorg.gradle.internal.worker.tmpdir=/app/build/tmp/test/work",
                "-Dlathe.capture.only=true",
                "--module-path=%s%s/mods/opentest4j.jar".formatted(worker, File.pathSeparator),
                "-Xmx512m",
                "-ea",
                "-Djdk.module.main=gradle.worker"),
            ownJar,
            "app");

    assertThat(data.classPath())
        .containsExactly(Path.of("/app/build/classes/java/test").toString());
    assertThat(data.modulePath()).containsExactly(Path.of("/mods/opentest4j.jar").toString());
    assertThat(data.jvmArgs()).containsExactly("-Xmx512m", "-ea");
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
            null,
            "");

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
            "/jdk", "", List.of("-Xmx512m", "-javaagent:/agent.jar", "--enable-preview"), null, "");

    assertThat(data.jvmArgs())
        .containsExactly("-Xmx512m", "-javaagent:/agent.jar", "--enable-preview");
  }
}
