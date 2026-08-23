package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.schema.RunKind;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The merged, effective run configuration: every entry of both layers after field-level merge.
 * Lookups are linear because a config file holds only a handful of entries.
 */
public record RunOverlaySet(List<RunItem> entries) {

  public RunOverlaySet {
    entries = List.copyOf(entries);
  }

  /**
   * The overlay for a run of {@code (module, kind)}: the module-specific entry if present, else the
   * workspace-wide entry (one authored with no {@code module}), else a built-in no-op. The most
   * specific match wins as a whole.
   */
  public RunItem defaultFor(final String module, final RunKind kind) {
    return matching(module, kind)
        .or(() -> matching(null, kind))
        .orElseGet(() -> RunItem.empty(module, kind));
  }

  private Optional<RunItem> matching(final String module, final RunKind kind) {
    return entries.stream()
        .filter(item -> Objects.equals(item.module(), module) && item.kind() == kind)
        .findFirst();
  }
}
