package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

// Several sources written in one javac task: the classes it wrote, and each source's diagnostics,
// including what only a write reports (annotation processor and compiler plugin findings). javac
// writes no class at all when any source has an error, so a failed source means nothing was
// written.
public record BatchWrite(
    Set<String> writtenBinaryNames, Map<String, List<Diagnostic>> diagnosticsByUri) {
  public BatchWrite {
    ValidCheck.check()
        .notNull(writtenBinaryNames, "writtenBinaryNames")
        .notNull(diagnosticsByUri, "diagnosticsByUri")
        .validate();
    writtenBinaryNames = Set.copyOf(writtenBinaryNames);
    diagnosticsByUri =
        diagnosticsByUri.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
  }

  public Set<String> failedUris() {
    return diagnosticsByUri.entrySet().stream()
        .filter(entry -> entry.getValue().stream().anyMatch(BatchWrite::isError))
        .map(Map.Entry::getKey)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static boolean isError(final Diagnostic diagnostic) {
    return diagnostic.getSeverity() == DiagnosticSeverity.Error;
  }
}
