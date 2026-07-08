package io.github.aglibs.lathe.core.launch;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ReplayTransformTest {

  private static final Path WORKSPACE = Path.of("/workspace");

  @Test
  void forTest_capturedTemplate_buildsRunnerCommand() {
    final var runner = Path.of("/cache/lathe-test-runner.jar");
    final var data =
        new TestLaunchData(
            "1",
            "surefire",
            LaunchMode.MODULE,
            "/jdk",
            "com.example.app",
            List.of("/workspace/app/target/classes"),
            List.of("/workspace/app/target/test-classes", "/m2/junit.jar"),
            Map.of("com.example.app", "/workspace/app/target/test-classes"),
            List.of("com.example.app/com.example.app=ALL-UNNAMED"),
            List.of("com.example.app=ALL-UNNAMED"),
            List.of(),
            List.of("ALL-MODULE-PATH"),
            List.of("-Dfoo=bar"));

    final List<String> args =
        ReplayTransform.forTest(
            data,
            WORKSPACE,
            List.of(runner),
            new TestSelection(TestSelectionKind.METHOD, "com.example.app.HelloTest#greet"));

    assertThat(args)
        .containsExactly(
            "/jdk/bin/java",
            "-Dfoo=bar",
            "--module-path",
            "/workspace/.lathe/app/classes",
            "--class-path",
            String.join(
                File.pathSeparator,
                "/workspace/.lathe/app/test-classes",
                "/m2/junit.jar",
                "/cache/lathe-test-runner.jar"),
            "--patch-module",
            "com.example.app=/workspace/.lathe/app/test-classes",
            "--add-opens",
            "com.example.app/com.example.app=ALL-UNNAMED",
            "--add-reads",
            "com.example.app=ALL-UNNAMED",
            "--add-modules",
            "ALL-MODULE-PATH",
            LatheLayout.TEST_RUNNER_MAIN_CLASS,
            "--select-method",
            "com.example.app.HelloTest#greet");
  }

  @Test
  void forMain_modularTemplate_buildsModuleCommand() {
    final var data =
        new MainLaunchData(
            "1",
            LaunchMode.MODULE,
            "/jdk",
            "com.example.app",
            List.of("/workspace/app/target/classes"),
            List.of("/m2/lib.jar"),
            List.of(),
            List.of(),
            List.of(),
            List.of("ALL-MODULE-PATH"),
            List.of("-Xmx512m"));

    final List<String> args =
        ReplayTransform.forMain(data, WORKSPACE, "com.example.app.Main", List.of("--port", "8080"));

    assertThat(args)
        .containsExactly(
            "/jdk/bin/java",
            "-Xmx512m",
            "--module-path",
            "/workspace/.lathe/app/classes",
            "--class-path",
            "/m2/lib.jar",
            "--add-modules",
            "ALL-MODULE-PATH",
            "-m",
            "com.example.app/com.example.app.Main",
            "--port",
            "8080");
  }

  @Test
  void forMain_classpathTemplate_buildsClasspathCommand() {
    final var data =
        new MainLaunchData(
            "1",
            LaunchMode.CLASSPATH,
            "/jdk",
            "",
            List.of(),
            List.of("/workspace/app/target/classes", "/m2/lib.jar"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    final List<String> args =
        ReplayTransform.forMain(data, WORKSPACE, "com.example.app.Main", List.of());

    assertThat(args)
        .containsExactly(
            "/jdk/bin/java",
            "--class-path",
            String.join(File.pathSeparator, "/workspace/.lathe/app/classes", "/m2/lib.jar"),
            "com.example.app.Main");
  }
}
