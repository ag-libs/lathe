package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;

/**
 * Params of the {@code lathe/testEvent} notification: one method-level {@link TestResult} tailed
 * from the results sink as it is flushed mid-run, tagged with the run token the client minted so it
 * can mark exactly that position live. The final {@code ReplayOutcome.testResults} read at process
 * exit stays authoritative; these events are the best-effort live feed on top of it.
 */
public record TestEventParams(String token, TestResult result) {

  public TestEventParams {
    ValidCheck.check().notNull(token, "token").notNull(result, "result").validate();
  }
}
