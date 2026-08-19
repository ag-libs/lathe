package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.validcheck.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

final class LaunchOutcomeTest {

  @Test
  void blocked_reasons_hasEmptyTestResults() {
    final LaunchOutcome outcome = LaunchOutcome.blocked(List.of("no runner jar"));

    assertThat(outcome.launched()).isFalse();
    assertThat(outcome.testResults()).isEmpty();
  }

  @Test
  void completed_withTestResults_carriesAndCopiesThem() {
    final var result = new TestResult("pkg.FooTest", "bar", "", "failed", "boom", 7, "");

    final LaunchOutcome outcome =
        LaunchOutcome.completed(
            1, List.of(new TranscriptLine(TranscriptLine.Stream.STDOUT, "line")), List.of(result));

    assertThat(outcome.launched()).isTrue();
    assertThat(outcome.testResults()).containsExactly(result);
    assertThatThrownBy(() -> outcome.testResults().add(result))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void constructor_nullTestResults_isRejected() {
    assertThatThrownBy(() -> new LaunchOutcome(true, List.of(), 0, List.of(), null))
        .isInstanceOf(ValidationException.class);
  }
}
