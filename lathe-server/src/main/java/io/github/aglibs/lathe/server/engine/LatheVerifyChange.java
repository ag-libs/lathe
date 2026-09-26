package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * The result of a {@code verify_change} call: the compiler diagnostics of the recompiled change set
 * grouped by module, plus the cross-module remainder to hand off to Maven.
 *
 * <p>{@code deferral} is non-null when the in-process recompile was refused (a pending POM sync, a
 * bulk change, or a running build); the caller should then run {@code suggestedMvn} rather than
 * trust an empty diagnostic set. When it ran, {@code deferral} is null, {@code affectedModules}
 * names the downstream modules a cross-module change may break, and {@code suggestedMvn} is the
 * precise scoped build to verify them (or null when nothing is downstream).
 */
public record LatheVerifyChange(
    String deferral,
    List<LatheModuleDiagnostics> perModule,
    List<String> affectedModules,
    String suggestedMvn) {

  public LatheVerifyChange {
    ValidCheck.check()
        .notNull(perModule, "perModule")
        .notNull(affectedModules, "affectedModules")
        .validate();
    perModule = List.copyOf(perModule);
    affectedModules = List.copyOf(affectedModules);
  }
}
