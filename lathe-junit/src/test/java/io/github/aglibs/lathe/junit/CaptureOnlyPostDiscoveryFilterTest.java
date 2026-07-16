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

  @Test
  void apply_captureOnlyTrue_excludesDescriptor() {
    System.setProperty(LatheFlags.CAPTURE_ONLY, "true");
    final var filter = new CaptureOnlyPostDiscoveryFilter();

    assertThat(filter.apply(null).excluded()).isTrue();
  }
}
