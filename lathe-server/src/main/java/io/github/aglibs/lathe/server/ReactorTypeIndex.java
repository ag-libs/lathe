package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.WorkspaceSymbolResolver;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.core.typeindex.ClassFileTypeScanner;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.SymbolInformation;

// The workspace type index and the reactor shards it is built from: each tree's bytecode types plus
// the types of its sources not built yet. Event-loop state: every entry point checks the loop, and
// a rebuild runs off it and is applied back on it.
final class ReactorTypeIndex {

  private static final Logger LOG = Logger.getLogger(ReactorTypeIndex.class.getName());

  private final ServerEventLoop eventLoop;
  private final WorkspaceState state;
  // The open documents' paths: a source open in the editor is indexed before it is built.
  private final Supplier<Set<Path>> openSourcePaths;
  private WorkspaceTypeIndex typeIndex = WorkspaceTypeIndex.empty();
  private final Map<ModuleSourceConfig, List<TypeIndexEntry>> reactorShards = new LinkedHashMap<>();
  // Each name's declarations, grouped by the source tree declaring them: replacing a shard drops
  // its own in one step, and adding one never reads the rest (a common supertype gathers tens of
  // thousands of subtypes).
  private final Map<String, Map<ModuleSourceConfig, Set<Declaration>>> declarations =
      new HashMap<>();
  private final Map<String, Map<ModuleSourceConfig, Set<Declaration>>> directSubtypeDeclarations =
      new HashMap<>();
  // MODULE_SYSTEM only: the bytecode base scanned once (modules/ changes just on make+sync), kept
  // apart from reactorShards so per-save delta refresh never rescans it. Empty for CLASSPATH.
  private final Map<ModuleSourceConfig, List<TypeIndexEntry>> reactorBaseShards =
      new LinkedHashMap<>();
  // Reactor type-usage document frequency, aggregated on a full scan; feeds completion ranking.
  private Map<String, Integer> reactorUsageCounts = Map.of();
  // Per-config lambda/method-ref target index (functional interface → implementing classes) and its
  // merged view; lets go-to-implementation find lambdas whose source never names the interface.
  private final Map<ModuleSourceConfig, Map<String, List<String>>> reactorLambdaShards =
      new LinkedHashMap<>();
  private Map<String, List<String>> reactorLambdaImplementors = Map.of();
  // A pass rescanned a shard: rebuild once no pass is left to run.
  private boolean reactorIndexStale;
  private long reactorIndexBuilds;
  // Done once the last rebuild is applied (or superseded): MCP answers wait for it, so a type the
  // pass just wrote is already searchable.
  private CompletableFuture<Void> reactorIndexRebuilt = CompletableFuture.completedFuture(null);

  ReactorTypeIndex(
      final ServerEventLoop eventLoop,
      final WorkspaceState state,
      final Supplier<Set<Path>> openSourcePaths) {
    this.eventLoop = eventLoop;
    this.state = state;
    this.openSourcePaths = openSourcePaths;
  }

  // Scans every tree of the current workspace and builds the index from scratch.
  void load() {
    eventLoop.checkOnLoop();
    reactorShards.clear();
    declarations.clear();
    directSubtypeDeclarations.clear();
    reactorBaseShards.clear();
    reactorLambdaShards.clear();
    scanReactorShards();
    typeIndex =
        WorkspaceTypeIndex.build(state.manifest().typeIndexShardPaths(), reactorShards.values())
            .withUsageCounts(reactorUsageCounts);
  }

  WorkspaceTypeIndex current() {
    eventLoop.checkOnLoop();
    return typeIndex;
  }

  // Functional interface -> the classes implementing it with a lambda or method reference.
  Map<String, List<String>> lambdaImplementors() {
    eventLoop.checkOnLoop();
    return reactorLambdaImplementors;
  }

  // The index with the given types of open files added, for a code action that must see types no
  // build has written yet.
  CompletableFuture<WorkspaceTypeIndex> withOpenEntries(final List<TypeIndexEntry> openEntries) {
    eventLoop.checkOnLoop();
    if (openEntries.isEmpty()) {
      return CompletableFuture.completedFuture(typeIndex);
    }

    return buildOffLoop(
        typeIndex, Stream.concat(reactorShards.values().stream(), Stream.of(openEntries)).toList());
  }

  // Binary names are unique within a compile, not across the workspace.
  Optional<ModuleSourceConfig> declaringConfig(
      final String binaryName, final ModuleSourceConfig asking) {
    eventLoop.checkOnLoop();
    final List<ModuleSourceConfig> owners = declaringConfigs(binaryName);
    if (owners.size() == 1) {
      return Optional.of(owners.get(0));
    }

    if (asking == null) {
      return Optional.empty();
    }

    return state.registry().visibleConfigs(asking).stream().filter(owners::contains).findFirst();
  }

  List<ModuleSourceConfig> declaringConfigs(final String binaryName) {
    eventLoop.checkOnLoop();
    return List.copyOf(declarations.getOrDefault(binaryName, Map.of()).keySet());
  }

  boolean sameDeclaration(
      final String binaryName,
      final ModuleSourceConfig declaring,
      final ModuleSourceConfig asking) {
    eventLoop.checkOnLoop();
    return declaringConfigs(binaryName).size() < 2
        || declaringConfig(binaryName, asking).filter(owner -> owner.equals(declaring)).isPresent();
  }

  Set<String> duplicateNames() {
    eventLoop.checkOnLoop();
    return declarations.keySet().stream()
        .filter(name -> declared(declarations, name).count() > 1)
        .collect(Collectors.toUnmodifiableSet());
  }

  List<SymbolInformation> symbols(final String query, final boolean reactorOnly) {
    eventLoop.checkOnLoop();
    final Set<String> duplicates = duplicateNames();
    final List<SymbolInformation> ordinary =
        WorkspaceSymbolResolver.resolve(
            query,
            typeIndex,
            state.typeSourceDirs(),
            entry ->
                !duplicates.contains(entry.binaryName())
                    && (!reactorOnly || typeIndex.isReactorType(entry.binaryName())));
    if (duplicates.isEmpty()) {
      return ordinary;
    }

    final var results = new ArrayList<>(ordinary);
    for (final var shard : reactorShards.entrySet()) {
      final List<TypeIndexEntry> entries =
          shard.getValue().stream()
              .filter(entry -> duplicates.contains(entry.binaryName()))
              .toList();
      if (!entries.isEmpty()) {
        final WorkspaceTypeIndex index =
            WorkspaceTypeIndex.empty().withReactorEntries(List.of(entries));
        results.addAll(
            WorkspaceSymbolResolver.resolve(
                query, index, shard.getKey().searchRoots(), entry -> true));
      }
    }

    return List.copyOf(results);
  }

  record Declaration(ModuleSourceConfig owner, TypeIndexEntry entry) {
    Declaration {
      ValidCheck.check().notNull(owner, "owner").notNull(entry, "entry").validate();
    }
  }

  Optional<TypeIndexEntry> entryFor(final String binaryName, final ModuleSourceConfig asking) {
    eventLoop.checkOnLoop();
    return declaringConfig(binaryName, asking)
        .flatMap(
            owner ->
                declarations
                    .getOrDefault(binaryName, Map.of())
                    .getOrDefault(owner, Set.of())
                    .stream()
                    .map(Declaration::entry)
                    .findFirst());
  }

  // Candidate discovery retains all parent edges; javac checks them in each owning compile.
  List<Declaration> subtypes(final String binaryName) {
    eventLoop.checkOnLoop();
    final var visited = new LinkedHashSet<String>();
    final var pending = new ArrayDeque<String>();
    pending.add(binaryName);
    while (!pending.isEmpty()) {
      final String name = pending.removeFirst();
      if (!visited.add(name)) {
        continue;
      }

      for (final TypeIndexEntry subtype : typeIndex.directSubtypes(name)) {
        pending.add(subtype.binaryName());
      }

      for (final Declaration subtype : declared(directSubtypeDeclarations, name).toList()) {
        pending.add(subtype.entry().binaryName());
      }
    }

    visited.remove(binaryName);
    return visited.stream().flatMap(name -> declared(declarations, name)).toList();
  }

  Set<String> ambiguousHierarchyNames() {
    eventLoop.checkOnLoop();
    final Set<String> duplicates = duplicateNames();
    return Stream.concat(
            duplicates.stream(),
            duplicates.stream()
                .flatMap(name -> subtypes(name).stream())
                .map(declaration -> declaration.entry().binaryName()))
        .collect(Collectors.toUnmodifiableSet());
  }

  private void replaceShard(final ModuleSourceConfig config, final List<TypeIndexEntry> entries) {
    eventLoop.checkOnLoop();
    final List<TypeIndexEntry> previous = reactorShards.put(config, entries);
    if (previous != null) {
      for (final TypeIndexEntry entry : previous) {
        removeDeclarations(declarations, entry.binaryName(), config);
        for (final String parent : entry.directSupertypes()) {
          removeDeclarations(directSubtypeDeclarations, parent, config);
        }
      }
    }

    for (final TypeIndexEntry entry : entries) {
      final var declaration = new Declaration(config, entry);
      addDeclaration(declarations, entry.binaryName(), declaration);
      for (final String parent : entry.directSupertypes()) {
        addDeclaration(directSubtypeDeclarations, parent, declaration);
      }
    }
  }

  private static Stream<Declaration> declared(
      final Map<String, Map<ModuleSourceConfig, Set<Declaration>>> index, final String name) {
    return index.getOrDefault(name, Map.of()).values().stream().flatMap(Set::stream);
  }

  private static void addDeclaration(
      final Map<String, Map<ModuleSourceConfig, Set<Declaration>>> index,
      final String name,
      final Declaration declaration) {
    index
        .computeIfAbsent(name, key -> new LinkedHashMap<>())
        .computeIfAbsent(declaration.owner(), key -> new LinkedHashSet<>())
        .add(declaration);
  }

  private static void removeDeclarations(
      final Map<String, Map<ModuleSourceConfig, Set<Declaration>>> index,
      final String name,
      final ModuleSourceConfig owner) {
    final Map<ModuleSourceConfig, Set<Declaration>> byOwner = index.get(name);
    if (byOwner == null) {
      return;
    }

    byOwner.remove(owner);
    if (byOwner.isEmpty()) {
      index.remove(name);
    }
  }

  // Rebuilds the index if a shard was refreshed since the last build.
  void rebuildIfStale() {
    eventLoop.checkOnLoop();
    if (reactorIndexStale) {
      rebuild();
    }
  }

  // Done once the last rebuild is applied (or superseded).
  CompletableFuture<Void> rebuilt() {
    eventLoop.checkOnLoop();
    return reactorIndexRebuilt;
  }

  private void scanReactorShards() {
    final var counts = new HashMap<String, Integer>();
    for (final var config : state.registry().allConfigs()) {
      final ClassFileTypeScanner.ReactorScan scan = scanReactorDir(config);
      reactorBaseShards.put(config, scan.entries());
      replaceShard(config, withSourceDelta(config, scan.entries()));
      reactorLambdaShards.put(config, scan.lambdaImplementors());
      scan.referenceCounts().forEach((name, count) -> counts.merge(name, count, Integer::sum));
    }

    reactorUsageCounts = Map.copyOf(counts);
    reactorLambdaImplementors = mergeLambdaShards();
  }

  private Map<String, List<String>> mergeLambdaShards() {
    return reactorLambdaShards.values().stream()
        .flatMap(shard -> shard.entrySet().stream())
        .collect(
            Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (left, right) -> Stream.concat(left.stream(), right.stream()).toList()));
  }

  // Rescans one tree after a write, marking the index for a rebuild.
  void refreshShard(final ModuleSourceConfig config) {
    eventLoop.checkOnLoop();
    // Incremental save. External output reuses the cached base (build changes only on make+sync)
    // and re-derives the delta; Maven rescans the freshly recompiled mirror.
    final List<TypeIndexEntry> base;
    if (config.externalOutput()) {
      base = reactorBaseShards.getOrDefault(config, List.of());
    } else {
      final ClassFileTypeScanner.ReactorScan scan = scanReactorDir(config);
      base = scan.entries();
      reactorLambdaShards.put(config, scan.lambdaImplementors());
    }

    reactorBaseShards.put(config, base);
    replaceShard(config, withSourceDelta(config, base));
    reactorIndexStale = true;
  }

  // Built off the event loop, as a rebuild takes 100-200ms on a large reactor and would hold
  // back the publishes queued behind it; applied only if the index is still the one it was built
  // from and no newer build started.
  private void rebuild() {
    reactorIndexStale = false;
    reactorLambdaImplementors = mergeLambdaShards();
    final WorkspaceTypeIndex base = typeIndex;
    final List<List<TypeIndexEntry>> shards = List.copyOf(reactorShards.values());
    final long build = ++reactorIndexBuilds;
    final var rebuilt = new CompletableFuture<Void>();
    reactorIndexRebuilt = rebuilt;
    buildOffLoop(base, shards)
        .whenComplete(
            (index, error) -> {
              if (error != null) {
                LOG.log(Level.SEVERE, error, () -> "[type-index] reactor rebuild failed");
              } else if (typeIndex == base && build == reactorIndexBuilds) {
                typeIndex = index;
              }

              rebuilt.complete(null);
            });
  }

  // Built off the loop, as a build takes 100-200ms on a large reactor, and back on the loop with
  // the result.
  private CompletableFuture<WorkspaceTypeIndex> buildOffLoop(
      final WorkspaceTypeIndex base, final List<List<TypeIndexEntry>> shards) {
    return eventLoop.rejoin(CompletableFuture.supplyAsync(() -> base.withReactorEntries(shards)));
  }

  // Index types from dirty/open sources not yet built, so they show in symbols before their class
  // exists: for external output until the next make+sync, and for the Maven mirror whenever a
  // compile has not caught up (a write still pending, or a file that does not compile). Keyed on
  // disk staleness, not open buffers (so they survive closing); the bytecode
  // entry, with its real kind, wins once the class is built.
  private List<TypeIndexEntry> withSourceDelta(
      final ModuleSourceConfig config, final List<TypeIndexEntry> base) {
    final Set<String> known =
        base.stream().map(TypeIndexEntry::binaryName).collect(Collectors.toUnmodifiableSet());
    final List<TypeIndexEntry> delta =
        deltaSourceFiles(config).stream()
            .flatMap(
                file ->
                    config
                        .sourceRootOf(file)
                        .flatMap(root -> SourceTypeScanner.deriveEntry(root, file))
                        .stream())
            .filter(entry -> !known.contains(entry.binaryName()))
            .toList();
    return delta.isEmpty() ? base : Stream.concat(base.stream(), delta.stream()).toList();
  }

  // Files the bytecode base may not cover: on-disk sources stale vs their compile stamp, plus open
  // buffers, restricted to this module's source roots.
  private List<Path> deltaSourceFiles(final ModuleSourceConfig config) {
    return Stream.concat(TreeState.read(config).stale().stream(), openSourcePaths.get().stream())
        .filter(path -> config.sourceRootOf(path).isPresent())
        .distinct()
        .toList();
  }

  private static ClassFileTypeScanner.ReactorScan scanReactorDir(final ModuleSourceConfig config) {
    final Path scanRoot = reactorScanRoot(config);
    try {
      return ClassFileTypeScanner.scanReactorDirectory(scanRoot);
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[type-index] reactor scan failed: %s".formatted(scanRoot));
      return new ClassFileTypeScanner.ReactorScan(List.of(), Map.of(), Map.of());
    }
  }

  // External-output configs scan the build output dir directly; Maven scans the .lathe mirror.
  private static Path reactorScanRoot(final ModuleSourceConfig config) {
    return config.externalOutput() ? config.outputDir() : config.latheClassesDir();
  }
}
