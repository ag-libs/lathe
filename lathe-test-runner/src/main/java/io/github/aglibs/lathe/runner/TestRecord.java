package io.github.aglibs.lathe.runner;

import java.util.Objects;

/**
 * One method-level test outcome captured from JUnit Platform, serialized as a single NDJSON line to
 * the results sink. The field names here are the wire contract read back on the server by {@code
 * ReplayOutcome}'s {@code TestResult}; keep both sides in sync.
 *
 * <p>The runner rides the replayed test classpath, so it stays as thin as possible: no gson (NDJSON
 * is hand-rolled, one line per record) and no ValidCheck (invariants use plain JDK checks), keeping
 * neither off the replay classpath.
 */
record TestRecord(
    String className,
    String methodName,
    String methodParameterTypes,
    String status,
    String failureMessage,
    int failureLine) {

  TestRecord {
    Objects.requireNonNull(className, "className");
    Objects.requireNonNull(methodName, "methodName");
    Objects.requireNonNull(methodParameterTypes, "methodParameterTypes");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(failureMessage, "failureMessage");
    if (failureLine < -1) {
      throw new IllegalArgumentException("failureLine must be >= -1, was " + failureLine);
    }
  }
}
