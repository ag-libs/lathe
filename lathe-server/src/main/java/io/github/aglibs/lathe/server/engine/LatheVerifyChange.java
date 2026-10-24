package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * The result of a {@code verify_change} call: the compiler diagnostics of the recompiled change set
 * grouped by module, plus the cross-module remainder to hand off to the build.
 *
 * <p>{@code deferral} is non-null when the in-process recompile was refused (a pending build-file
 * sync, or a running build); the caller should then run {@code suggestedSync} rather than trust an
 * empty diagnostic set. When it ran, {@code deferral} is null, {@code affectedModules} names the
 * downstream modules a cross-module change may break, and {@code suggestedSync} is the sync that
 * builds and verifies them (or null when nothing is downstream). The changed files' callers are
 * checked in their own and in dependent modules up to a cap; {@code uncheckedFiles} counts the
 * callers past it, left to {@code suggestedSync}.
 */
public record LatheVerifyChange(
    String deferral,
    List<LatheModuleDiagnostics> perModule,
    List<String> affectedModules,
    String suggestedSync,
    int uncheckedFiles) {

  public LatheVerifyChange {
    ValidCheck.check()
        .notNull(perModule, "perModule")
        .notNull(affectedModules, "affectedModules")
        .isNonNegative(uncheckedFiles, "uncheckedFiles")
        .validate();
    perModule = List.copyOf(perModule);
    affectedModules = List.copyOf(affectedModules);
  }
}
