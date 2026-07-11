package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

public record ReplayOutcome(
    boolean launched,
    List<String> blockedReasons,
    int exitCode,
    List<TranscriptLine> output,
    List<TestResult> testResults) {

  public ReplayOutcome {
    ValidCheck.check()
        .notNull(blockedReasons, "blockedReasons")
        .notNull(output, "output")
        .notNull(testResults, "testResults")
        .validate();
    blockedReasons = List.copyOf(blockedReasons);
    output = List.copyOf(output);
    testResults = List.copyOf(testResults);
  }

  public static ReplayOutcome blocked(final List<String> reasons) {
    return new ReplayOutcome(false, reasons, -1, List.of(), List.of());
  }

  public static ReplayOutcome completed(
      final int exitCode, final List<TranscriptLine> output, final List<TestResult> testResults) {
    return new ReplayOutcome(true, List.of(), exitCode, output, testResults);
  }
}
