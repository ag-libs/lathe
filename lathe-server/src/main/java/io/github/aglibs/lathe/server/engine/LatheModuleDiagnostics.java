package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/** Compiler diagnostics for one reactor module after a {@code verify_change} recompile. */
public record LatheModuleDiagnostics(String module, List<LatheDiagnostic> diagnostics) {

  public LatheModuleDiagnostics {
    ValidCheck.check().notNull(module, "module").validate();
    diagnostics = List.copyOf(diagnostics);
  }
}
