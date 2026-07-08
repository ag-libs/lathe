package io.github.aglibs.lathe.junit;

import io.github.aglibs.lathe.core.LatheFlags;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.launcher.PostDiscoveryFilter;

public final class CaptureOnlyPostDiscoveryFilter implements PostDiscoveryFilter {

  @Override
  public FilterResult apply(final TestDescriptor descriptor) {
    return LatheFlags.isTestExecutionSkipped()
        ? FilterResult.excluded("Lathe test execution skipped")
        : FilterResult.included("Lathe test execution enabled");
  }
}
