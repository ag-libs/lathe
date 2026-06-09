package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LatheFlagsTest {

  @AfterEach
  void clearProperty() {
    System.clearProperty("lathe.skip");
  }

  @Test
  void isDisabled_notDisabledByDefault() {
    assertThat(LatheFlags.isDisabled()).isFalse();
  }

  @Test
  void isDisabled_trueWhenSkipPropertyIsTrue() {
    System.setProperty("lathe.skip", "true");
    assertThat(LatheFlags.isDisabled()).isTrue();
  }

  @Test
  void isDisabled_falseWhenSkipPropertyIsFalse() {
    System.setProperty("lathe.skip", "false");
    assertThat(LatheFlags.isDisabled()).isFalse();
  }
}
