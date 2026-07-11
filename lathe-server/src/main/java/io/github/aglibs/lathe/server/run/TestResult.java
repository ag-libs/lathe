package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;

/**
 * One method-level test outcome read back from the replay results sink. The first six fields mirror
 * the NDJSON records written by {@code lathe-test-runner}'s {@code ResultsListener} ({@code
 * failureLine} is {@code -1} when no line could be resolved, and {@code failureMessage} is empty
 * for non-failures); {@code positionId} is derived server-side (the runner has no javac) so the
 * client consumes it directly instead of reconstructing the id itself. It is derived in the compact
 * constructor when absent, which is the case for every record deserialized from the sink.
 */
public record TestResult(
    String className,
    String methodName,
    String methodParameterTypes,
    String status,
    String failureMessage,
    int failureLine,
    String positionId) {

  public TestResult {
    ValidCheck.check()
        .notBlank(className, "className")
        .notBlank(methodName, "methodName")
        .notNull(methodParameterTypes, "methodParameterTypes")
        .notBlank(status, "status")
        .notNull(failureMessage, "failureMessage")
        .min(failureLine, -1, "failureLine")
        .validate();
    positionId =
        (positionId == null || positionId.isBlank())
            ? TestId.positionId(className, methodName, methodParameterTypes)
            : positionId;
  }
}
