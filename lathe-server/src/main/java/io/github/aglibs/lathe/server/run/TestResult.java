package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * One test outcome read back from the replay results sink: a method's, or a class's when the class
 * itself failed ({@code methodName} empty). The fields up to {@code stackTrace} mirror the NDJSON
 * records written by {@code lathe-test-runner}'s {@code ResultsListener} ({@code failureLine} and
 * {@code durationMs} are {@code -1} when unknown, {@code failureMessage} is empty for non-failures,
 * {@code expected} and {@code actual} are null unless the failure carries both values). {@code
 * positionId} is derived server-side (the runner has no javac) so the client consumes it directly
 * instead of reconstructing the id itself: the method's id, or the class id for a class's own
 * failure. It is derived in the compact constructor when absent, which is the case for every record
 * deserialized from the sink.
 */
public record TestResult(
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
    List<StackFrame> stackTrace,
    String positionId) {

  public TestResult {
    ValidCheck.check()
        .notBlank(className, "className")
        .notNull(methodName, "methodName")
        .notNull(methodParameterTypes, "methodParameterTypes")
        .notBlank(status, "status")
        .notNull(failureMessage, "failureMessage")
        .min(failureLine, -1, "failureLine")
        .notNull(displayName, "displayName")
        .notNull(stackTrace, "stackTrace")
        .validate();
    stackTrace = List.copyOf(stackTrace);
    if (positionId == null || positionId.isBlank()) {
      positionId =
          methodName.isEmpty()
              ? className
              : TestId.positionId(className, methodName, methodParameterTypes);
    }
  }
}
