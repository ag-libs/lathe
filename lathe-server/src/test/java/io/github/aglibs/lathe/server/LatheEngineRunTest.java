package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.analysis.RunTarget;
import io.github.aglibs.lathe.analysis.RunnableKind;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.server.LatheEngine.TestScope;
import io.github.aglibs.lathe.server.engine.LatheTestRun;
import io.github.aglibs.lathe.server.run.LaunchOutcome;
import io.github.aglibs.lathe.server.run.TestResult;
import io.github.aglibs.lathe.server.run.TranscriptLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

// run_test: picking what to run, why nothing ran, and the result an agent reads.
class LatheEngineRunTest extends LatheEngineTestBase {
  // The scan lists a nested class before its enclosing one; running the class runs the outermost,
  // which JUnit runs with its @Nested classes, and every top-level class of the file, not only the
  // first. A method name runs in every class declaring it, nested or top-level.
  @Test
  void selectTargets_nestedAndSecondTopLevelClass_runsEveryOutermostClassAndEveryMethodOfTheName() {
    final List<RunTarget> targets =
        List.of(
            target("demo.Outer$Inner#run()", RunnableKind.TEST_METHOD, "run"),
            target("demo.Outer$Inner", RunnableKind.TEST_CLASS, "Inner"),
            target("demo.Outer#run()", RunnableKind.TEST_METHOD, "run"),
            target("demo.Outer", RunnableKind.TEST_CLASS, "Outer"),
            target("demo.Second", RunnableKind.TEST_CLASS, "Second"),
            target("demo.Second#run()", RunnableKind.TEST_METHOD, "run"));
    final Path file = tmp.resolve("Outer.java");

    assertThat(LatheEngine.selectTargets(targets, TestScope.CLASS, null, file))
        .extracting(RunTarget::id)
        .containsExactly("demo.Outer", "demo.Second");
    assertThat(LatheEngine.selectTargets(targets, TestScope.METHOD, "run", file))
        .extracting(RunTarget::id)
        .containsExactly("demo.Outer$Inner#run()", "demo.Outer#run()", "demo.Second#run()");
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

    assertThatThrownBy(
            () -> engine.runTest(sourceNamed(sources, "Shop"), TestScope.CLASS, null).value())
        .as("a location's path, written with / on every OS")
        .hasMessage(
            "no test in Shop.java; its test class may be module/src/main/java/com/example/ShopTest.java");
    assertThatThrownBy(
            () -> engine.runTest(sourceNamed(sources, "Lone"), TestScope.CLASS, null).value())
        .hasMessage("no test in Lone.java");
    assertThatThrownBy(() -> engine.runTest(outsideTheBuild, TestScope.CLASS, null).value())
        .hasMessageContaining("is outside the modules the last build compiled");

    Files.writeString(
        sourceNamed(sources, "Lone"), "package com.example; class Lone { Missing m; }");
    assertThatThrownBy(
            () -> engine.runTest(sourceNamed(sources, "Shop"), TestScope.CLASS, null).value())
        .as("another file of its module does not compile")
        .hasMessageStartingWith("Shop.java was not run: code it runs does not compile: ")
        .hasMessageContaining("Lone.java")
        .hasMessageContaining("cannot find symbol");
  }

  // A class the test uses deleted, the test unchanged: no build compile failed, but the test no
  // longer compiles, so it is not run on the old classes, where it would only fail at runtime.
  @Test
  void runTest_classItUsesDeleted_notRunNamingItsError() throws Exception {
    final List<Path> sources =
        TestCompiler.writeMirroredModule(
            tmp,
            "module",
            Map.of(
                "com/example/Edge.java", "package com.example; class Edge {}",
                "com/example/EdgeTest.java", "package com.example;\nclass EdgeTest { Edge e; }\n"),
            List.of(),
            1_000L);
    engine = new LatheEngine(tmp);
    final Path test = sourceNamed(sources, "EdgeTest");
    assertThatThrownBy(() -> engine.runTest(test, TestScope.CLASS, null).value())
        .as("compiling, it is looked at for tests")
        .hasMessage("no test in EdgeTest.java");

    Files.delete(sourceNamed(sources, "Edge"));

    assertThatThrownBy(() -> engine.runTest(test, TestScope.CLASS, null).value())
        .hasMessageStartingWith(
            "EdgeTest.java was not run: code it runs does not compile: "
                + "module/src/main/java/com/example/EdgeTest.java:2: ")
        .hasMessageContaining("cannot find symbol");
  }

  // A class the test uses changed so it still compiles but the unchanged test does not: refused
  // too, by the test's own error.
  @Test
  void runTest_classItUsesChangedUnderIt_notRunNamingItsError() throws Exception {
    final List<Path> sources =
        TestCompiler.writeMirroredModule(
            tmp,
            "module",
            Map.of(
                "com/example/Edge.java",
                    "package com.example; class Edge { int weight() { return 1; } }",
                "com/example/EdgeTest.java",
                    "package com.example;\nclass EdgeTest { int w = new Edge().weight(); }\n"),
            List.of(),
            1_000L);
    engine = new LatheEngine(tmp);
    final Path test = sourceNamed(sources, "EdgeTest");

    writeNewer(
        sourceNamed(sources, "Edge"),
        "package com.example; class Edge { int cost() { return 1; } }");

    assertThatThrownBy(() -> engine.runTest(test, TestScope.CLASS, null).value())
        .hasMessageStartingWith(
            "EdgeTest.java was not run: code it runs does not compile: "
                + "module/src/main/java/com/example/EdgeTest.java:2: ")
        .hasMessageContaining("cannot find symbol");
  }

  // Lathe's error for a package that is not its directory is not javac's: the build compiles and
  // runs such a test, so run_test does not refuse it.
  @Test
  void runTest_packageNotItsDirectory_notRefused() throws Exception {
    final List<Path> sources =
        TestCompiler.writeMirroredModule(
            tmp,
            "module",
            Map.of("com/example/MisplacedTest.java", "package com.other; class MisplacedTest {}"),
            List.of(),
            1_000L);
    engine = new LatheEngine(tmp);

    assertThatThrownBy(
            () ->
                engine
                    .runTest(sourceNamed(sources, "MisplacedTest"), TestScope.CLASS, null)
                    .value())
        .hasMessage("no test in MisplacedTest.java");
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

    // The launch's own note, among the command lines the editor shows, reaches the agent as a note.
    final List<TranscriptLine> printed =
        Stream.concat(
                Stream.of(
                    new TranscriptLine(TranscriptLine.Stream.COMMAND, "java -cp a"),
                    new TranscriptLine(
                        TranscriptLine.Stream.COMMAND,
                        TranscriptLine.NOTE_PREFIX + "JAVA_TOOL_OPTIONS not applied")),
                IntStream.range(0, 45)
                    .mapToObj(
                        i ->
                            new TranscriptLine(
                                TranscriptLine.Stream.STDERR, "line %d".formatted(i))))
            .toList();
    final LatheTestRun nothing =
        LatheTestRun.from(LaunchOutcome.completed(1, printed, List.of()), List.of("a.FooTest"));

    assertThat(nothing.total()).isZero();
    assertThat(nothing.exitCode()).isEqualTo(1);
    assertThat(nothing.output()).hasSize(40).startsWith("line 5").endsWith("line 44");
    assertThat(nothing.notes()).containsExactly("JAVA_TOOL_OPTIONS not applied");
  }

  // A JVM that failed before any test (classes too new for it) prints one long stack trace: its
  // last
  // lines are frames, so the exception and its cause, which say why, keep their first frame and
  // the rest go. Output within the limit stays whole.
  @Test
  void latheTestRun_nothingRanWithALongStackTrace_keepsTheExceptionAndItsCause() {
    final List<String> trace =
        Stream.of(
                Stream.of(
                    "Exception in thread \"main\" org.junit.platform.commons.JUnitException: load failed",
                    "\tat org.junit.Loader.load(Loader.java:10)"),
                IntStream.range(0, 40)
                    .mapToObj("\tat org.junit.Frame.call%d(Frame.java:1)"::formatted),
                Stream.of(
                    "Caused by: java.lang.UnsupportedClassVersionError: a/FooTest has been compiled by a more recent version",
                    "\tat java.lang.ClassLoader.defineClass1(Native Method)",
                    "\tat java.lang.ClassLoader.defineClass(ClassLoader.java:1)",
                    "\t... 41 more"))
            .flatMap(lines -> lines)
            .toList();

    final LatheTestRun run =
        LatheTestRun.from(
            LaunchOutcome.completed(1, stderr(trace), List.of()), List.of("a.FooTest"));
    final LatheTestRun shortRun =
        LatheTestRun.from(
            LaunchOutcome.completed(1, stderr(trace.subList(0, 5)), List.of()),
            List.of("a.FooTest"));

    assertThat(run.output())
        .containsExactly(
            "Exception in thread \"main\" org.junit.platform.commons.JUnitException: load failed",
            "\tat org.junit.Loader.load(Loader.java:10)",
            "Caused by: java.lang.UnsupportedClassVersionError: a/FooTest has been compiled by a more recent version",
            "\tat java.lang.ClassLoader.defineClass1(Native Method)");
    assertThat(shortRun.output()).isEqualTo(trace.subList(0, 5));
  }

  private static List<TranscriptLine> stderr(final List<String> lines) {
    return lines.stream()
        .map(line -> new TranscriptLine(TranscriptLine.Stream.STDERR, line))
        .toList();
  }
}
