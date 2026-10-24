package io.github.aglibs.lathe.core;

import io.github.aglibs.lathe.core.schema.CompiledStampsData;
import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// Per-source compile stamps (source-root-relative path -> mtime), one map per module source tree,
// with the content each source had when the build last compiled it. Callers work with Paths; the
// file holds them '/'-separated, so it reads the same on every OS.
public final class CompiledStamps {

  private CompiledStamps() {}

  // Missing or corrupt file -> empty map: the scan then reads every source stale (self-healing).
  public static Map<Path, Long> load(final Path moduleDir, final String sourceTree) {
    return byPath(read(moduleDir, sourceTree).stamps());
  }

  public static Map<Path, ContentFingerprint> loadBuilt(
      final Path moduleDir, final String sourceTree) {
    return byPath(read(moduleDir, sourceTree).built());
  }

  // A build's stamps: every source's mtime, and the content it compiled (empty from a front-end
  // that does not record it).
  public static void writeAll(
      final Path moduleDir,
      final String sourceTree,
      final Map<Path, Long> stamps,
      final Map<Path, ContentFingerprint> built)
      throws IOException {
    Json.write(
        new CompiledStampsData(bySlashed(stamps), bySlashed(built)),
        stampsPath(moduleDir, sourceTree));
  }

  // The server's own write: the mtime only, so what the build compiled stays the reference.
  public static void record(
      final Path moduleDir, final String sourceTree, final Path relPath, final long mtime)
      throws IOException {
    final CompiledStampsData data = read(moduleDir, sourceTree);
    final var stamps = new HashMap<>(byPath(data.stamps()));
    stamps.put(relPath, mtime);
    writeAll(moduleDir, sourceTree, stamps, byPath(data.built()));
  }

  // Drops the mtimes only: what the build compiled is unchanged by a deletion, so a deleted file
  // still counts as changed, and one restored as it was (a branch switch and back) does not.
  public static void prune(final Path moduleDir, final String sourceTree, final Set<Path> relPaths)
      throws IOException {
    final CompiledStampsData data = read(moduleDir, sourceTree);
    final var stamps = new HashMap<>(byPath(data.stamps()));
    if (!stamps.keySet().removeAll(relPaths)) {
      return;
    }

    writeAll(moduleDir, sourceTree, stamps, byPath(data.built()));
  }

  private static CompiledStampsData read(final Path moduleDir, final String sourceTree) {
    final var path = stampsPath(moduleDir, sourceTree);
    if (!Files.exists(path)) {
      return new CompiledStampsData(Map.of(), Map.of());
    }

    try {
      final CompiledStampsData data = Json.read(path, CompiledStampsData.class);
      return data != null ? data : new CompiledStampsData(Map.of(), Map.of());
    } catch (final IOException | RuntimeException e) {
      return new CompiledStampsData(Map.of(), Map.of());
    }
  }

  // A file Windows wrote before stamps were '/'-separated may hold one source under both
  // spellings; the first wins.
  private static <V> Map<Path, V> byPath(final Map<String, V> saved) {
    return saved.entrySet().stream()
        .collect(
            Collectors.toUnmodifiableMap(
                entry -> Path.of(entry.getKey()), Map.Entry::getValue, (first, ignored) -> first));
  }

  private static <V> Map<String, V> bySlashed(final Map<Path, V> map) {
    return map.entrySet().stream()
        .collect(
            Collectors.toUnmodifiableMap(
                entry -> FileUtil.slashed(entry.getKey()), Map.Entry::getValue));
  }

  private static Path stampsPath(final Path moduleDir, final String sourceTree) {
    return moduleDir.resolve(LatheLayout.compiledStampsFileName(sourceTree));
  }
}
