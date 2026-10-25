package io.github.aglibs.lathe.junit;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarFile;

// Puts this jar on the system class loader when a build starts the test JVM with -javaagent: the
// Gradle plugin does, through the test task's JDK_JAVA_OPTIONS, so the task's inputs, and its
// build-cache key, stay the build's own. JUnit's ServiceLoader then finds the listener as if the
// jar were on the class path, and the tests run as they would without Lathe.
public final class LatheAgent {

  private LatheAgent() {}

  // A failing agent stops the JVM from starting, so it never throws: without it, no capture. The
  // jar stays open: the class loader reads from it for the rest of the run.
  public static void premain(final String args, final Instrumentation instrumentation) {
    try {
      final Optional<Path> jar = CaptureLauncherSessionListener.ownJarLocation();
      if (jar.isPresent()) {
        instrumentation.appendToSystemClassLoaderSearch(new JarFile(jar.get().toFile()));
      }
    } catch (final Throwable ignored) {
      // Capture must never change the user's test run.
    }
  }
}
