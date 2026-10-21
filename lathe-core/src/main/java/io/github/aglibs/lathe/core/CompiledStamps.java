package io.github.aglibs.lathe.core;

import io.github.aglibs.lathe.core.schema.CompiledStampsData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// Per-source compile stamps (source-root-relative path -> mtime), one map per module source tree.
// Callers work with Paths; the file holds them '/'-separated, so it reads the same on every OS.
public final class CompiledStamps {

  private CompiledStamps() {}

  // Missing or corrupt file -> empty map: the scan then reads every source stale (self-healing).
  // A file Windows wrote before stamps were '/'-separated may hold one source under both
  // spellings; the first wins.
  public static Map<Path, Long> load(final Path moduleDir, final String sourceTree) {
    final var path = stampsPath(moduleDir, sourceTree);
    if (!Files.exists(path)) {
      return Map.of();
    }

    try {
      final CompiledStampsData data = Json.read(path, CompiledStampsData.class);
      if (data == null) {
        return Map.of();
      }

      return data.stamps().entrySet().stream()
          .collect(
              Collectors.toUnmodifiableMap(
                  entry -> Path.of(entry.getKey()),
                  Map.Entry::getValue,
                  (first, ignored) -> first));
    } catch (final IOException | RuntimeException e) {
      return Map.of();
    }
  }

  public static void writeAll(
      final Path moduleDir, final String sourceTree, final Map<Path, Long> stamps)
      throws IOException {
    final Map<String, Long> saved =
        stamps.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    entry -> FileUtil.slashed(entry.getKey()), Map.Entry::getValue));
    Json.write(new CompiledStampsData(saved), stampsPath(moduleDir, sourceTree));
  }

  public static void record(
      final Path moduleDir, final String sourceTree, final Path relPath, final long mtime)
      throws IOException {
    final var current = new HashMap<>(load(moduleDir, sourceTree));
    current.put(relPath, mtime);
    writeAll(moduleDir, sourceTree, current);
  }

  public static void prune(final Path moduleDir, final String sourceTree, final Set<Path> relPaths)
      throws IOException {
    final var current = new HashMap<>(load(moduleDir, sourceTree));
    if (!current.keySet().removeAll(relPaths)) {
      return;
    }

    writeAll(moduleDir, sourceTree, current);
  }

  private static Path stampsPath(final Path moduleDir, final String sourceTree) {
    return moduleDir.resolve(LatheLayout.compiledStampsFileName(sourceTree));
  }
}
