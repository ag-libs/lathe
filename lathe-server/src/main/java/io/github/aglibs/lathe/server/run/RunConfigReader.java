package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.RunKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Reads the two run-config layers — the shared {@code lathe-run.json} and the machine-local {@code
 * .lathe/run.json} — and field-merges local over shared into one {@link RunOverlaySet}. A missing
 * or malformed layer is treated as empty (fail-open), so every run still resolves to the built-in
 * defaults.
 */
public final class RunConfigReader {

  private static final Logger LOG = Logger.getLogger(RunConfigReader.class.getName());

  private final Path workspaceRoot;

  public RunConfigReader(final Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
  }

  public RunOverlaySet read() {
    final RunConfigFile shared =
        readLayer(workspaceRoot.resolve(LatheLayout.RUN_CONFIG_SHARED_FILE), "shared");
    final RunConfigFile local =
        readLayer(
            workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.RUN_CONFIG_LOCAL_FILE),
            "local");

    final List<RunItem> baselineItems =
        mergeByKey(baselines(shared, "shared"), baselines(local, "local"), RunConfigReader::key);
    final List<RunItem> configItems =
        mergeByKey(configs(shared, "shared"), configs(local, "local"), RunItem::name);
    return new RunOverlaySet(Stream.concat(baselineItems.stream(), configItems.stream()).toList());
  }

  private static RunConfigFile readLayer(final Path file, final String label) {
    if (!Files.exists(file)) {
      return RunConfigFile.empty();
    }

    try {
      final RunConfigFile parsed = Json.read(file, RunConfigFile.class);
      return parsed != null ? parsed : RunConfigFile.empty();
    } catch (final IOException | RuntimeException e) {
      LOG.log(
          Level.WARNING, e, () -> "[run-config] %s layer unreadable, ignoring".formatted(label));
      return RunConfigFile.empty();
    }
  }

  private static List<RunItem> baselines(final RunConfigFile file, final String label) {
    return file.defaults().stream()
        .map(entry -> toBaseline(entry, label))
        .filter(Objects::nonNull)
        .toList();
  }

  private static List<RunItem> configs(final RunConfigFile file, final String label) {
    return file.configs().entrySet().stream()
        .map(entry -> toConfig(entry.getKey(), entry.getValue(), label))
        .filter(Objects::nonNull)
        .toList();
  }

  // A baseline pins no target; convert, then drop (with a warning) an invalid or target-bearing
  // one.
  private static RunItem toBaseline(final RunConfigEntry entry, final String label) {
    final RunItem item = convert(null, entry, label);
    if (item != null && item.hasTarget()) {
      LOG.warning(
          () ->
              "[run-config] %s default with a target ignored (baselines pin none)"
                  .formatted(label));
      return null;
    }

    return item;
  }

  // A config must pin a target; convert, then drop (with a warning) an invalid or targetless one.
  private static RunItem toConfig(
      final String name, final RunConfigEntry entry, final String label) {
    final RunItem item = convert(name, entry, label);
    if (item != null && !item.hasTarget()) {
      LOG.warning(() -> "[run-config] %s config '%s' has no target ignored".formatted(label, name));
      return null;
    }

    return item;
  }

  private static RunItem convert(
      final String name, final RunConfigEntry entry, final String label) {
    try {
      return entry.toItem(name);
    } catch (final RuntimeException e) {
      LOG.log(Level.WARNING, e, () -> "[run-config] %s entry invalid, ignoring".formatted(label));
      return null;
    }
  }

  private static <K> List<RunItem> mergeByKey(
      final List<RunItem> shared, final List<RunItem> local, final Function<RunItem, K> key) {
    final var merged = new LinkedHashMap<K, RunItem>();
    shared.forEach(item -> merged.put(key.apply(item), item));
    local.forEach(item -> merged.merge(key.apply(item), item, RunItem::mergedWith));
    return List.copyOf(merged.values());
  }

  private static BaselineKey key(final RunItem item) {
    return new BaselineKey(item.module(), item.kind());
  }

  private record BaselineKey(String module, RunKind kind) {}
}
