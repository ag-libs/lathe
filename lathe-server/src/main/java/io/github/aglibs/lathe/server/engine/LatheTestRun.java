package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.lathe.server.run.LaunchOutcome;
import io.github.aglibs.lathe.server.run.TestResult;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * A replayed test run: whether it launched (else why not), the pass/fail/skip counts, and the
 * failures.
 */
public record LatheTestRun(
    boolean launched,
    List<String> blockedReasons,
    int total,
    int passed,
    int failed,
    int skipped,
    List<LatheTestFailure> failures) {

  // The runner's status wire values (ResultsListener); aborted is reported as skipped.
  private static final String PASSED = "passed";
  private static final String FAILED = "failed";
  private static final String SKIPPED = "skipped";

  public LatheTestRun {
    ValidCheck.check()
        .notNull(blockedReasons, "blockedReasons")
        .notNull(failures, "failures")
        .validate();
    blockedReasons = List.copyOf(blockedReasons);
    failures = List.copyOf(failures);
  }

  static LatheTestRun from(final LaunchOutcome outcome) {
    final List<TestResult> results = outcome.testResults();
    final List<LatheTestFailure> failures =
        results.stream()
            .filter(r -> FAILED.equals(r.status()))
            .map(LatheTestRun::toFailure)
            .toList();
    return new LatheTestRun(
        outcome.launched(),
        outcome.blockedReasons(),
        results.size(),
        (int) results.stream().filter(r -> PASSED.equals(r.status())).count(),
        failures.size(),
        (int) results.stream().filter(r -> SKIPPED.equals(r.status())).count(),
        failures);
  }

  private static LatheTestFailure toFailure(final TestResult r) {
    return new LatheTestFailure(
        "%s#%s".formatted(r.className(), r.methodName()), r.failureMessage(), r.failureLine());
  }
}
