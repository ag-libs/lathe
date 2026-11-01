package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LatheFlagsTest {

  @AfterEach
  void clearProperty() {
    System.clearProperty(LatheFlags.DISABLED);
    System.clearProperty(LatheFlags.FORCE_SYNC);
    System.clearProperty(LatheFlags.CAPTURE_ONLY);
  }

  @Test
  void isDisabled_trueWhenDisabledPropertyIsTrue() {
    System.setProperty(LatheFlags.DISABLED, "true");
    assertThat(LatheFlags.isDisabled()).isTrue();
  }

  @Test
  void isDisabled_explicitValueOrCi_explicitValueWins() {
    assertThat(LatheFlags.isDisabled("true", false)).isTrue();
    assertThat(LatheFlags.isDisabled("false", true)).isFalse();
    assertThat(LatheFlags.isDisabled(null, true)).isTrue();
    assertThat(LatheFlags.isDisabled(null, false)).isFalse();
    assertThat(LatheFlags.isDisabled("yes", false)).isFalse();
  }

  @Test
  void unsupportedBuild_twoProblems_listsEachAndTheWayOut() {
    assertThat(LatheFlags.unsupportedBuild(List.of("first", "second"), "-P"))
        .isEqualTo(
            "[lathe] This build is older than Lathe supports:%n  - first%n  - second%n  To build without Lathe, add -Plathe.disabled=true"
                .formatted());
  }

  @Test
  void isCaptureOnly_falseByDefault() {
    assertThat(LatheFlags.isCaptureOnly()).isFalse();
  }

  @Test
  void isCaptureOnly_trueWhenPropertyIsTrue() {
    System.setProperty(LatheFlags.CAPTURE_ONLY, "true");

    assertThat(LatheFlags.isCaptureOnly()).isTrue();
  }
}
