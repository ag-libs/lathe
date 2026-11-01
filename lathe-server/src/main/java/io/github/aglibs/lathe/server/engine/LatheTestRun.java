package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.lathe.server.run.LaunchOutcome;
import io.github.aglibs.lathe.server.run.TestResult;
import io.github.aglibs.lathe.server.run.TranscriptLine;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A replayed test run: whether it launched (else why not), what it ran, the pass/fail/skip counts,
 * the failures, and, when no test ran, the JVM's exit code and its last output lines.
 */
public record LatheTestRun(
    boolean launched,
    List<String> blockedReasons,
    List<String> ran,
    int total,
    int passed,
    int failed,
    int skipped,
    List<LatheTestFailure> failures,
    int exitCode,
    List<String> output) {

  // The runner's status wire values (ResultsListener); aborted is reported as skipped.
  private static final String PASSED = "passed";
  private static final String FAILED = "failed";
  private static final String SKIPPED = "skipped";
  // Enough to show an exception or the state a test printed, not a whole log.
  private static final int OUTPUT_LINES = 40;

  public LatheTestRun {
    ValidCheck.check()
        .notNull(blockedReasons, "blockedReasons")
        .notNull(ran, "ran")
        .notNull(failures, "failures")
        .notNull(output, "output")
        .validate();
    blockedReasons = List.copyOf(blockedReasons);
    ran = List.copyOf(ran);
    failures = List.copyOf(failures);
    output = List.copyOf(output);
  }

  // ran: the selected classes, methods or package, so a result names what it covers.
  static LatheTestRun from(final LaunchOutcome outcome, final List<String> ran) {
    final List<TestResult> results = outcome.testResults();
    final List<LatheTestFailure> failures =
        results.stream()
            .filter(r -> FAILED.equals(r.status()))
            .map(r -> toFailure(r, outcome.output()))
            .toList();
    return new LatheTestRun(
        outcome.launched(),
        outcome.blockedReasons(),
        ran,
        results.size(),
        (int) results.stream().filter(r -> PASSED.equals(r.status())).count(),
        failures.size(),
        (int) results.stream().filter(r -> SKIPPED.equals(r.status())).count(),
        failures,
        outcome.exitCode(),
        results.isEmpty() ? lastLines(outcome.output(), line -> true) : List.of());
  }

  // A class's own failure (its @BeforeAll broke) has no method, so it is named by the class alone.
  private static LatheTestFailure toFailure(final TestResult r, final List<TranscriptLine> output) {
    final String name =
        r.methodName().isEmpty() ? r.className() : "%s#%s".formatted(r.className(), r.methodName());
    return new LatheTestFailure(
        name, r.failureMessage(), r.failureLine(), lastLines(output, line -> printedBy(line, r)));
  }

  // A class's own failure (@BeforeAll, a static initializer) prints outside any one test, untagged.
  private static boolean printedBy(final TranscriptLine line, final TestResult r) {
    return Objects.equals(line.positionId(), r.positionId())
        || (r.methodName().isEmpty() && line.positionId() == null);
  }

  // What the test JVM printed, without the launch command itself.
  private static List<String> lastLines(
      final List<TranscriptLine> output, final Predicate<TranscriptLine> belongs) {
    final List<String> printed =
        output.stream()
            .filter(line -> line.stream() != TranscriptLine.Stream.COMMAND)
            .filter(belongs)
            .map(TranscriptLine::text)
            .toList();
    return printed.subList(Math.max(0, printed.size() - OUTPUT_LINES), printed.size());
  }
}
