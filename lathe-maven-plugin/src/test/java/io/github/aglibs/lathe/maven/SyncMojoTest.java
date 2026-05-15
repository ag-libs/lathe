package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SyncMojoTest {

  @AfterEach
  void clearProperty() {
    System.clearProperty("lathe.skip");
  }

  @Test
  void latheFlags_notDisabledByDefault() {
    assertThat(LatheFlags.isDisabled()).isFalse();
  }

  @Test
  void latheFlags_disabledWhenSkipTrue() {
    System.setProperty("lathe.skip", "true");
    assertThat(LatheFlags.isDisabled()).isTrue();
  }

  @Test
  void latheFlags_enabledWhenSkipFalse() {
    System.setProperty("lathe.skip", "false");
    assertThat(LatheFlags.isDisabled()).isFalse();
  }
}
