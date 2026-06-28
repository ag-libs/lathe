package io.github.aglibs.lathe.server.module;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Set;
import org.eclipse.lsp4j.Diagnostic;

public record CompileResponse(
    String uri, long generation, List<Diagnostic> diagnostics, Set<String> writtenBinaryNames) {
  public CompileResponse {
    ValidCheck.check()
        .notNull(uri, "uri")
        .notNull(diagnostics)
        .notNull(writtenBinaryNames)
        .validate();
    diagnostics = List.copyOf(diagnostics);
    writtenBinaryNames = Set.copyOf(writtenBinaryNames);
  }
}
