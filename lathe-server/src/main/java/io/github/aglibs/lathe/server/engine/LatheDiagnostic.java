package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import org.eclipse.lsp4j.Diagnostic;

// A diagnostic with the file it is in, for an answer that spans many files; the path as on a
// LatheLocation.
public record LatheDiagnostic(String path, Diagnostic diagnostic) {

  public LatheDiagnostic {
    ValidCheck.check().notNull(path, "path").notNull(diagnostic, "diagnostic").validate();
  }
}
