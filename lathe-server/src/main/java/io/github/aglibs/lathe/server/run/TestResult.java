package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;

/**
 * One method-level test outcome read back from the replay results sink. Field names mirror the
 * NDJSON records written by {@code lathe-test-runner}'s {@code ResultsListener}; {@code
 * failureLine} is {@code -1} when no line could be resolved, and {@code failureMessage} is empty
 * for non-failures.
 */
public record TestResult(
    String className,
    String methodName,
    String methodParameterTypes,
    String status,
    String failureMessage,
    int failureLine) {

  public TestResult {
    ValidCheck.check()
        .notBlank(className, "className")
        .notBlank(methodName, "methodName")
        .notNull(methodParameterTypes, "methodParameterTypes")
        .notBlank(status, "status")
        .notNull(failureMessage, "failureMessage")
        .min(failureLine, -1, "failureLine")
        .validate();
  }
}
