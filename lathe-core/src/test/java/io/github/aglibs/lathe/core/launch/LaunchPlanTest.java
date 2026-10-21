package io.github.aglibs.lathe.core.launch;

import static io.github.aglibs.lathe.core.launch.TestPaths.os;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LaunchPlanTest {

  private static final Path WORKSPACE = Path.of("/workspace");
  // The output map a front-end would write for module "app".
  private static final ReactorRewrite REWRITE = appRewrite(WORKSPACE);

  @TempDir private Path workspace;

  @Test
  void forTest_capturedTemplate_buildsRunnerCommand() {
    final var runner = Path.of("/cache/lathe-test-runner.jar");
    final var resultsSink = Path.of("/tmp/lathe-results.ndjson");
    final var data =
        new TestLaunchData(
            "surefire",
            LaunchMode.MODULE,
            os("/jdk"),
            "com.example.app",
            List.of(os("/workspace/app/target/classes")),
            List.of(os("/workspace/app/target/test-classes"), os("/m2/junit.jar")),
            Map.of("com.example.app", os("/workspace/app/target/test-classes")),
            List.of("com.example.app/com.example.app=ALL-UNNAMED"),
            List.of("com.example.app=ALL-UNNAMED"),
            List.of(),
            List.of("ALL-MODULE-PATH"),
            List.of("-Dfoo=bar"),
            "");

    final List<String> args =
        LaunchPlan.forTest(
            data,
            REWRITE,
            List.of(runner),
            List.of(new TestSelection(TestSelectionKind.METHOD, "com.example.app.HelloTest#greet")),
            resultsSink,
            LaunchOverlay.NONE,
            JdwpOptions.NONE);

    assertThat(args)
        .containsExactly(
            os("/jdk/bin/java"),
            "-Dfoo=bar",
            "-D%s=%s".formatted(LatheFlags.RESULTS_SINK, resultsSink),
            "--module-path",
            os("/workspace/.lathe/app/classes"),
            "--class-path",
            String.join(
                File.pathSeparator,
                os("/workspace/.lathe/app/test-classes"),
                os("/m2/junit.jar"),
                os("/cache/lathe-test-runner.jar")),
            "--patch-module",
            "com.example.app=%s".formatted(os("/workspace/.lathe/app/test-classes")),
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
            "surefire",
            LaunchMode.MODULE,
            os("/jdk"),
            "com.example.app",
            List.of(os("/workspace/app/target/classes")),
            List.of(os("/workspace/app/target/test-classes")),
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "");

    final List<String> args =
        LaunchPlan.forTest(
            data,
            REWRITE,
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
            LaunchMode.MODULE,
            os("/jdk"),
            "com.example.app",
            List.of(os("/workspace/app/target/classes")),
            List.of(os("/m2/lib.jar")),
            List.of(),
            List.of(),
            List.of(),
            List.of("ALL-MODULE-PATH"),
            List.of("-Xmx512m"),
            "");

    final List<String> args =
        LaunchPlan.forMain(
            data,
            REWRITE,
            "com.example.app.Main",
            new LaunchOverlay(List.of(), List.of("--port", "8080"), List.of(), List.of()),
            JdwpOptions.NONE);

    assertThat(args)
        .containsExactly(
            os("/jdk/bin/java"),
            "-Xmx512m",
            "--module-path",
            os("/workspace/.lathe/app/classes"),
            "--class-path",
            os("/m2/lib.jar"),
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
            LaunchMode.CLASSPATH,
            os("/jdk"),
            "",
            List.of(),
            List.of(os("/workspace/app/target/classes"), os("/m2/lib.jar")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "");

    final List<String> args =
        LaunchPlan.forMain(
            data, REWRITE, "com.example.app.Main", LaunchOverlay.NONE, JdwpOptions.NONE);

    assertThat(args)
        .containsExactly(
            os("/jdk/bin/java"),
            "--class-path",
            String.join(File.pathSeparator, os("/workspace/.lathe/app/classes"), os("/m2/lib.jar")),
            "com.example.app.Main");
  }

  @Test
  void forMain_overlay_appendsJvmArgsClasspathAndProgramArgs() {
    final var data =
        new MainLaunchData(
            LaunchMode.CLASSPATH,
            os("/jdk"),
            "",
            List.of(),
            List.of(os("/workspace/app/target/classes")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("-Xmx256m"),
            "");
    final var overlay =
        new LaunchOverlay(
            List.of("-Dprofile=prod"), List.of("--flag"), List.of(os("/abs/config")), List.of());

    final List<String> args =
        LaunchPlan.forMain(data, REWRITE, "com.example.app.Main", overlay, JdwpOptions.NONE);

    assertThat(args)
        .containsExactly(
            os("/jdk/bin/java"),
            "-Xmx256m",
            "-Dprofile=prod",
            "--class-path",
            String.join(File.pathSeparator, os("/workspace/.lathe/app/classes"), os("/abs/config")),
            "com.example.app.Main",
            "--flag");
  }

  @Test
  void forTest_overlay_appendsJvmArgsPathsAfterDerivedAndProgramArgs() {
    final var runner = Path.of("/cache/lathe-test-runner.jar");
    final var resultsSink = Path.of("/tmp/lathe-results.ndjson");
    final var data =
        new TestLaunchData(
            "surefire",
            LaunchMode.MODULE,
            os("/jdk"),
            "com.example.app",
            List.of(os("/workspace/app/target/classes")),
            List.of(os("/workspace/app/target/test-classes")),
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("-Dfoo=bar"),
            "");
    final var overlay =
        new LaunchOverlay(
            List.of("-Dfoo=baz"),
            List.of("extra-arg"),
            List.of(os("/abs/cp")),
            List.of(os("/abs/mp")));

    final List<String> args =
        LaunchPlan.forTest(
            data,
            REWRITE,
            List.of(runner),
            List.of(new TestSelection(TestSelectionKind.CLASS, "com.example.app.FooTest")),
            resultsSink,
            overlay,
            JdwpOptions.NONE);

    assertThat(args)
        .containsExactly(
            os("/jdk/bin/java"),
            "-Dfoo=bar",
            "-Dfoo=baz",
            "-D%s=%s".formatted(LatheFlags.RESULTS_SINK, resultsSink),
            "--module-path",
            String.join(File.pathSeparator, os("/workspace/.lathe/app/classes"), os("/abs/mp")),
            "--class-path",
            String.join(
                File.pathSeparator,
                os("/workspace/.lathe/app/test-classes"),
                os("/cache/lathe-test-runner.jar"),
                os("/abs/cp")),
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
            "surefire",
            LaunchMode.MODULE,
            os("/jdk"),
            "com.example.app",
            List.of(os("/workspace/app/target/classes")),
            List.of(os("/workspace/app/target/test-classes")),
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "");

    final List<String> args =
        LaunchPlan.forTest(
            data,
            REWRITE,
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
            "surefire",
            LaunchMode.MODULE,
            os("/jdk"),
            "com.example.app",
            List.of(os("/workspace/app/target/classes")),
            List.of(os("/workspace/app/target/test-classes")),
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "");

    final List<String> args =
        LaunchPlan.forTest(
            data,
            REWRITE,
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
            LaunchMode.CLASSPATH,
            os("/jdk"),
            "",
            List.of(),
            List.of(os("/workspace/app/target/classes")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "");

    final List<String> args =
        LaunchPlan.forMain(
            data, REWRITE, "com.example.app.Main", LaunchOverlay.NONE, new JdwpOptions(5005));

    final String agent =
        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005";
    assertThat(args).contains(agent);
    assertThat(args.indexOf(agent)).isLessThan(args.indexOf("com.example.app.Main"));
  }

  @Test
  void forTestMain_modularTemplate_patchesTestClassesAndRunsMainNotRunner() {
    final var data =
        new TestLaunchData(
            "surefire",
            LaunchMode.MODULE,
            os("/jdk"),
            "com.example.app",
            List.of(os("/workspace/app/target/classes")),
            List.of(os("/m2/junit.jar")),
            Map.of("com.example.app", os("/workspace/app/target/test-classes")),
            List.of("com.example.app/com.example.app=ALL-UNNAMED"),
            List.of("com.example.app=ALL-UNNAMED"),
            List.of(),
            List.of("ALL-MODULE-PATH"),
            List.of("-Dfoo=bar"),
            "");

    final List<String> args =
        LaunchPlan.forTestMain(
            data, REWRITE, "com.example.app.tool.Tester", LaunchOverlay.NONE, JdwpOptions.NONE);

    assertThat(args)
        .containsExactly(
            os("/jdk/bin/java"),
            "-Dfoo=bar",
            "--module-path",
            os("/workspace/.lathe/app/classes"),
            "--class-path",
            os("/m2/junit.jar"),
            "--patch-module",
            "com.example.app=%s".formatted(os("/workspace/.lathe/app/test-classes")),
            "--add-opens",
            "com.example.app/com.example.app=ALL-UNNAMED",
            "--add-reads",
            "com.example.app=ALL-UNNAMED",
            "--add-modules",
            "ALL-MODULE-PATH",
            "-m",
            "com.example.app/com.example.app.tool.Tester");
    assertThat(args).doesNotContain(LatheLayout.TEST_RUNNER_MAIN_CLASS);
    assertThat(args).noneMatch(arg -> arg.startsWith("-D" + LatheFlags.RESULTS_SINK));
  }

  @Test
  void forTestMain_classpathTemplate_runsMainOnTestClasspath() {
    final var data =
        new TestLaunchData(
            "surefire",
            LaunchMode.CLASSPATH,
            os("/jdk"),
            "",
            List.of(),
            List.of(os("/workspace/app/target/test-classes"), os("/m2/junit.jar")),
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "");

    final List<String> args =
        LaunchPlan.forTestMain(
            data, REWRITE, "com.example.app.Tester", LaunchOverlay.NONE, JdwpOptions.NONE);

    assertThat(args)
        .containsExactly(
            os("/jdk/bin/java"),
            "--class-path",
            String.join(
                File.pathSeparator, os("/workspace/.lathe/app/test-classes"), os("/m2/junit.jar")),
            "com.example.app.Tester");
  }

  @Test
  void forTestMain_debug_emitsSuspendedJdwpAgentBeforeEntry() {
    final var data =
        new TestLaunchData(
            "surefire",
            LaunchMode.MODULE,
            os("/jdk"),
            "com.example.app",
            List.of(os("/workspace/app/target/classes")),
            List.of(),
            Map.of("com.example.app", os("/workspace/app/target/test-classes")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "");

    final List<String> args =
        LaunchPlan.forTestMain(
            data, REWRITE, "com.example.app.Tester", LaunchOverlay.NONE, new JdwpOptions(5005));

    final String agent =
        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005";
    assertThat(args).contains(agent);
    assertThat(args.indexOf(agent)).isLessThan(args.indexOf("-m"));
  }

  @Test
  void forMain_noDebug_omitsJdwpAgent() {
    final var data =
        new MainLaunchData(
            LaunchMode.CLASSPATH,
            os("/jdk"),
            "",
            List.of(),
            List.of(os("/workspace/app/target/classes")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "");

    final List<String> args =
        LaunchPlan.forMain(
            data, REWRITE, "com.example.app.Main", LaunchOverlay.NONE, JdwpOptions.NONE);

    assertThat(args).noneMatch(arg -> arg.startsWith("-agentlib:jdwp"));
  }

  @Test
  void forTest_partialCaptureAndNewPackage_derivesCompleteAddOpensAtReplay() throws IOException {
    writeClass("app", "com/example/app/AppTest.class");
    writeClass("app", "com/example/app/extra/ExtraTest.class");

    final var runner = Path.of("/cache/lathe-test-runner.jar");
    final var resultsSink = Path.of("/tmp/lathe-results.ndjson");
    final var testClasses = workspace.resolve("app/target/test-classes").toString();
    final var data =
        new TestLaunchData(
            "surefire",
            LaunchMode.MODULE,
            os("/jdk"),
            "com.example.app",
            List.of(workspace.resolve("app/target/classes").toString()),
            List.of(testClasses),
            Map.of("com.example.app", testClasses),
            List.of(
                "com.example.app/com.example.app=ALL-UNNAMED", "java.base/java.lang=ALL-UNNAMED"),
            List.of("com.example.app=ALL-UNNAMED"),
            List.of(),
            List.of("ALL-MODULE-PATH"),
            List.of(),
            "");

    final List<String> args =
        LaunchPlan.forTest(
            data,
            appRewrite(workspace),
            List.of(runner),
            List.of(new TestSelection(TestSelectionKind.CLASS, "com.example.app.AppTest")),
            resultsSink,
            LaunchOverlay.NONE,
            JdwpOptions.NONE);

    assertThat(args)
        .contains(
            "com.example.app/com.example.app=ALL-UNNAMED",
            "com.example.app/com.example.app.extra=ALL-UNNAMED",
            "java.base/java.lang=ALL-UNNAMED");
  }

  @Test
  void forTestMain_newPackageInTestClasses_derivesAddOpensAtReplay() throws IOException {
    writeClass("app", "com/example/app/AppTest.class");
    writeClass("app", "com/example/app/extra/ExtraTest.class");

    final var testClasses = workspace.resolve("app/target/test-classes").toString();
    final var data =
        new TestLaunchData(
            "surefire",
            LaunchMode.MODULE,
            os("/jdk"),
            "com.example.app",
            List.of(workspace.resolve("app/target/classes").toString()),
            List.of(os("/m2/junit.jar")),
            Map.of("com.example.app", testClasses),
            List.of(),
            List.of("com.example.app=ALL-UNNAMED"),
            List.of(),
            List.of("ALL-MODULE-PATH"),
            List.of(),
            "");

    final List<String> args =
        LaunchPlan.forTestMain(
            data,
            appRewrite(workspace),
            "com.example.app.tool.Tester",
            LaunchOverlay.NONE,
            JdwpOptions.NONE);

    assertThat(args)
        .contains(
            "com.example.app/com.example.app=ALL-UNNAMED",
            "com.example.app/com.example.app.extra=ALL-UNNAMED");
  }

  private static ReactorRewrite appRewrite(final Path root) {
    return ReactorRewrite.of(
        root,
        List.of(
            new OutputData("app/target/classes", "app", LatheLayout.CLASSES_DIR),
            new OutputData("app/target/test-classes", "app", LatheLayout.TEST_CLASSES_DIR)));
  }

  private void writeClass(final String moduleDir, final String relClassPath) throws IOException {
    final Path file =
        workspace
            .resolve(".lathe")
            .resolve(moduleDir)
            .resolve("test-classes")
            .resolve(relClassPath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "");
  }
}
