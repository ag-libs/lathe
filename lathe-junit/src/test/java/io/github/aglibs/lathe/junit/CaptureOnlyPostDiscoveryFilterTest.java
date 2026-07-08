package io.github.aglibs.lathe.junit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheFlags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class CaptureOnlyPostDiscoveryFilterTest {

  @AfterEach
  void clearProperty() {
    System.clearProperty(LatheFlags.TEST_CAPTURE_SKIP_EXECUTION);
  }

  @Test
  void apply_skipPropertyUnset_includesDescriptor() {
    final var filter = new CaptureOnlyPostDiscoveryFilter();

    assertThat(filter.apply(null).included()).isTrue();
  }

  @Test
  void apply_skipPropertyTrue_excludesDescriptor() {
    System.setProperty(LatheFlags.TEST_CAPTURE_SKIP_EXECUTION, "true");
    final var filter = new CaptureOnlyPostDiscoveryFilter();

    assertThat(filter.apply(null).excluded()).isTrue();
  }
}
