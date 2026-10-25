package io.github.aglibs.lathe.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.aglibs.lathe.core.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

final class ResultsListenerTest {

  @Test
  void execute_mixedFixture_recordsEachMethodWithStatusMessageAndLine(@TempDir final Path dir)
      throws IOException {
    final List<JsonObject> records = run(dir, MixedFixture.class);

    assertThat(records).hasSize(4);
    assertThat(recordFor(records, "passing_test").get("status").getAsString()).isEqualTo("passed");
    assertThat(recordFor(records, "passing_test").get("failureLine").getAsInt()).isEqualTo(-1);
    assertThat(recordFor(records, "disabled_test").get("status").getAsString())
        .isEqualTo("skipped");
    assertThat(recordFor(records, "aborted_test").get("status").getAsString()).isEqualTo("skipped");
    final JsonObject failing = recordFor(records, "failing_test");
    assertThat(failing.get("status").getAsString()).isEqualTo("failed");
    assertThat(failing.get("failureMessage").getAsString()).contains("boom failure");
    assertThat(failing.get("failureLine").getAsInt()).isPositive();
  }

  // Duration, display name and a stack cut where JUnit called into the test.
  @Test
  void execute_failingMethod_recordsDurationDisplayNameAndCutStack(@TempDir final Path dir)
      throws IOException {
    final JsonObject failing = recordFor(run(dir, MixedFixture.class), "failing_test");

    assertThat(failing.get("durationMs").getAsLong()).isNotNegative();
    assertThat(failing.get("displayName").getAsString()).isEqualTo("failing_test()");
    final List<JsonObject> frames = objects(failing.getAsJsonArray("stackTrace"));
    assertThat(frames).isNotEmpty();
    assertThat(frames.get(0).get("className").getAsString())
        .isEqualTo(MixedFixture.class.getName());
    assertThat(frames.get(0).get("methodName").getAsString()).isEqualTo("failing_test");
    assertThat(frames.get(0).get("fileName").getAsString()).isEqualTo("ResultsListenerTest.java");
    assertThat(frames)
        .extracting(frame -> frame.get("className").getAsString())
        .noneMatch(className -> className.startsWith("org.junit.platform."))
        .noneMatch(className -> className.startsWith("jdk.internal.reflect."));
  }

  @Test
  void execute_parameterizedFixture_recordsEachInvocationNamedNotTheTemplate(
      @TempDir final Path dir) throws IOException {
    final List<JsonObject> records = run(dir, ParameterizedFixture.class);

    assertThat(records).hasSize(3);
    assertThat(records)
        .filteredOn(record -> record.get("methodName").getAsString().equals("param_test"))
        .extracting(record -> record.get("displayName").getAsString())
        .containsExactly("[1] \"a\"", "[2] \"b\"");
  }

  // The values come from the failure itself, never from its message: a plain fail() has none.
  @Test
  void execute_comparisonFailures_recordExpectedAndActualOnlyWhenTheFailureHasThem(
      @TempDir final Path dir) throws IOException {
    final List<JsonObject> records = run(dir, ComparisonFixture.class);

    assertThat(recordFor(records, "single_line").get("expected").getAsString()).isEqualTo("Alice");
    assertThat(recordFor(records, "single_line").get("actual").getAsString()).isEqualTo("alice");
    assertThat(recordFor(records, "multi_line").get("expected").getAsString())
        .isEqualTo("one\ntwo");
    assertThat(recordFor(records, "multi_line").get("actual").getAsString()).isEqualTo("one\nTWO");
    // AssertJ hands over its own formatted representation, quotes included; kept as it comes.
    assertThat(recordFor(records, "assertj").get("expected").getAsString()).isEqualTo("\"Alice\"");
    assertThat(recordFor(records, "assertj").get("actual").getAsString()).isEqualTo("\"alice\"");
    assertThat(recordFor(records, "no_values").has("expected")).isFalse();
    assertThat(recordFor(records, "no_values").has("actual")).isFalse();
  }

  // A class whose @BeforeAll fails gets a record of its own (empty method name); a parameterized
  // method whose arguments can't be produced gets one on the method. A failing static initializer
  // is the method's failure: JUnit initializes the class when it creates the test instance.
  @Test
  void execute_containerFailures_recordTheClassOrTemplateThatFailed(@TempDir final Path dir)
      throws IOException {
    final JsonObject setup = single(run(dir, BrokenSetupFixture.class));
    final JsonObject init = single(run(dir, BrokenInitFixture.class));
    final JsonObject template = single(run(dir, BrokenTemplateFixture.class));

    assertThat(setup.get("className").getAsString()).isEqualTo(BrokenSetupFixture.class.getName());
    assertThat(setup.get("methodName").getAsString()).isEmpty();
    assertThat(setup.get("status").getAsString()).isEqualTo("failed");
    assertThat(setup.get("failureMessage").getAsString()).contains("setup exploded");
    assertThat(setup.get("failureLine").getAsInt()).isPositive();
    assertThat(init.get("methodName").getAsString()).isEqualTo("never_runs");
    assertThat(init.get("status").getAsString()).isEqualTo("failed");
    assertThat(init.get("failureMessage").getAsString()).contains("ExceptionInInitializerError");
    assertThat(template.get("methodName").getAsString()).isEqualTo("broken_param");
    assertThat(template.get("status").getAsString()).isEqualTo("failed");
  }

  // Markers around each test on stdout, the stream its own output goes to, so the server can tell
  // which test printed a line; containers get none.
  @Test
  void execute_eachTest_printsStartedAndFinishedMarkersAroundItsOutput(@TempDir final Path dir) {
    final var stdout = new ByteArrayOutputStream();
    final var out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
    final var original = System.out;
    System.setOut(out);
    try {
      execute(PrintingFixture.class, new ResultsListener(dir.resolve("sink"), out, System.err));
    } finally {
      System.setOut(original);
    }

    final String fields = "%s\tprints\t".formatted(PrintingFixture.class.getName());
    assertThat(stdout.toString(StandardCharsets.UTF_8).lines().toList())
        .containsExactly(
            ResultsListener.TEST_STARTED_MARKER + fields,
            "printed by the test",
            ResultsListener.TEST_FINISHED_MARKER + fields);
  }

  @Test
  void toJson_specialCharactersAndOptionalValues_singleLineWithAbsentValuesLeftOut() {
    final var frame = new TestRecord.Frame("pkg.C", "m", "C.java", 7);
    final var withValues =
        new TestRecord(
            "pkg.C",
            "m",
            "",
            "failed",
            "he said \"hi\"\nback\\slash\ttab",
            7,
            12,
            "m()",
            "a\nb",
            "a\nc",
            List.of(frame));
    final var withoutValues =
        new TestRecord("pkg.C", "m", "", "passed", "", -1, 3, "m()", null, null, List.of());

    final String json = ResultsListener.toJson(withValues);
    final JsonObject parsed = Json.fromJson(json, JsonObject.class);

    assertThat(json).doesNotContain("\n", "\t");
    assertThat(parsed.get("failureMessage").getAsString())
        .isEqualTo("he said \"hi\"\nback\\slash\ttab");
    assertThat(parsed.get("durationMs").getAsLong()).isEqualTo(12);
    assertThat(parsed.get("expected").getAsString()).isEqualTo("a\nb");
    assertThat(parsed.getAsJsonArray("stackTrace").get(0).getAsJsonObject().get("line").getAsInt())
        .isEqualTo(7);
    assertThat(ResultsListener.toJson(withoutValues))
        .doesNotContain("\"expected\"", "\"actual\"")
        .contains("\"stackTrace\":[]");
  }

  @Test
  void failureLine_topmostFrameOfTestClass_returnsThatLine() {
    final var throwable = new RuntimeException("x");
    throwable.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement("other.Helper", "help", "Helper.java", 5),
          new StackTraceElement("pkg.FooTest", "synthetic", "FooTest.java", -1),
          new StackTraceElement("pkg.FooTest", "test", "FooTest.java", 42)
        });

    assertThat(ResultsListener.failureLine(throwable, "pkg.FooTest")).isEqualTo(42);
  }

  @Test
  void failureLine_noMatchingFrameOrNoThrowable_returnsMinusOne() {
    final var throwable = new RuntimeException("x");
    throwable.setStackTrace(
        new StackTraceElement[] {new StackTraceElement("other.Helper", "help", "Helper.java", 5)});

    assertThat(ResultsListener.failureLine(throwable, "pkg.FooTest")).isEqualTo(-1);
    assertThat(ResultsListener.failureLine(null, "pkg.FooTest")).isEqualTo(-1);
  }

  private static List<JsonObject> run(final Path dir, final Class<?> fixture) throws IOException {
    final Path sink = dir.resolve("%s.ndjson".formatted(fixture.getSimpleName()));
    execute(
        fixture,
        new ResultsListener(sink, new PrintStream(OutputStream.nullOutputStream()), System.err));
    return Files.readAllLines(sink).stream()
        .map(line -> Json.fromJson(line, JsonObject.class))
        .toList();
  }

  private static void execute(final Class<?> fixture, final ResultsListener listener) {
    final var request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClass(fixture))
            .build();
    LauncherFactory.create().execute(request, listener);
  }

  private static JsonObject recordFor(final List<JsonObject> records, final String methodName) {
    return records.stream()
        .filter(record -> record.get("methodName").getAsString().equals(methodName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no record for " + methodName));
  }

  private static JsonObject single(final List<JsonObject> records) {
    assertThat(records).hasSize(1);
    return records.get(0);
  }

  private static List<JsonObject> objects(final JsonArray array) {
    return array.asList().stream().map(JsonElement::getAsJsonObject).toList();
  }
}

final class MixedFixture {

  @Test
  void passing_test() {}

  @Test
  void failing_test() {
    throw new AssertionError("boom failure");
  }

  @Test
  @Disabled("intentionally disabled")
  void disabled_test() {}

  @Test
  void aborted_test() {
    Assumptions.assumeTrue(false, "precondition not met");
  }
}

final class ComparisonFixture {

  @Test
  void single_line() {
    assertEquals("Alice", "alice");
  }

  @Test
  void multi_line() {
    assertEquals("one\ntwo", "one\nTWO");
  }

  @Test
  void assertj() {
    assertThat("alice").isEqualTo("Alice");
  }

  @Test
  void no_values() {
    fail("no comparison");
  }
}

final class BrokenSetupFixture {

  @BeforeAll
  static void setUp() {
    throw new IllegalStateException("setup exploded");
  }

  @Test
  void never_runs() {}
}

final class BrokenInitFixture {

  private static final int VALUE = Integer.parseInt("not a number");

  @Test
  void never_runs() {
    assertEquals(0, VALUE);
  }
}

final class BrokenTemplateFixture {

  static Stream<String> broken() {
    throw new IllegalStateException("no arguments");
  }

  @ParameterizedTest
  @MethodSource("broken")
  void broken_param(final String value) {}
}

final class PrintingFixture {

  @Test
  void prints() {
    System.out.println("printed by the test");
  }
}
