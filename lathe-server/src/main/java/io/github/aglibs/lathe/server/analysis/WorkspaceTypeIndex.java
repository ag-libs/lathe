package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeIndexFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class WorkspaceTypeIndex {

  private static final Logger LOG = Logger.getLogger(WorkspaceTypeIndex.class.getName());

  private final List<TypeIndexEntry> staticEntries;
  private final NavigableMap<String, List<TypeIndexEntry>> bySimpleNameLower;

  private WorkspaceTypeIndex(
      final List<TypeIndexEntry> staticEntries,
      final NavigableMap<String, List<TypeIndexEntry>> bySimpleNameLower) {
    this.staticEntries = staticEntries;
    this.bySimpleNameLower = bySimpleNameLower;
  }

  public static WorkspaceTypeIndex empty() {
    return new WorkspaceTypeIndex(List.of(), Collections.emptyNavigableMap());
  }

  public static WorkspaceTypeIndex build(final List<Path> shardPaths) {
    return build(shardPaths, List.of());
  }

  public static WorkspaceTypeIndex build(
      final List<Path> shardPaths, final Collection<List<TypeIndexEntry>> reactorEntries) {
    final var t = Stopwatch.start();
    final List<TypeIndexFile> files =
        shardPaths.stream()
            .filter(WorkspaceTypeIndex::shardExists)
            .flatMap(WorkspaceTypeIndex::loadFile)
            .toList();
    final List<TypeIndexEntry> staticEntries =
        files.stream().flatMap(file -> file.types().stream()).toList();
    final var index = create(staticEntries, reactorEntries);
    LOG.fine(
        () ->
            "[type-index] loaded index: %d simple names from %d/%d shard(s) + %d reactor shard(s) %dms"
                .formatted(
                    index.bySimpleNameLower.size(),
                    files.size(),
                    shardPaths.size(),
                    reactorEntries.size(),
                    t.elapsedMs()));
    return index;
  }

  public WorkspaceTypeIndex withReactorEntries(
      final Collection<List<TypeIndexEntry>> reactorEntries) {
    final var t = Stopwatch.start();
    final var index = create(staticEntries, reactorEntries);
    LOG.fine(
        () ->
            "[type-index] refreshed reactor index: %d simple names from %d static type(s) + %d reactor shard(s) %dms"
                .formatted(
                    index.bySimpleNameLower.size(),
                    staticEntries.size(),
                    reactorEntries.size(),
                    t.elapsedMs()));
    return index;
  }

  private static WorkspaceTypeIndex create(
      final List<TypeIndexEntry> staticEntries,
      final Collection<List<TypeIndexEntry>> reactorEntries) {
    final TreeMap<String, List<TypeIndexEntry>> mutable =
        Stream.concat(staticEntries.stream(), reactorEntries.stream().flatMap(List::stream))
            .collect(
                Collectors.groupingBy(
                    e -> e.simpleName().toLowerCase(),
                    TreeMap::new,
                    Collectors.collectingAndThen(Collectors.toList(), List::copyOf)));

    final NavigableMap<String, List<TypeIndexEntry>> map =
        Collections.unmodifiableNavigableMap(mutable);
    return new WorkspaceTypeIndex(staticEntries, map);
  }

  private static boolean shardExists(final Path shard) {
    if (Files.exists(shard)) {
      return true;
    }

    LOG.fine(() -> "[type-index] shard not found: %s".formatted(shard));
    return false;
  }

  private static Stream<TypeIndexFile> loadFile(final Path shard) {
    try {
      return Stream.of(Json.read(shard, TypeIndexFile.class));
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[type-index] failed to load shard %s".formatted(shard));
      return Stream.empty();
    }
  }

  public List<TypeIndexEntry> search(final String prefix, final int limit) {
    if (prefix.isEmpty()) {
      return List.of();
    }

    final String lower = prefix.toLowerCase();
    return bySimpleNameLower.subMap(lower, lower + "￿").values().stream()
        .flatMap(List::stream)
        .limit(limit)
        .toList();
  }
}
