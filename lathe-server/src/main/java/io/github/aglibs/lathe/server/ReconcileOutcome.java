package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Path;
import java.util.List;

/**
 * What an on-demand verify reconcile did: the sources it recompiled into the mirror, or the reason
 * it refused. A refusal ({@code deferral != NONE}) means the in-process recompile could not be
 * trusted (a pending POM sync, too many files, or a reactor build in flight), so the caller must
 * fall back to Maven instead of reporting diagnostics against a stale classpath.
 */
public record ReconcileOutcome(List<Path> reacted, DeferReason deferral) {

  public ReconcileOutcome {
    ValidCheck.check().notNull(deferral, "deferral").validate();
    reacted = List.copyOf(reacted);
  }

  /** Why an in-process verify recompile was refused; {@link #NONE} when it ran. */
  public enum DeferReason {
    NONE,
    POM_PENDING,
    BULK_CHANGE,
    BUILD_IN_PROGRESS
  }
}
