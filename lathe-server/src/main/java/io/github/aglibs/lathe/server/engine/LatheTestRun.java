package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.lathe.server.run.LaunchOutcome;
import io.github.aglibs.lathe.server.run.TestResult;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * A replayed test run: whether it launched (else why not), what it ran, the pass/fail/skip counts,
 * and the failures.
 */
public record LatheTestRun(
    boolean launched,
    List<String> blockedReasons,
    List<String> ran,
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
        .notNull(ran, "ran")
        .notNull(failures, "failures")
        .validate();
    blockedReasons = List.copyOf(blockedReasons);
    ran = List.copyOf(ran);
    failures = List.copyOf(failures);
  }

  // ran: the selected classes, methods or package, so a result names what it covers.
  static LatheTestRun from(final LaunchOutcome outcome, final List<String> ran) {
    final List<TestResult> results = outcome.testResults();
    final List<LatheTestFailure> failures =
        results.stream()
            .filter(r -> FAILED.equals(r.status()))
            .map(LatheTestRun::toFailure)
            .toList();
    return new LatheTestRun(
        outcome.launched(),
        outcome.blockedReasons(),
        ran,
        results.size(),
        (int) results.stream().filter(r -> PASSED.equals(r.status())).count(),
        failures.size(),
        (int) results.stream().filter(r -> SKIPPED.equals(r.status())).count(),
        failures);
  }

  // A class's own failure (its @BeforeAll broke) has no method, so it is named by the class alone.
  private static LatheTestFailure toFailure(final TestResult r) {
    final String name =
        r.methodName().isEmpty() ? r.className() : "%s#%s".formatted(r.className(), r.methodName());
    return new LatheTestFailure(name, r.failureMessage(), r.failureLine());
  }
}
