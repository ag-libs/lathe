package io.github.aglibs.lathe.junit;

import io.github.aglibs.lathe.core.LatheLayout;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

// The entry point JUnit loads in every test JVM, so it is compiled for Java 8 (see pom.xml): a
// test JVM older than Lathe's code loads it, captures nothing, and runs its tests as usual. Only
// on a JDK Lathe runs on does it reach the capture, which is Java 17 code.
public final class CaptureLauncherSessionListener implements LauncherSessionListener {

  @Override
  public void launcherSessionOpened(final LauncherSession session) {
    try {
      if (featureVersion(System.getProperty("java.specification.version"))
          >= LatheLayout.MIN_JAVA_VERSION) {
        LaunchCapture.capture();
      }
    } catch (final Throwable ignored) {
      // Capture must never change the user's test result.
    }
  }

  // "1.8" up to Java 8, then "9", "11", "17".
  static int featureVersion(final String specificationVersion) {
    return Integer.parseInt(
        specificationVersion.startsWith("1.")
            ? specificationVersion.substring(2)
            : specificationVersion);
  }
}
