package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.RunKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads the two run-config layers — the shared {@code lathe-run.json} at the reactor root and the
 * machine-local {@code .lathe/run.json} — and field-merges local over shared into one {@link
 * RunOverlaySet}. Each layer is a JSON array of entries. Lathe never writes these files, so no lock
 * is taken; a missing or malformed layer is treated as empty (fail-open), and every run still
 * resolves to the built-in defaults.
 */
public final class RunConfigReader {

  private static final Logger LOG = Logger.getLogger(RunConfigReader.class.getName());

  private final Path workspaceRoot;

  public RunConfigReader(final Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
  }

  public RunOverlaySet read() {
    final List<RunItem> shared =
        readLayer(workspaceRoot.resolve(LatheLayout.RUN_CONFIG_SHARED_FILE), "shared");
    final List<RunItem> local =
        readLayer(
            workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.RUN_CONFIG_LOCAL_FILE),
            "local");
    return new RunOverlaySet(merge(shared, local));
  }

  private static List<RunItem> readLayer(final Path file, final String label) {
    if (!Files.exists(file)) {
      return List.of();
    }

    try {
      final RunItem[] parsed = Json.read(file, RunItem[].class);
      return parsed != null ? List.of(parsed) : List.of();
    } catch (final IOException | RuntimeException e) {
      LOG.log(
          Level.WARNING, e, () -> "[run-config] %s layer unreadable, ignoring".formatted(label));
      return List.of();
    }
  }

  private static List<RunItem> merge(final List<RunItem> shared, final List<RunItem> local) {
    final var merged = new LinkedHashMap<DefaultKey, RunItem>();
    for (final RunItem item : shared) {
      merged.put(defaultKey(item), item);
    }

    for (final RunItem item : local) {
      merged.merge(defaultKey(item), item, RunItem::mergedWith);
    }

    return List.copyOf(merged.values());
  }

  private static DefaultKey defaultKey(final RunItem item) {
    return new DefaultKey(item.module(), item.kind());
  }

  private record DefaultKey(String module, RunKind kind) {}
}
