package io.github.aglibs.lathe.junit;

import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.launcher.PostDiscoveryFilter;

// Capture-only on a JUnit Platform without dry run (see CaptureOnly): every test is excluded. With
// dry run the listener turns it on, and this includes everything. Compiled for Java 8, as the
// listener is, so the capture-only sync skips the tests on any JDK.
public final class CaptureOnlyPostDiscoveryFilter implements PostDiscoveryFilter {

  @Override
  public FilterResult apply(final TestDescriptor descriptor) {
    return CaptureOnly.requested() && !CaptureOnly.dryRunSupported()
        ? FilterResult.excluded("Lathe capture-only: test execution skipped")
        : FilterResult.included("Lathe test execution enabled");
  }
}
