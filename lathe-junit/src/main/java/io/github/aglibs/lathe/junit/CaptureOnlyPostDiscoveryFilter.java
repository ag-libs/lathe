package io.github.aglibs.lathe.junit;

import io.github.aglibs.lathe.core.LatheFlags;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.launcher.PostDiscoveryFilter;

// Compiled for Java 8, as the listener is, so the capture-only sync skips the tests on any JDK. It
// reads the property itself rather than through LatheFlags.isCaptureOnly(): the constant is copied
// in at compile time, so a test JVM older than Lathe's code never loads lathe-core.
public final class CaptureOnlyPostDiscoveryFilter implements PostDiscoveryFilter {

  @Override
  public FilterResult apply(final TestDescriptor descriptor) {
    return "true".equals(System.getProperty(LatheFlags.CAPTURE_ONLY))
        ? FilterResult.excluded("Lathe capture-only: test execution skipped")
        : FilterResult.included("Lathe test execution enabled");
  }
}
