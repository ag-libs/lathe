package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.lathe.server.ReconcileOutcome;
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
 * builds and verifies them (or null when nothing is downstream). {@code perModule} covers the
 * changed files and their callers in their own and in dependent modules. {@code failedWrites} names
 * the changed files the build's own compile fails: their classes are those of their last good
 * compile, so their callers were checked against their edited source.
 */
public record LatheVerifyChange(
    ReconcileOutcome.DeferReason deferral,
    List<LatheModuleDiagnostics> perModule,
    List<String> affectedModules,
    String suggestedSync,
    List<String> failedWrites) {

  public LatheVerifyChange {
    ValidCheck.check()
        .notNull(perModule, "perModule")
        .notNull(affectedModules, "affectedModules")
        .notNull(failedWrites, "failedWrites")
        .validate();
    perModule = List.copyOf(perModule);
    affectedModules = List.copyOf(affectedModules);
    failedWrites = List.copyOf(failedWrites);
  }
}
