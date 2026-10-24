package io.github.aglibs.lathe.runner;

import java.util.List;
import java.util.Objects;

/**
 * One test outcome captured from JUnit Platform, serialized as a single NDJSON line to the results
 * sink: a method's, or a class's when the class itself failed (empty {@code methodName}). The field
 * names here are the wire contract read back on the server by {@code LaunchOutcome}'s {@code
 * TestResult}; keep both sides in sync.
 *
 * <p>The runner rides the replayed test classpath, so it stays as thin as possible: no gson (NDJSON
 * is hand-rolled, one line per record) and no ValidCheck (invariants use plain JDK checks), keeping
 * neither off the replay classpath.
 *
 * <p>{@code expected} and {@code actual} are null unless the failure carries both values.
 */
record TestRecord(
    String className,
    String methodName,
    String methodParameterTypes,
    String status,
    String failureMessage,
    int failureLine,
    long durationMs,
    String displayName,
    String expected,
    String actual,
    List<Frame> stackTrace) {

  TestRecord {
    Objects.requireNonNull(className, "className");
    Objects.requireNonNull(methodName, "methodName");
    Objects.requireNonNull(methodParameterTypes, "methodParameterTypes");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(failureMessage, "failureMessage");
    Objects.requireNonNull(displayName, "displayName");
    if (failureLine < -1) {
      throw new IllegalArgumentException("failureLine must be >= -1, was " + failureLine);
    }

    stackTrace = List.copyOf(stackTrace);
  }

  /** One stack frame of a failure; {@code fileName} is empty when the class file names none. */
  record Frame(String className, String methodName, String fileName, int line) {

    Frame {
      Objects.requireNonNull(className, "className");
      Objects.requireNonNull(methodName, "methodName");
      Objects.requireNonNull(fileName, "fileName");
    }
  }
}
