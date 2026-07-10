package io.github.aglibs.lathe.runner;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LatheTestRunnerTest {

  @Test
  void run_selectClassPassingFixture_returnsOk() {
    assertThat(runClass(PassingFixture.class)).isEqualTo(LatheTestRunner.EXIT_OK);
  }

  @Test
  void run_selectClassFailingFixture_returnsFailure() {
    assertThat(runClass(FailingFixture.class)).isEqualTo(LatheTestRunner.EXIT_FAILURE);
  }

  @Test
  void run_missingSelectors_returnsUsage() {
    final var err = new ByteArrayOutputStream();

    assertThat(
            LatheTestRunner.run(
                new String[] {}, System.out, new PrintStream(err, true, StandardCharsets.UTF_8)))
        .isEqualTo(LatheTestRunner.EXIT_USAGE);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("No test selectors provided");
  }

  @Test
  void run_failingFixture_printsFailureStackTraceToOut() {
    final var out = new ByteArrayOutputStream();

    final int code =
        LatheTestRunner.run(
            new String[] {TestSelectorParser.SELECT_CLASS, FailingFixture.class.getName()},
            new PrintStream(out, true, StandardCharsets.UTF_8),
            System.err);

    assertThat(code).isEqualTo(LatheTestRunner.EXIT_FAILURE);
    final String text = out.toString(StandardCharsets.UTF_8);
    assertThat(text).contains("failing_test_returnsRed").contains("expected");
    // The client's stack-frame parser keys on the "at <fqcn>(<File>.java:<line>)" shape.
    assertThat(text).containsPattern("at .+\\.java:\\d+\\)");
  }

  @Test
  void run_passingFixture_printsNothingToOut() {
    final var out = new ByteArrayOutputStream();

    LatheTestRunner.run(
        new String[] {TestSelectorParser.SELECT_CLASS, PassingFixture.class.getName()},
        new PrintStream(out, true, StandardCharsets.UTF_8),
        System.err);

    assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
  }

  @Test
  void run_withResultsSinkProperty_writesPerTestRecords(@TempDir final Path dir)
      throws IOException {
    final Path sink = dir.resolve("results.ndjson");
    System.setProperty(LatheTestRunner.RESULTS_SINK, sink.toString());
    try {
      assertThat(runClass(PassingFixture.class)).isEqualTo(LatheTestRunner.EXIT_OK);
    } finally {
      System.clearProperty(LatheTestRunner.RESULTS_SINK);
    }

    assertThat(Files.readAllLines(sink))
        .anyMatch(line -> line.contains("\"methodName\":\"passing_test_returnsGreen\""));
  }

  @Test
  void inlinedWireLiterals_matchLatheCoreSourceOfTruth() {
    assertThat(LatheTestRunner.RESULTS_SINK).isEqualTo(LatheFlags.RESULTS_SINK);
    assertThat(TestSelectorParser.SELECT_CLASS).isEqualTo(TestSelectionKind.CLASS.runnerFlag());
    assertThat(TestSelectorParser.SELECT_METHOD).isEqualTo(TestSelectionKind.METHOD.runnerFlag());
    assertThat(TestSelectorParser.SELECT_PACKAGE).isEqualTo(TestSelectionKind.PACKAGE.runnerFlag());
    assertThat(TestSelectorParser.SELECT_MODULE).isEqualTo(TestSelectionKind.MODULE.runnerFlag());
  }

  private static int runClass(final Class<?> testClass) {
    return LatheTestRunner.run(new String[] {TestSelectorParser.SELECT_CLASS, testClass.getName()});
  }
}

final class PassingFixture {

  @Test
  void passing_test_returnsGreen() {}
}

final class FailingFixture {

  @Test
  void failing_test_returnsRed() {
    throw new AssertionError("expected");
  }
}
