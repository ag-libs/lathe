package io.github.aglibs.lathe.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

final class ResultsListenerTest {

  @Test
  void execute_mixedFixture_writesOneRecordPerMethod(@TempDir final Path dir) throws IOException {
    final List<String> lines = runFixture(dir, MixedFixture.class);

    assertThat(lines).hasSize(4);
  }

  @Test
  void execute_passingMethod_recordsPassedNoFailureLine(@TempDir final Path dir)
      throws IOException {
    final String line = lineFor(runFixture(dir, MixedFixture.class), "passing_test");

    assertThat(line).contains("\"status\":\"passed\"").contains("\"failureLine\":-1");
  }

  @Test
  void execute_failingMethod_recordsFailedWithMessageAndLine(@TempDir final Path dir)
      throws IOException {
    final String line = lineFor(runFixture(dir, MixedFixture.class), "failing_test");

    assertThat(line).contains("\"status\":\"failed\"").contains("boom failure");
    assertThat(failureLineOf(line)).isPositive();
  }

  @Test
  void execute_disabledMethod_recordsSkipped(@TempDir final Path dir) throws IOException {
    final String line = lineFor(runFixture(dir, MixedFixture.class), "disabled_test");

    assertThat(line).contains("\"status\":\"skipped\"");
  }

  @Test
  void execute_abortedMethod_recordsSkipped(@TempDir final Path dir) throws IOException {
    final String line = lineFor(runFixture(dir, MixedFixture.class), "aborted_test");

    assertThat(line).contains("\"status\":\"skipped\"");
  }

  @Test
  void execute_parameterizedFixture_recordsInvocationsNotTheTemplateContainer(
      @TempDir final Path dir) throws IOException {
    final List<String> lines = runFixture(dir, ParameterizedFixture.class);

    final long paramRecords =
        lines.stream().filter(line -> line.contains("\"methodName\":\"param_test\"")).count();
    assertThat(paramRecords).isEqualTo(2);
    assertThat(lines).hasSize(3);
  }

  @Test
  void toJson_specialCharacters_escapedOnSingleLine() {
    final var record =
        new TestRecord("pkg.C", "m", "", "failed", "he said \"hi\"\nback\\slash\ttab", 7);

    final String json = ResultsListener.toJson(record);

    assertThat(json).doesNotContain("\n", "\t");
    assertThat(json).contains("\\\"hi\\\"").contains("\\nback\\\\slash\\ttab");
    assertThat(json).contains("\"failureLine\":7");
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

  private static List<String> runFixture(final Path dir, final Class<?> fixture)
      throws IOException {
    final Path sink = dir.resolve("results.ndjson");
    final var request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClass(fixture))
            .build();
    LauncherFactory.create().execute(request, new ResultsListener(sink, System.err));
    return Files.readAllLines(sink);
  }

  private static String lineFor(final List<String> lines, final String methodName) {
    return lines.stream()
        .filter(line -> line.contains("\"methodName\":\"%s\"".formatted(methodName)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no record for " + methodName));
  }

  private static int failureLineOf(final String line) {
    final String marker = "\"failureLine\":";
    final int start = line.indexOf(marker) + marker.length();
    final int end = line.indexOf('}', start);
    return Integer.parseInt(line.substring(start, end));
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

final class ParameterizedFixture {

  @Test
  void plain_test() {}

  @ParameterizedTest
  @ValueSource(strings = {"a", "b"})
  void param_test(final String value) {}
}
