package io.github.aglibs.lathe.core;

import io.github.aglibs.lathe.core.schema.CompiledStampsData;
import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.core.schema.SourceStamp;
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
  public static Map<Path, SourceStamp> load(final Path moduleDir, final String sourceTree) {
    return byPath(read(moduleDir, sourceTree).stamps());
  }

  public static Map<Path, ContentFingerprint> loadBuilt(
      final Path moduleDir, final String sourceTree) {
    return byPath(read(moduleDir, sourceTree).built());
  }

  // A build's stamps: every source as compiled, and the content it compiled (empty from a front-end
  // that does not record it).
  public static void writeAll(
      final Path moduleDir,
      final String sourceTree,
      final Map<Path, SourceStamp> stamps,
      final Map<Path, ContentFingerprint> built)
      throws IOException {
    Json.write(
        new CompiledStampsData(bySlashed(stamps), bySlashed(built)),
        stampsPath(moduleDir, sourceTree));
  }

  // A build that found the tree up to date compiled nothing, so nothing recorded what was compiled:
  // for a tree the build completed, that is each source's current content. The mtimes stay.
  public static void recordBuiltIfMissing(final Path moduleDir, final ModuleConfigData config)
      throws IOException {
    final CompiledStampsData data = read(moduleDir, config.sourceTree());
    if (!data.built().isEmpty()) {
      return;
    }

    writeAll(moduleDir, config.sourceTree(), byPath(data.stamps()), builtOf(sourcesByKey(config)));
  }

  // Each Java source of the tree by its stamp key (source-root-relative), the annotation-processor
  // output left out: the scan skips it too, and its files could collide with real sources' keys.
  public static Map<Path, Path> sourcesByKey(final ModuleConfigData config) {
    final Path genRoot =
        config.generatedSourcesDir() != null
            ? Path.of(config.generatedSourcesDir()).normalize()
            : null;
    return config.sourceRoots().stream()
        .map(Path::of)
        .filter(root -> !root.normalize().equals(genRoot) && Files.isDirectory(root))
        .flatMap(
            root ->
                IOUtil.unchecked(() -> FileUtil.walkFiles(root, FileUtil::isJavaFile)).stream()
                    .map(source -> Map.entry(root.relativize(source), source)))
        .collect(
            Collectors.toUnmodifiableMap(
                Map.Entry::getKey, Map.Entry::getValue, (first, ignored) -> first));
  }

  public static Map<Path, ContentFingerprint> builtOf(final Map<Path, Path> sourcesByKey) {
    return sourcesByKey.entrySet().stream()
        .collect(
            Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> IOUtil.unchecked(() -> FileUtil.fingerprint(entry.getValue()))));
  }

  // The server's own writes, in one go: what the build compiled stays verify_change's reference.
  public static void record(
      final Path moduleDir, final String sourceTree, final Map<Path, SourceStamp> written)
      throws IOException {
    final CompiledStampsData data = read(moduleDir, sourceTree);
    final var stamps = new HashMap<>(byPath(data.stamps()));
    stamps.putAll(written);
    writeAll(moduleDir, sourceTree, stamps, byPath(data.built()));
  }

  // Drops the stamps only: what the build compiled is unchanged by a deletion, so a deleted file
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
