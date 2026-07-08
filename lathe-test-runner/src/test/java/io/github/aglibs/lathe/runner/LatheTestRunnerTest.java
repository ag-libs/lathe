package io.github.aglibs.lathe.runner;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

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
                new String[] {}, new PrintStream(err, true, StandardCharsets.UTF_8)))
        .isEqualTo(LatheTestRunner.EXIT_USAGE);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("No test selectors provided");
  }

  private static int runClass(final Class<?> testClass) {
    return LatheTestRunner.run(
        new String[] {TestSelectionKind.CLASS.runnerFlag(), testClass.getName()});
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
