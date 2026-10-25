package io.github.aglibs.lathe.junit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheFlags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class CaptureOnlyPostDiscoveryFilterTest {

  @AfterEach
  void clearProperty() {
    System.clearProperty(LatheFlags.CAPTURE_ONLY);
  }

  @Test
  void apply_captureOnlyUnset_includesDescriptor() {
    final var filter = new CaptureOnlyPostDiscoveryFilter();

    assertThat(filter.apply(null).included()).isTrue();
  }

  // This module tests on a JUnit Platform with dry run, which skips the tests instead; a platform
  // without it is CaptureOnlyTest's.
  @Test
  void apply_captureOnlyWithDryRun_leavesTheSkippingToTheDryRun() {
    System.setProperty(LatheFlags.CAPTURE_ONLY, "true");
    final var filter = new CaptureOnlyPostDiscoveryFilter();

    assertThat(CaptureOnly.dryRunSupported()).isTrue();
    assertThat(filter.apply(null).included()).isTrue();
  }
}
