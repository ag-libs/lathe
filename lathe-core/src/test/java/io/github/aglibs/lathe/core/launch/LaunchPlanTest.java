package io.github.aglibs.lathe.core.launch;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class LaunchPlanTest {

  private static final Path WORKSPACE = Path.of("/workspace");

  @Test
  void forTest_capturedTemplate_buildsRunnerCommand() {
    final var runner = Path.of("/cache/lathe-test-runner.jar");
    final var resultsSink = Path.of("/tmp/lathe-results.ndjson");
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
        LaunchPlan.forTest(
            data,
            WORKSPACE,
            List.of(runner),
            List.of(new TestSelection(TestSelectionKind.METHOD, "com.example.app.HelloTest#greet")),
            resultsSink,
            LaunchOverlay.NONE,
            JdwpOptions.NONE);

    assertThat(args)
        .containsExactly(
            "/jdk/bin/java",
            "-Dfoo=bar",
            "-D%s=%s".formatted(LatheFlags.RESULTS_SINK, resultsSink),
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
  void forTest_multipleSelections_appendsEachSelectorAfterTheMainClass() {
    final var runner = Path.of("/cache/lathe-test-runner.jar");
    final var resultsSink = Path.of("/tmp/lathe-results.ndjson");
    final var data =
        new TestLaunchData(
            "1",
            "surefire",
            LaunchMode.MODULE,
            "/jdk",
            "com.example.app",
            List.of("/workspace/app/target/classes"),
            List.of("/workspace/app/target/test-classes"),
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    final List<String> args =
        LaunchPlan.forTest(
            data,
            WORKSPACE,
            List.of(runner),
            List.of(
                new TestSelection(TestSelectionKind.CLASS, "com.example.app.FooTest"),
                new TestSelection(TestSelectionKind.CLASS, "com.example.app.BarTest")),
            resultsSink,
            LaunchOverlay.NONE,
            JdwpOptions.NONE);

    assertThat(args)
        .endsWith(
            LatheLayout.TEST_RUNNER_MAIN_CLASS,
            "--select-class",
            "com.example.app.FooTest",
            "--select-class",
            "com.example.app.BarTest");
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
        LaunchPlan.forMain(
            data,
            WORKSPACE,
            "com.example.app.Main",
            new LaunchOverlay(List.of(), List.of("--port", "8080"), List.of(), List.of()),
            JdwpOptions.NONE);

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
        LaunchPlan.forMain(
            data, WORKSPACE, "com.example.app.Main", LaunchOverlay.NONE, JdwpOptions.NONE);

    assertThat(args)
        .containsExactly(
            "/jdk/bin/java",
            "--class-path",
            String.join(File.pathSeparator, "/workspace/.lathe/app/classes", "/m2/lib.jar"),
            "com.example.app.Main");
  }

  @Test
  void forMain_overlay_appendsJvmArgsClasspathAndProgramArgs() {
    final var data =
        new MainLaunchData(
            "1",
            LaunchMode.CLASSPATH,
            "/jdk",
            "",
            List.of(),
            List.of("/workspace/app/target/classes"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("-Xmx256m"));
    final var overlay =
        new LaunchOverlay(
            List.of("-Dprofile=prod"), List.of("--flag"), List.of("/abs/config"), List.of());

    final List<String> args =
        LaunchPlan.forMain(data, WORKSPACE, "com.example.app.Main", overlay, JdwpOptions.NONE);

    assertThat(args)
        .containsExactly(
            "/jdk/bin/java",
            "-Xmx256m",
            "-Dprofile=prod",
            "--class-path",
            String.join(File.pathSeparator, "/workspace/.lathe/app/classes", "/abs/config"),
            "com.example.app.Main",
            "--flag");
  }

  @Test
  void forTest_overlay_appendsJvmArgsPathsAfterDerivedAndProgramArgs() {
    final var runner = Path.of("/cache/lathe-test-runner.jar");
    final var resultsSink = Path.of("/tmp/lathe-results.ndjson");
    final var data =
        new TestLaunchData(
            "1",
            "surefire",
            LaunchMode.MODULE,
            "/jdk",
            "com.example.app",
            List.of("/workspace/app/target/classes"),
            List.of("/workspace/app/target/test-classes"),
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("-Dfoo=bar"));
    final var overlay =
        new LaunchOverlay(
            List.of("-Dfoo=baz"), List.of("extra-arg"), List.of("/abs/cp"), List.of("/abs/mp"));

    final List<String> args =
        LaunchPlan.forTest(
            data,
            WORKSPACE,
            List.of(runner),
            List.of(new TestSelection(TestSelectionKind.CLASS, "com.example.app.FooTest")),
            resultsSink,
            overlay,
            JdwpOptions.NONE);

    assertThat(args)
        .containsExactly(
            "/jdk/bin/java",
            "-Dfoo=bar",
            "-Dfoo=baz",
            "-D%s=%s".formatted(LatheFlags.RESULTS_SINK, resultsSink),
            "--module-path",
            String.join(File.pathSeparator, "/workspace/.lathe/app/classes", "/abs/mp"),
            "--class-path",
            String.join(
                File.pathSeparator,
                "/workspace/.lathe/app/test-classes",
                "/cache/lathe-test-runner.jar",
                "/abs/cp"),
            LatheLayout.TEST_RUNNER_MAIN_CLASS,
            "--select-class",
            "com.example.app.FooTest",
            "extra-arg");
  }

  @Test
  void forTest_debug_emitsSuspendedJdwpAgentBeforeRunner() {
    final var runner = Path.of("/cache/lathe-test-runner.jar");
    final var resultsSink = Path.of("/tmp/lathe-results.ndjson");
    final var data =
        new TestLaunchData(
            "1",
            "surefire",
            LaunchMode.MODULE,
            "/jdk",
            "com.example.app",
            List.of("/workspace/app/target/classes"),
            List.of("/workspace/app/target/test-classes"),
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    final List<String> args =
        LaunchPlan.forTest(
            data,
            WORKSPACE,
            List.of(runner),
            List.of(new TestSelection(TestSelectionKind.CLASS, "com.example.app.FooTest")),
            resultsSink,
            LaunchOverlay.NONE,
            new JdwpOptions(5005));

    final String agent =
        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005";
    assertThat(args).contains(agent);
    assertThat(args.indexOf(agent)).isLessThan(args.indexOf(LatheLayout.TEST_RUNNER_MAIN_CLASS));
  }

  @Test
  void forTest_noDebug_omitsJdwpAgent() {
    final var runner = Path.of("/cache/lathe-test-runner.jar");
    final var resultsSink = Path.of("/tmp/lathe-results.ndjson");
    final var data =
        new TestLaunchData(
            "1",
            "surefire",
            LaunchMode.MODULE,
            "/jdk",
            "com.example.app",
            List.of("/workspace/app/target/classes"),
            List.of("/workspace/app/target/test-classes"),
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    final List<String> args =
        LaunchPlan.forTest(
            data,
            WORKSPACE,
            List.of(runner),
            List.of(new TestSelection(TestSelectionKind.CLASS, "com.example.app.FooTest")),
            resultsSink,
            LaunchOverlay.NONE,
            JdwpOptions.NONE);

    assertThat(args).noneMatch(arg -> arg.startsWith("-agentlib:jdwp"));
  }

  @Test
  void forMain_debug_emitsSuspendedJdwpAgentBeforeMainClass() {
    final var data =
        new MainLaunchData(
            "1",
            LaunchMode.CLASSPATH,
            "/jdk",
            "",
            List.of(),
            List.of("/workspace/app/target/classes"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    final List<String> args =
        LaunchPlan.forMain(
            data, WORKSPACE, "com.example.app.Main", LaunchOverlay.NONE, new JdwpOptions(5005));

    final String agent =
        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005";
    assertThat(args).contains(agent);
    assertThat(args.indexOf(agent)).isLessThan(args.indexOf("com.example.app.Main"));
  }

  @Test
  void forMain_noDebug_omitsJdwpAgent() {
    final var data =
        new MainLaunchData(
            "1",
            LaunchMode.CLASSPATH,
            "/jdk",
            "",
            List.of(),
            List.of("/workspace/app/target/classes"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    final List<String> args =
        LaunchPlan.forMain(
            data, WORKSPACE, "com.example.app.Main", LaunchOverlay.NONE, JdwpOptions.NONE);

    assertThat(args).noneMatch(arg -> arg.startsWith("-agentlib:jdwp"));
  }
}
