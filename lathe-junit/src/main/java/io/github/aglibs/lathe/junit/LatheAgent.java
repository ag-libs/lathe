package io.github.aglibs.lathe.junit;

import java.lang.instrument.Instrumentation;

// The class -javaagent requires: the Gradle plugin starts each test JVM with this jar as an agent,
// through the test task's JDK_JAVA_OPTIONS, so the task's inputs, and its build-cache key, stay the
// build's own. Loading the jar is all it takes: the JVM puts an agent's jar on the system class
// path, where JUnit's ServiceLoader finds the listener. Compiled for Java 8, as the listener is,
// so a test JVM older than Lathe's code loads it too (see pom.xml).
public final class LatheAgent {

  private LatheAgent() {}

  public static void premain(final String args, final Instrumentation instrumentation) {}
}
