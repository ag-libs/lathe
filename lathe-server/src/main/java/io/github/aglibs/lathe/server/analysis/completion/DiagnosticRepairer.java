package io.github.aglibs.lathe.server.analysis.completion;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

final class DiagnosticRepairer implements SourceRepairer {

  @Override
  public Optional<String> repair(
      final String source, final List<Diagnostic<? extends JavaFileObject>> diagnostics) {
    for (final var d : diagnostics) {
      if (d.getKind() != Diagnostic.Kind.ERROR) {
        continue;
      }

      final var insert = insertionFor(d.getMessage(Locale.ENGLISH));
      if (insert == null) {
        continue;
      }

      final long pos = d.getPosition();
      if (pos == Diagnostic.NOPOS || pos < 0 || pos > source.length()) {
        continue;
      }

      final int ipos = (int) pos;
      return Optional.of(source.substring(0, ipos) + insert + source.substring(ipos));
    }

    return Optional.empty();
  }

  private static String insertionFor(final String message) {
    if (message.contains("')' expected")) {
      return ")";
    }

    if (message.contains("']' expected")) {
      return "]";
    }

    if (message.contains("'}' expected")) {
      return "}";
    }

    if (message.contains("';' expected")) {
      return ";";
    }

    return null;
  }
}
