package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;

/**
 * Params of the {@code lathe/testFinished} notification: the final {@link LaunchOutcome} of a debug
 * session, tagged with the run token the client minted. The run path returns this same outcome as
 * the {@code lathe.run.test} command response; the debug launch returns its DAP ports immediately
 * (long before the debuggee exits) and cannot, so it publishes the outcome here when the debuggee
 * exits, letting the client complete its results reconciliation (gutters, summary, pass/fail)
 * exactly as a run does.
 */
public record TestFinishedParams(String token, LaunchOutcome outcome) {

  public TestFinishedParams {
    ValidCheck.check().notNull(token, "token").notNull(outcome, "outcome").validate();
  }
}
