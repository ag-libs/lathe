package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeIndexFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class WorkspaceTypeIndex {

  private static final Logger LOG = Logger.getLogger(WorkspaceTypeIndex.class.getName());

  private final List<TypeIndexEntry> staticEntries;
  private final Set<String> reactorBinaryNames;
  private final NavigableMap<String, List<TypeIndexEntry>> bySimpleNameLower;
  private final NavigableMap<String, TypeIndexEntry> byBinaryName;
  private final Map<String, List<TypeIndexEntry>> directSubtypesByParent;

  private WorkspaceTypeIndex(
      final List<TypeIndexEntry> staticEntries,
      final Set<String> reactorBinaryNames,
      final NavigableMap<String, List<TypeIndexEntry>> bySimpleNameLower,
      final NavigableMap<String, TypeIndexEntry> byBinaryName,
      final Map<String, List<TypeIndexEntry>> directSubtypesByParent) {
    this.staticEntries = staticEntries;
    this.reactorBinaryNames = reactorBinaryNames;
    this.bySimpleNameLower = bySimpleNameLower;
    this.byBinaryName = byBinaryName;
    this.directSubtypesByParent = directSubtypesByParent;
  }

  public static WorkspaceTypeIndex empty() {
    return new WorkspaceTypeIndex(
        List.of(),
        Set.of(),
        Collections.emptyNavigableMap(),
        Collections.emptyNavigableMap(),
        Map.of());
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
    final Set<String> reactorBinaryNames =
        reactorEntries.stream()
            .flatMap(List::stream)
            .map(TypeIndexEntry::binaryName)
            .collect(Collectors.toUnmodifiableSet());
    final List<TypeIndexEntry> deduped = deduplicate(staticEntries, reactorEntries);
    final TreeMap<String, List<TypeIndexEntry>> mutable =
        deduped.stream()
            .filter(
                e ->
                    e.typeNameCandidate()
                        || (reactorBinaryNames.contains(e.binaryName()) && e.isTopLevel()))
            .collect(
                Collectors.groupingBy(
                    e -> e.simpleName().toLowerCase(),
                    TreeMap::new,
                    Collectors.collectingAndThen(Collectors.toList(), List::copyOf)));
    final NavigableMap<String, List<TypeIndexEntry>> map =
        Collections.unmodifiableNavigableMap(mutable);
    final var byBinaryName = new TreeMap<String, TypeIndexEntry>();
    for (final TypeIndexEntry entry : deduped) {
      byBinaryName.put(entry.binaryName(), entry);
    }

    final Map<String, List<TypeIndexEntry>> directSubtypesByParent =
        deduped.stream()
            .flatMap(
                entry -> entry.directSupertypes().stream().map(parent -> Map.entry(parent, entry)))
            .collect(
                Collectors.groupingBy(
                    Map.Entry::getKey,
                    Collectors.mapping(
                        Map.Entry::getValue,
                        Collectors.collectingAndThen(Collectors.toList(), List::copyOf))));
    return new WorkspaceTypeIndex(
        staticEntries,
        reactorBinaryNames,
        map,
        Collections.unmodifiableNavigableMap(byBinaryName),
        Map.copyOf(directSubtypesByParent));
  }

  private static List<TypeIndexEntry> deduplicate(
      final List<TypeIndexEntry> staticEntries,
      final Collection<List<TypeIndexEntry>> reactorEntries) {
    final var seen = new LinkedHashMap<String, TypeIndexEntry>();
    final var all =
        Stream.concat(staticEntries.stream(), reactorEntries.stream().flatMap(List::stream));
    for (final TypeIndexEntry entry : (Iterable<TypeIndexEntry>) all::iterator) {
      final TypeIndexEntry existing = seen.put(entry.binaryName(), entry);
      if (existing != null) {
        seen.put(entry.binaryName(), existing);
        LOG.fine(() -> "[type-index] duplicate type %s skipped".formatted(entry.binaryName()));
      }
    }
    return List.copyOf(seen.values());
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
    final Comparator<TypeIndexEntry> order =
        Comparator.<TypeIndexEntry, Boolean>comparing(
                e -> !reactorBinaryNames.contains(e.binaryName()))
            .thenComparing(TypeIndexEntry::binaryName);
    return bySimpleNameLower.subMap(lower, lower + "￿").values().stream()
        .flatMap(List::stream)
        .sorted(order)
        .limit(limit)
        .toList();
  }

  public List<String> searchByBinaryPrefix(final String prefix, final int limit) {
    final Set<String> seen = new LinkedHashSet<>();
    // "￿" (U+FFFF) is the highest BMP char — acts as an exclusive upper bound for all ASCII binary
    // names
    for (final TypeIndexEntry entry : byBinaryName.subMap(prefix, prefix + "￿").values()) {
      final String remainder = entry.binaryName().substring(prefix.length());
      if (remainder.isEmpty() || remainder.charAt(0) == '$') {
        continue;
      }

      int end = remainder.length();
      final int dot = remainder.indexOf('.');
      final int dollar = remainder.indexOf('$');
      if (dot >= 0) {
        end = dot;
      }

      if (dollar >= 0) {
        end = Math.min(end, dollar);
      }
      seen.add(remainder.substring(0, end));
      if (seen.size() >= limit) {
        break;
      }
    }
    return List.copyOf(seen);
  }

  public boolean isReactorType(final String binaryName) {
    return reactorBinaryNames.contains(binaryName);
  }

  public Optional<TypeIndexEntry> findType(final String binaryName) {
    return Optional.ofNullable(byBinaryName.get(binaryName));
  }

  public List<TypeIndexEntry> directSupertypes(final String binaryName) {
    return findType(binaryName).stream()
        .flatMap(entry -> entry.directSupertypes().stream())
        .flatMap(name -> findType(name).stream())
        .toList();
  }

  public List<TypeIndexEntry> directSubtypes(final String binaryName) {
    return directSubtypesByParent.getOrDefault(binaryName, List.of()).stream().distinct().toList();
  }

  public List<TypeIndexEntry> transitiveSubtypes(final String binaryName) {
    final var visited = new HashSet<String>();
    visited.add(binaryName);
    final var pending = new ArrayDeque<>(directSubtypes(binaryName));
    final var results = new ArrayList<TypeIndexEntry>();
    while (!pending.isEmpty()) {
      final var subtype = pending.removeFirst();
      if (visited.add(subtype.binaryName())) {
        results.add(subtype);
        pending.addAll(directSubtypes(subtype.binaryName()));
      }
    }
    return List.copyOf(results);
  }
}
