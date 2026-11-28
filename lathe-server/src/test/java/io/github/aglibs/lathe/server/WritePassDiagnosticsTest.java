package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

// How the analysis's diagnostics and a write's merge. The written findings are shaped as Error
// Prone 2.50 reports them: at the declaration's name, one character wide once mapped (EG-125).
class WritePassDiagnosticsTest {

  private static final String ERROR_PRONE_WARNING = "compiler.warn.error.prone";

  private static Diagnostic unusedHint(final int line, final int character, final String name) {
    final var hint =
        new Diagnostic(
            new Range(new Position(line, character), new Position(line, character + name.length())),
            "Unused local variable '%s'".formatted(name),
            DiagnosticSeverity.Hint,
            "lathe",
            "lathe.unused");
    hint.setTags(List.of(DiagnosticTag.Unnecessary));
    return hint;
  }

  private static Diagnostic errorProne(final int line, final int character, final String message) {
    return errorProne(line, character, message, DiagnosticSeverity.Warning, ERROR_PRONE_WARNING);
  }

  private static Diagnostic errorProne(
      final int line,
      final int character,
      final String message,
      final DiagnosticSeverity severity,
      final String code) {
    return new Diagnostic(
        new Range(new Position(line, character), new Position(line, character + 1)),
        message,
        severity,
        "lathe",
        code);
  }

  // A local both report: Error Prone's finding alone, faded as the hint was.
  @Test
  void withWriteOnly_errorProneUnusedVariableAtTheHint_keepsTheFindingOnceFaded() {
    final Diagnostic written =
        errorProne(4, 17, "[UnusedVariable] The local variable 'l' is never read.");

    final List<Diagnostic> merged =
        WritePass.withWriteOnly(List.of(unusedHint(4, 17, "l")), List.of(written));

    assertThat(merged)
        .singleElement()
        .satisfies(
            diagnostic -> {
              assertThat(diagnostic.getMessage().getLeft()).startsWith("[UnusedVariable]");
              assertThat(diagnostic.getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
              assertThat(diagnostic.getTags()).containsExactly(DiagnosticTag.Unnecessary);
            });
    assertThat(written.getTags()).as("the write's own finding is kept as it was").isNull();
  }

  // A private method Error Prone's UnusedMethod reports, at its name.
  @Test
  void withWriteOnly_errorProneUnusedMethodAtTheHint_keepsTheFindingOnce() {
    final List<Diagnostic> merged =
        WritePass.withWriteOnly(
            List.of(unusedHint(7, 15, "helper")),
            List.of(errorProne(7, 15, "[UnusedMethod] Method 'helper' is never used.")));

    assertThat(merged)
        .extracting(diagnostic -> diagnostic.getMessage().getLeft())
        .containsExactly("[UnusedMethod] Method 'helper' is never used.");
  }

  // Another check at the same name says something else: both stay.
  @Test
  void withWriteOnly_otherErrorProneCheckAtTheHint_keepsBoth() {
    final List<Diagnostic> merged =
        WritePass.withWriteOnly(
            List.of(unusedHint(5, 14, "count")),
            List.of(errorProne(5, 14, "[FieldCanBeFinal] Field is never assigned.")));

    assertThat(merged)
        .extracting(diagnostic -> diagnostic.getMessage().getLeft())
        .containsExactly(
            "Unused local variable 'count'", "[FieldCanBeFinal] Field is never assigned.");
  }

  // With the check off, or suppressed with @SuppressWarnings("UnusedVariable"), the write reports
  // nothing there, so the hint stays; so does one whose declaration Error Prone does not report.
  @Test
  void withWriteOnly_noErrorProneFindingAtTheHint_keepsTheHint() {
    final List<Diagnostic> merged =
        WritePass.withWriteOnly(
            List.of(unusedHint(4, 17, "l"), unusedHint(9, 8, "quiet")),
            List.of(errorProne(4, 17, "[UnusedVariable] The local variable 'l' is never read.")));

    assertThat(merged)
        .extracting(diagnostic -> diagnostic.getMessage().getLeft())
        .containsExactly(
            "Unused local variable 'quiet'",
            "[UnusedVariable] The local variable 'l' is never read.");
  }

  // A check run as a suggestion reports under javac's note code: one report too.
  @Test
  void withWriteOnly_errorProneSuggestionAtTheHint_keepsTheFindingOnce() {
    final List<Diagnostic> merged =
        WritePass.withWriteOnly(
            List.of(unusedHint(4, 17, "l")),
            List.of(
                errorProne(
                    4,
                    17,
                    "[UnusedVariable] The local variable 'l' is never read.",
                    DiagnosticSeverity.Hint,
                    "compiler.note.error.prone")));

    assertThat(merged)
        .singleElement()
        .satisfies(
            diagnostic ->
                assertThat(diagnostic.getMessage().getLeft()).startsWith("[UnusedVariable]"));
  }

  // Under -Werror the finding fails the build: it replaces the hint but is not faded as dead code.
  @Test
  void withWriteOnly_errorProneUnusedAsAnError_replacesTheHintUnfaded() {
    final List<Diagnostic> merged =
        WritePass.withWriteOnly(
            List.of(unusedHint(4, 17, "l")),
            List.of(
                errorProne(
                    4,
                    17,
                    "[UnusedVariable] The local variable 'l' is never read. (-Werror)",
                    DiagnosticSeverity.Error,
                    ERROR_PRONE_WARNING)));

    assertThat(merged)
        .singleElement()
        .satisfies(
            diagnostic -> {
              assertThat(diagnostic.getSeverity()).isEqualTo(DiagnosticSeverity.Error);
              assertThat(diagnostic.getTags()).isNull();
            });
  }

  // A failed write's causes merge first, then its kept findings: the faded finding the first merge
  // made is not taken for a hint by the second, and the finding's own fields and tags are kept.
  @Test
  void withWriteOnly_mergedTwice_keepsTheFindingTheFirstMergeFaded() {
    final Diagnostic written =
        errorProne(4, 17, "[UnusedVariable] The local variable 'l' is never read.");
    written.setTags(List.of(DiagnosticTag.Deprecated));
    final List<Diagnostic> once =
        WritePass.withWriteOnly(List.of(unusedHint(4, 17, "l")), List.of(written));
    final var other =
        new Diagnostic(
            new Range(new Position(4, 17), new Position(4, 30)),
            "[UnusedVariable] The local variable 'l' is never read.",
            DiagnosticSeverity.Warning,
            "lathe",
            ERROR_PRONE_WARNING);

    final List<Diagnostic> twice = WritePass.withWriteOnly(once, List.of(other));

    assertThat(twice).hasSize(2);
    assertThat(twice.get(0).getTags())
        .containsExactly(DiagnosticTag.Deprecated, DiagnosticTag.Unnecessary);
  }
}
