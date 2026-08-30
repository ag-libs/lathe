package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LatheFlagsTest {

  @AfterEach
  void clearProperty() {
    System.clearProperty(LatheFlags.SKIP);
    System.clearProperty(LatheFlags.FORCE_SYNC);
    System.clearProperty(LatheFlags.CAPTURE_ONLY);
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
  void isCaptureOnly_falseByDefault() {
    assertThat(LatheFlags.isCaptureOnly()).isFalse();
  }

  @Test
  void isCaptureOnly_trueWhenPropertyIsTrue() {
    System.setProperty(LatheFlags.CAPTURE_ONLY, "true");

    assertThat(LatheFlags.isCaptureOnly()).isTrue();
  }
}
