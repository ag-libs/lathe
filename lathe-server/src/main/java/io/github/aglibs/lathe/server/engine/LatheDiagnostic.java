package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

// A diagnostic with the file it is in, for an answer that spans many files; the path as on a
// LatheLocation, line and column 0-based.
public record LatheDiagnostic(
    String path, int line, int column, Severity severity, String message) {

  // In the order an answer lists them, most severe first.
  public enum Severity {
    ERROR("Error"),
    WARNING("Warning"),
    INFORMATION("Information"),
    HINT("Hint");

    private final String label;

    Severity(final String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  public LatheDiagnostic {
    ValidCheck.check()
        .notNull(path, "path")
        .isNonNegative(line, "line")
        .isNonNegative(column, "column")
        .notNull(severity, "severity")
        .notNull(message, "message")
        .validate();
  }

  // An LSP diagnostic of the file at path; one without a severity is an error, as LSP reads it.
  public static LatheDiagnostic of(final String path, final Diagnostic diagnostic) {
    final var start = diagnostic.getRange().getStart();
    final var message = diagnostic.getMessage();
    return new LatheDiagnostic(
        path,
        start.getLine(),
        start.getCharacter(),
        severity(diagnostic.getSeverity()),
        message.isLeft() ? message.getLeft() : message.getRight().getValue());
  }

  private static Severity severity(final DiagnosticSeverity severity) {
    if (severity == null) {
      return Severity.ERROR;
    }

    return switch (severity) {
      case Error -> Severity.ERROR;
      case Warning -> Severity.WARNING;
      case Information -> Severity.INFORMATION;
      case Hint -> Severity.HINT;
    };
  }
}
