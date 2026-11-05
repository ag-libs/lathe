package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.server.LatheEngine.TestScope;
import io.github.aglibs.lathe.server.engine.LatheTestRun;
import io.github.aglibs.lathe.server.run.LaunchOutcome;
import io.github.aglibs.lathe.server.run.RunTarget;
import io.github.aglibs.lathe.server.run.RunnableKind;
import io.github.aglibs.lathe.server.run.TestResult;
import io.github.aglibs.lathe.server.run.TranscriptLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

// run_test: picking what to run, why nothing ran, and the result an agent reads.
class LatheEngineRunTest extends LatheEngineTestBase {
  // The scan lists a nested class before its enclosing one; running the class runs the outermost,
  // which JUnit runs with its @Nested classes, and every top-level class of the file, not only the
  // first. A method in the outer class wins a name clash.
  @Test
  void selectTargets_nestedAndSecondTopLevelClass_runsEveryOutermostClassAndOneMethod() {
    final List<RunTarget> targets =
        List.of(
            target("demo.Outer$Inner#run()", RunnableKind.TEST_METHOD, "run"),
            target("demo.Outer$Inner", RunnableKind.TEST_CLASS, "Inner"),
            target("demo.Outer#run()", RunnableKind.TEST_METHOD, "run"),
            target("demo.Outer", RunnableKind.TEST_CLASS, "Outer"),
            target("demo.Second", RunnableKind.TEST_CLASS, "Second"));
    final Path file = tmp.resolve("Outer.java");

    assertThat(LatheEngine.selectTargets(targets, TestScope.CLASS, null, file))
        .extracting(RunTarget::id)
        .containsExactly("demo.Outer", "demo.Second");
    assertThat(LatheEngine.selectTargets(targets, TestScope.METHOD, "run", file))
        .extracting(RunTarget::id)
        .containsExactly("demo.Outer#run()");
    assertThatThrownBy(() -> LatheEngine.selectTargets(targets, TestScope.METHOD, "missing", file))
        .hasMessage("no test method 'missing' in Outer.java");
  }

  // A file with no test says why: its module is outside the build, or it is the class under test,
  // and then its test class is named; one that does not compile is not run on its old classes.
  @Test
  void runTest_fileWithNoTestOrNotCompiling_saysWhy() throws Exception {
    final List<Path> sources =
        TestCompiler.writeMirroredModule(
            tmp,
            "module",
            Map.of(
                "com/example/Shop.java", "package com.example; class Shop {}",
                "com/example/ShopTest.java", "package com.example; class ShopTest {}",
                "com/example/Lone.java", "package com.example; class Lone {}"),
            List.of(),
            1_000L);
    final Path outsideTheBuild =
        TestCompiler.writeAt(
            tmp.resolve("newmodule/src/main/java/com/example/Fresh.java"),
            "package com.example; class Fresh {}",
            1_000L);
    engine = new LatheEngine(tmp);

    assertThatThrownBy(() -> engine.runTest(sourceNamed(sources, "Shop"), TestScope.CLASS, null))
        .as("a location's path, written with / on every OS")
        .hasMessage(
            "no test in Shop.java; its test class may be module/src/main/java/com/example/ShopTest.java");
    assertThatThrownBy(() -> engine.runTest(sourceNamed(sources, "Lone"), TestScope.CLASS, null))
        .hasMessage("no test in Lone.java");
    assertThatThrownBy(() -> engine.runTest(outsideTheBuild, TestScope.CLASS, null))
        .hasMessageContaining("is outside the modules the last build compiled");

    Files.writeString(
        sourceNamed(sources, "Lone"), "package com.example; class Lone { Missing m; }");
    assertThatThrownBy(() -> engine.runTest(sourceNamed(sources, "Shop"), TestScope.CLASS, null))
        .as("another file of its module does not compile")
        .hasMessageStartingWith("Shop.java was not run: code it runs does not compile: ")
        .hasMessageContaining("Lone.java")
        .hasMessageContaining("cannot find symbol");
  }

  private static Path sourceNamed(final List<Path> sources, final String type) {
    return sources.stream()
        .filter(source -> FileUtil.javaTypeName(source).equals(type))
        .findFirst()
        .orElseThrow();
  }

  private static RunTarget target(final String id, final RunnableKind kind, final String label) {
    final var start = new Position(0, 0);
    return new RunTarget(id, "", kind, label, "app", "file:///Outer.java", new Range(start, start));
  }

  @Test
  void latheTestRun_completedOutcome_mapsCountsAndFailureWithItsOutput() {
    final var outcome =
        LaunchOutcome.completed(
            0,
            List.of(
                new TranscriptLine(TranscriptLine.Stream.COMMAND, "java -cp ..."),
                new TranscriptLine(TranscriptLine.Stream.STDOUT, "ok", "passes-id"),
                new TranscriptLine(TranscriptLine.Stream.STDOUT, "edits: [a, b]", "fails-id"),
                new TranscriptLine(TranscriptLine.Stream.STDERR, "WARN retry", "fails-id"),
                new TranscriptLine(TranscriptLine.Stream.STDERR, "setup: no database")),
            List.of(
                new TestResult(
                    "com.example.FooTest",
                    "passes",
                    "",
                    "passed",
                    "",
                    -1,
                    -1,
                    "passes()",
                    null,
                    null,
                    List.of(),
                    null),
                new TestResult(
                    "com.example.FooTest",
                    "fails",
                    "",
                    "failed",
                    "java.lang.AssertionError: boom",
                    42,
                    -1,
                    "fails()",
                    null,
                    null,
                    List.of(),
                    "fails-id"),
                new TestResult(
                    "com.example.FooTest",
                    "skips",
                    "",
                    "skipped",
                    "",
                    -1,
                    -1,
                    "skips()",
                    null,
                    null,
                    List.of(),
                    null),
                // A class whose @BeforeAll broke: its own record, without a method.
                new TestResult(
                    "com.example.BrokenTest",
                    "",
                    "",
                    "failed",
                    "setup exploded",
                    9,
                    -1,
                    "BrokenTest",
                    null,
                    null,
                    List.of(),
                    null)));

    final LatheTestRun run =
        LatheTestRun.from(
            outcome.withNotes(List.of("dep/target/classes-jdk21 runs as the last build left it")),
            List.of("com.example.FooTest"));

    assertThat(run.launched()).isTrue();
    assertThat(run.notes())
        .containsExactly("dep/target/classes-jdk21 runs as the last build left it");
    assertThat(run.ran()).containsExactly("com.example.FooTest");
    assertThat(run.total()).isEqualTo(4);
    assertThat(run.passed()).isEqualTo(1);
    assertThat(run.failed()).isEqualTo(2);
    assertThat(run.skipped()).isEqualTo(1);
    assertThat(run.failures())
        .anySatisfy(
            f -> {
              assertThat(f.test()).isEqualTo("com.example.FooTest#fails");
              assertThat(f.summary()).contains("boom");
              assertThat(f.line()).isEqualTo(42);
              assertThat(f.output()).containsExactly("edits: [a, b]", "WARN retry");
            })
        .anySatisfy(
            f -> {
              assertThat(f.test()).isEqualTo("com.example.BrokenTest");
              assertThat(f.output())
                  .as("printed outside any test")
                  .containsExactly("setup: no database");
            });
  }

  @Test
  void latheTestRun_blockedOrNothingRan_carriesTheReasonOrTheLastOutput() {
    final LatheTestRun run =
        LatheTestRun.from(LaunchOutcome.blocked(List.of("no runner jar")), List.of());

    assertThat(run.launched()).isFalse();
    assertThat(run.blockedReasons()).containsExactly("no runner jar");
    assertThat(run.total()).isZero();

    final List<TranscriptLine> printed =
        IntStream.range(0, 45)
            .mapToObj(i -> new TranscriptLine(TranscriptLine.Stream.STDERR, "line %d".formatted(i)))
            .toList();
    final LatheTestRun nothing =
        LatheTestRun.from(LaunchOutcome.completed(1, printed, List.of()), List.of("a.FooTest"));

    assertThat(nothing.total()).isZero();
    assertThat(nothing.exitCode()).isEqualTo(1);
    assertThat(nothing.output()).hasSize(40).startsWith("line 5").endsWith("line 44");
  }
}
