package io.github.aglibs.lathe.core;

import io.github.aglibs.lathe.core.schema.CompiledStampsData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

// Per-source compile stamps (root-relative path -> mtime), one map per module source tree.
public final class CompiledStamps {

  private CompiledStamps() {}

  // Missing or corrupt file -> empty map: the scan then reads every source stale (self-healing).
  public static Map<String, Long> load(final Path moduleDir, final String sourceTree) {
    final var path = stampsPath(moduleDir, sourceTree);
    if (!Files.exists(path)) {
      return Map.of();
    }

    try {
      final CompiledStampsData data = Json.read(path, CompiledStampsData.class);
      return data != null ? data.stamps() : Map.of();
    } catch (final IOException | RuntimeException e) {
      return Map.of();
    }
  }

  public static void writeAll(
      final Path moduleDir, final String sourceTree, final Map<String, Long> stamps)
      throws IOException {
    Json.write(new CompiledStampsData(stamps), stampsPath(moduleDir, sourceTree));
  }

  public static void record(
      final Path moduleDir, final String sourceTree, final String relPath, final long mtime)
      throws IOException {
    final var current = new HashMap<String, Long>(load(moduleDir, sourceTree));
    current.put(relPath, mtime);
    writeAll(moduleDir, sourceTree, current);
  }

  private static Path stampsPath(final Path moduleDir, final String sourceTree) {
    return moduleDir.resolve(LatheLayout.compiledStampsFileName(sourceTree));
  }
}
