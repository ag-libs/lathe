package io.github.aglibs.lathe.junit;

import io.github.aglibs.lathe.core.LatheFlags;
import org.junit.platform.launcher.PostDiscoveryFilter;

// The editor's capture-only run: the test JVM starts and is captured, and no test runs. JUnit
// Platform 1.10+ skips the tests itself in its dry-run mode, after discovering them as usual, so a
// @Suite finds its tests and a build that decides from the discovered tests (Develocity) still
// starts the JVM. An older platform has no dry run, and the filter excludes every test instead.
// Compiled for Java 8, as the listener and the filter that use it are (see pom.xml).
final class CaptureOnly {

  private static final int DRY_RUN_MAJOR = 1;
  private static final int DRY_RUN_MINOR = 10;

  private CaptureOnly() {}

  static boolean requested() {
    return "true".equals(System.getProperty(LatheFlags.CAPTURE_ONLY));
  }

  // The launcher's version, read through a type every platform has (LauncherSession is 1.8+).
  static boolean dryRunSupported() {
    return dryRunSupported(PostDiscoveryFilter.class.getPackage().getImplementationVersion());
  }

  // "1.10.0", or "6.0.1" since JUnit 6. Unknown counts as unsupported, and the filter then
  // excludes.
  static boolean dryRunSupported(final String platformVersion) {
    if (platformVersion == null) {
      return false;
    }

    final String[] parts = platformVersion.split("\\.");
    try {
      final int major = Integer.parseInt(parts[0]);
      final int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
      return major > DRY_RUN_MAJOR || (major == DRY_RUN_MAJOR && minor >= DRY_RUN_MINOR);
    } catch (final NumberFormatException e) {
      return false;
    }
  }
}
