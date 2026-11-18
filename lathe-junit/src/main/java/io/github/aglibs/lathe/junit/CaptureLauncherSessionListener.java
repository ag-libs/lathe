package io.github.aglibs.lathe.junit;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import java.io.PrintStream;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

// The entry point JUnit loads in every test JVM, so it is compiled for Java 8 (see pom.xml): a
// test JVM older than Lathe's code loads it, captures nothing, and runs its tests as usual. Only
// on a JDK Lathe runs on does it reach the capture, which is Java 17 code.
public final class CaptureLauncherSessionListener implements LauncherSessionListener {

  @Override
  public void launcherSessionOpened(final LauncherSession session) {
    try {
      // Before the JDK check: an old test JVM captures nothing, but still skips its tests.
      if (CaptureOnly.requested() && CaptureOnly.dryRunSupported()) {
        System.setProperty(LatheFlags.JUNIT_DRY_RUN, "true");
      }

      final int javaVersion = featureVersion(System.getProperty("java.specification.version"));
      if (javaVersion < LatheLayout.MIN_JAVA_VERSION) {
        notCaptured(
            System.err,
            String.format(
                "this test JVM runs Java %d; Lathe captures on Java %d or newer",
                javaVersion, LatheLayout.MIN_JAVA_VERSION));
        return;
      }

      LaunchCapture.capture();
    } catch (final Throwable e) {
      // Capture must never change the user's test result; it says why in the build's test output.
      notCaptured(System.err, e);
    }
  }

  // A failed capture, by the exception and where it was thrown. The report is guarded too: an
  // exception whose toString() throws must not fail the tests either.
  static void notCaptured(final PrintStream err, final Throwable cause) {
    try {
      final StackTraceElement[] frames = cause.getStackTrace();
      notCaptured(
          err,
          frames.length == 0 ? String.valueOf(cause) : String.format("%s at %s", cause, frames[0]));
    } catch (final Throwable ignored) {
      // Nothing left to say it with.
    }
  }

  // The one line a capture that did not happen leaves in the build's test output: the reason the
  // editor cannot run these tests.
  static void notCaptured(final PrintStream err, final String reason) {
    err.printf("[lathe] test launch not captured: %s%n", reason);
  }

  // "1.8" up to Java 8, then "9", "11", "17".
  static int featureVersion(final String specificationVersion) {
    return Integer.parseInt(
        specificationVersion.startsWith("1.")
            ? specificationVersion.substring(2)
            : specificationVersion);
  }
}
