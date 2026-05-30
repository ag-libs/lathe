package io.github.aglibs.lathe.server.module;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import org.eclipse.lsp4j.Diagnostic;

public record CompileResponse(String uri, long generation, List<Diagnostic> diagnostics) {
  public CompileResponse {
    ValidCheck.check().notNull(uri, "uri").notNull(diagnostics).validate();
    ;
  }
}
