package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LatheFlagsTest {

  @AfterEach
  void clearProperty() {
    System.clearProperty(LatheFlags.SKIP);
    System.clearProperty(LatheFlags.FORCE_SYNC);
    System.clearProperty(LatheFlags.TEST_CAPTURE_SKIP_EXECUTION);
  }

  @Test
  void isDisabled_notDisabledByDefault() {
    assertThat(LatheFlags.isDisabled()).isFalse();
  }

  @Test
  void isDisabled_trueWhenSkipPropertyIsTrue() {
    System.setProperty(LatheFlags.SKIP, "true");
    assertThat(LatheFlags.isDisabled()).isTrue();
  }

  @Test
  void isDisabled_falseWhenSkipPropertyIsFalse() {
    System.setProperty(LatheFlags.SKIP, "false");
    assertThat(LatheFlags.isDisabled()).isFalse();
  }

  @Test
  void isTestExecutionSkipped_trueWhenPropertyIsTrue() {
    System.setProperty(LatheFlags.TEST_CAPTURE_SKIP_EXECUTION, "true");

    assertThat(LatheFlags.isTestExecutionSkipped()).isTrue();
  }
}
