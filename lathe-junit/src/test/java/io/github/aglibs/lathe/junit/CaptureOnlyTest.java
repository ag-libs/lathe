package io.github.aglibs.lathe.junit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class CaptureOnlyTest {

  // Dry run arrived in JUnit Platform 1.10; JUnit 6 numbers the platform 6.x. An unknown version
  // keeps the filter, which works on every platform.
  @Test
  void dryRunSupported_platformVersions_from110On() {
    assertThat(CaptureOnly.dryRunSupported("1.9.3")).isFalse();
    assertThat(CaptureOnly.dryRunSupported("1.10.0")).isTrue();
    assertThat(CaptureOnly.dryRunSupported("1.14.4")).isTrue();
    assertThat(CaptureOnly.dryRunSupported("6.0.1")).isTrue();
    assertThat(CaptureOnly.dryRunSupported(null)).isFalse();
    assertThat(CaptureOnly.dryRunSupported("snapshot")).isFalse();
  }
}
