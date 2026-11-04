package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

// notes: the completeness gate's word on what the run took from the last build
// (CompletenessResult).
public record LaunchOutcome(
    boolean launched,
    List<String> blockedReasons,
    int exitCode,
    List<TranscriptLine> output,
    List<TestResult> testResults,
    List<String> notes) {

  public LaunchOutcome {
    ValidCheck.check()
        .notNull(blockedReasons, "blockedReasons")
        .notNull(output, "output")
        .notNull(testResults, "testResults")
        .notNull(notes, "notes")
        .validate();
    blockedReasons = List.copyOf(blockedReasons);
    output = List.copyOf(output);
    testResults = List.copyOf(testResults);
    notes = List.copyOf(notes);
  }

  public static LaunchOutcome blocked(final List<String> reasons) {
    return new LaunchOutcome(false, reasons, -1, List.of(), List.of(), List.of());
  }

  public static LaunchOutcome completed(
      final int exitCode, final List<TranscriptLine> output, final List<TestResult> testResults) {
    return new LaunchOutcome(true, List.of(), exitCode, output, testResults, List.of());
  }

  public LaunchOutcome withNotes(final List<String> notes) {
    return new LaunchOutcome(launched, blockedReasons, exitCode, output, testResults, notes);
  }
}
