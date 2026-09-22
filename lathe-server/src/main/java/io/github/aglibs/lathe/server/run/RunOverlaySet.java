package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.schema.RunKind;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The merged, effective run configuration: the {@code defaults} baselines (entries with a null
 * {@code name}) and the named {@code configs} (entries with a name), after field-level layer merge.
 * Lookups are linear because a config file holds only a handful of entries.
 */
public record RunOverlaySet(List<RunItem> entries) {

  public RunOverlaySet {
    entries = List.copyOf(entries);
  }

  // Cursor/gutter baseline, most-specific-wins: (module, kind) -> module-less kind -> built-in.
  // Named configs are excluded so one can never become a silent auto-default.
  public RunItem defaultFor(final String module, final RunKind kind) {
    return matchingBaseline(module, kind)
        .or(() -> matchingBaseline(null, kind))
        .orElseGet(() -> RunItem.empty(module, kind));
  }

  // The named config composed over its matching baseline (config wins), or empty if none.
  public Optional<RunItem> byName(final String name) {
    return entries.stream()
        .filter(item -> item.name() != null && item.name().equals(name))
        .findFirst()
        .map(config -> defaultFor(config.module(), config.kind()).mergedWith(config));
  }

  /** The named configs (excludes baselines), in file order — for selection completion. */
  public List<RunItem> configs() {
    return entries.stream().filter(item -> item.name() != null).toList();
  }

  private Optional<RunItem> matchingBaseline(final String module, final RunKind kind) {
    return entries.stream()
        .filter(
            item ->
                item.name() == null && Objects.equals(item.module(), module) && item.kind() == kind)
        .findFirst();
  }
}
