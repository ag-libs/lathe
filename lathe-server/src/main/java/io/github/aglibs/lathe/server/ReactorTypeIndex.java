package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.SourcelessSymbol;
import io.github.aglibs.lathe.analysis.WorkspaceSymbolResolver;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.typeindex.ClassFileTypeScanner;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
  // The full scan of a refresh runs here, off the loop.
  private final Executor scans;
  // Bumped by every full scan: a refresh applies its result only if no newer one started.
  private long fullScans;
  // The refresh in flight, and whether another was asked for meanwhile: one runs at a time.
  private CompletableFuture<Void> refreshing;
  private boolean refreshAgain;
  // Trees a write rescanned while a refresh was in flight: their newer shard stands.
  private final Set<ModuleSourceConfig> rescannedDuringRefresh = new HashSet<>();

  ReactorTypeIndex(
      final ServerEventLoop eventLoop,
      final WorkspaceState state,
      final Supplier<Set<Path>> openSourcePaths,
      final Executor scans) {
    this.eventLoop = eventLoop;
    this.state = state;
    this.openSourcePaths = openSourcePaths;
    this.scans = scans;
  }

  // Scans every tree of the current workspace and builds the index from scratch, on the loop: at
  // startup and on a reload, which bring new trees.
  void load() {
    eventLoop.checkOnLoop();
    fullScans++;
    refreshAgain = false;
    rescannedDuringRefresh.clear();
    reactorShards.clear();
    declarations.clear();
    directSubtypeDeclarations.clear();
    reactorBaseShards.clear();
    reactorLambdaShards.clear();
    apply(
        scan(
            List.copyOf(state.registry().allConfigs()),
            openSourcePaths.get(),
            state.manifest().typeIndexShardPaths(),
            state.manifest().sourcelessShardPaths()));
  }

  // After a build, the same trees rescanned off the loop and applied back on it: requests meanwhile
  // answer from the index a build behind, where a scan of a large workspace held them for seconds.
  void refresh() {
    eventLoop.checkOnLoop();
    if (refreshing != null) {
      refreshAgain = true;
      return;
    }

    final long scan = ++fullScans;
    rescannedDuringRefresh.clear();
    final var t = Stopwatch.start();
    final List<ModuleSourceConfig> configs = List.copyOf(state.registry().allConfigs());
    final Set<Path> openSources = Set.copyOf(openSourcePaths.get());
    final List<Path> dependencyShards = state.manifest().typeIndexShardPaths();
    final Set<Path> sourcelessShards = state.manifest().sourcelessShardPaths();
    final var refreshed = new CompletableFuture<Void>();
    refreshing = refreshed;
    eventLoop
        .rejoin(
            CompletableFuture.supplyAsync(
                () -> scan(configs, openSources, dependencyShards, sourcelessShards), scans))
        .whenComplete(
            (result, error) -> {
              refreshing = null;
              if (error != null) {
                LOG.log(Level.SEVERE, error, () -> "[refresh] reactor type index failed");
              } else if (scan == fullScans) {
                apply(result);
                LOG.info(() -> "[refresh] reactor type index %dms".formatted(t.elapsedMs()));
                // A document opened or closed meanwhile changes its tree's unbuilt types.
                refreshAgain |= !openSources.equals(Set.copyOf(openSourcePaths.get()));
              }

              // An answer waiting on this refresh waits on the one asked for meanwhile too.
              if (refreshAgain) {
                refreshAgain = false;
                refresh();
                refreshing.thenRun(() -> refreshed.complete(null));
              } else {
                reactorIndexRebuilt.thenRun(() -> refreshed.complete(null));
              }
            });
  }

  // What a full scan finds: each tree's bytecode scan, its shard (with the types of its sources not
  // built yet), the trees whose scan failed, the reactor's usage counts, and the index built from
  // them.
  private record FullScan(
      Map<ModuleSourceConfig, ClassFileTypeScanner.ReactorScan> trees,
      Map<ModuleSourceConfig, List<TypeIndexEntry>> shards,
      Set<ModuleSourceConfig> failed,
      Map<String, Integer> usageCounts,
      WorkspaceTypeIndex index) {
    FullScan {
      ValidCheck.check()
          .notNull(trees, "trees")
          .notNull(shards, "shards")
          .notNull(failed, "failed")
          .notNull(usageCounts, "usageCounts")
          .notNull(index, "index")
          .validate();
      trees = Collections.unmodifiableMap(new LinkedHashMap<>(trees));
      shards = Collections.unmodifiableMap(new LinkedHashMap<>(shards));
      failed = Set.copyOf(failed);
      usageCounts = Map.copyOf(usageCounts);
    }
  }

  // Reads the disk only, so it runs on or off the loop alike.
  private static FullScan scan(
      final List<ModuleSourceConfig> configs,
      final Set<Path> openSources,
      final List<Path> dependencyShards,
      final Set<Path> sourcelessShards) {
    final var trees = new LinkedHashMap<ModuleSourceConfig, ClassFileTypeScanner.ReactorScan>();
    final var shards = new LinkedHashMap<ModuleSourceConfig, List<TypeIndexEntry>>();
    final var failed = new HashSet<ModuleSourceConfig>();
    final var counts = new HashMap<String, Integer>();
    for (final var config : configs) {
      final Optional<ClassFileTypeScanner.ReactorScan> scanned = scanReactorDir(config);
      if (scanned.isEmpty()) {
        failed.add(config);
        continue;
      }

      final ClassFileTypeScanner.ReactorScan tree = scanned.get();
      trees.put(config, tree);
      shards.put(config, withSourceDelta(config, tree.entries(), openSources));
      tree.referenceCounts().forEach((name, count) -> counts.merge(name, count, Integer::sum));
    }

    final Map<String, Integer> usageCounts = Map.copyOf(counts);
    return new FullScan(
        trees,
        shards,
        failed,
        usageCounts,
        WorkspaceTypeIndex.build(dependencyShards, shardsByTree(shards), sourcelessShards)
            .withUsageCounts(usageCounts));
  }

  // A tree a write rescanned meanwhile keeps its newer shard, and one whose scan failed its last
  // one; the index is then rebuilt with them, over the scan's own (its usage counts).
  private void apply(final FullScan scan) {
    scan.shards()
        .forEach(
            (config, shard) -> {
              if (!rescannedDuringRefresh.contains(config)) {
                reactorBaseShards.put(config, scan.trees().get(config).entries());
                reactorLambdaShards.put(config, scan.trees().get(config).lambdaImplementors());
                replaceShard(config, shard);
              }
            });
    reactorUsageCounts = scan.usageCounts();
    reactorLambdaImplementors = mergeLambdaShards();
    typeIndex = scan.index();
    if (!rescannedDuringRefresh.isEmpty() || !scan.failed().isEmpty()) {
      rebuild();
    }
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
  // build has written yet; they count as declared in `tree`, the tree asking.
  CompletableFuture<WorkspaceTypeIndex> withOpenEntries(
      final Path tree, final List<TypeIndexEntry> openEntries) {
    eventLoop.checkOnLoop();
    if (openEntries.isEmpty()) {
      return CompletableFuture.completedFuture(typeIndex);
    }

    final var shards = new LinkedHashMap<>(shardsByTree(reactorShards));
    shards.merge(tree, openEntries, ReactorTypeIndex::concat);
    return buildOffLoop(typeIndex, Collections.unmodifiableMap(shards));
  }

  // The shards keyed by their tree's classes dir, as WorkspaceTypeIndex takes them; two configs on
  // one classes dir share a shard.
  private static Map<Path, List<TypeIndexEntry>> shardsByTree(
      final Map<ModuleSourceConfig, List<TypeIndexEntry>> shards) {
    final var byTree = new LinkedHashMap<Path, List<TypeIndexEntry>>();
    shards.forEach(
        (config, entries) ->
            byTree.merge(config.latheClassesDir(), entries, ReactorTypeIndex::concat));
    return Collections.unmodifiableMap(byTree);
  }

  private static List<TypeIndexEntry> concat(
      final List<TypeIndexEntry> first, final List<TypeIndexEntry> second) {
    return Stream.concat(first.stream(), second.stream()).toList();
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
    return declarations.entrySet().stream()
        .filter(entry -> declarationCount(entry.getValue()) > 1)
        .map(Map.Entry::getKey)
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
            WorkspaceTypeIndex.empty()
                .withReactorEntries(Map.of(shard.getKey().latheClassesDir(), entries));
        results.addAll(
            WorkspaceSymbolResolver.resolve(
                query, index, shard.getKey().searchRoots(), entry -> true));
      }
    }

    return List.copyOf(results);
  }

  // The types a search finds in dependencies without sources, which symbols leaves out: they have
  // no file to point at, only the dependency that declares them.
  List<SourcelessSymbol> sourcelessSymbols(final String query, final boolean reactorOnly) {
    eventLoop.checkOnLoop();
    if (reactorOnly) {
      return List.of();
    }

    return WorkspaceSymbolResolver.sourceless(query, typeIndex, entry -> true);
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

      for (final Set<Declaration> byOwner :
          directSubtypeDeclarations.getOrDefault(name, Map.of()).values()) {
        for (final Declaration subtype : byOwner) {
          pending.add(subtype.entry().binaryName());
        }
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

  private static int declarationCount(final Map<ModuleSourceConfig, Set<Declaration>> byOwner) {
    return byOwner.size() == 1 ? byOwner.values().iterator().next().size() : byOwner.size();
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

  // Done once the last rebuild, and a refresh in flight, are applied (or superseded).
  CompletableFuture<Void> rebuilt() {
    eventLoop.checkOnLoop();
    return refreshing == null
        ? reactorIndexRebuilt
        : CompletableFuture.allOf(refreshing, reactorIndexRebuilt);
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
      final Optional<ClassFileTypeScanner.ReactorScan> scan = scanReactorDir(config);
      base =
          scan.map(ClassFileTypeScanner.ReactorScan::entries)
              .orElseGet(() -> reactorBaseShards.getOrDefault(config, List.of()));
      scan.ifPresent(found -> reactorLambdaShards.put(config, found.lambdaImplementors()));
    }

    reactorBaseShards.put(config, base);
    replaceShard(config, withSourceDelta(config, base, openSourcePaths.get()));
    reactorIndexStale = true;
    // An external-output tree's write reuses the cached base: the scan in flight is newer.
    if (refreshing != null && !config.externalOutput()) {
      rescannedDuringRefresh.add(config);
    }
  }

  // Built off the event loop, as a rebuild takes 100-200ms on a large reactor and would hold
  // back the publishes queued behind it; applied only if the index is still the one it was built
  // from and no newer build started.
  private void rebuild() {
    reactorIndexStale = false;
    reactorLambdaImplementors = mergeLambdaShards();
    final WorkspaceTypeIndex base = typeIndex;
    final Map<Path, List<TypeIndexEntry>> shards = shardsByTree(reactorShards);
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
      final WorkspaceTypeIndex base, final Map<Path, List<TypeIndexEntry>> shards) {
    return eventLoop.rejoin(CompletableFuture.supplyAsync(() -> base.withReactorEntries(shards)));
  }

  // Index types from dirty/open sources not yet built, so they show in symbols before their class
  // exists: for external output until the next make+sync, and for the Maven mirror whenever a
  // compile has not caught up (a write still pending, or a file that does not compile). Keyed on
  // disk staleness, not open buffers (so they survive closing); the bytecode
  // entry, with its real kind, wins once the class is built.
  private static List<TypeIndexEntry> withSourceDelta(
      final ModuleSourceConfig config,
      final List<TypeIndexEntry> base,
      final Set<Path> openSources) {
    final Set<String> known =
        base.stream().map(TypeIndexEntry::binaryName).collect(Collectors.toUnmodifiableSet());
    final List<TypeIndexEntry> delta =
        deltaSourceFiles(config, openSources).stream()
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
  private static List<Path> deltaSourceFiles(
      final ModuleSourceConfig config, final Set<Path> openSources) {
    return Stream.concat(TreeState.read(config).stale().stream(), openSources.stream())
        .filter(path -> config.sourceRootOf(path).isPresent())
        .distinct()
        .toList();
  }

  // A walk that fails (a directory removed or unreadable midway: the server's own writes run beside
  // a
  // refresh's scan) leaves the tree's last scan in place.
  private static Optional<ClassFileTypeScanner.ReactorScan> scanReactorDir(
      final ModuleSourceConfig config) {
    final Path scanRoot = reactorScanRoot(config);
    try {
      return Optional.of(ClassFileTypeScanner.scanReactorDirectory(scanRoot));
    } catch (final IOException | UncheckedIOException e) {
      LOG.log(
          Level.WARNING,
          e,
          () -> "[type-index] reactor scan failed, last scan kept: %s".formatted(scanRoot));
      return Optional.empty();
    }
  }

  // External-output configs scan the build output dir directly; Maven scans the .lathe mirror.
  private static Path reactorScanRoot(final ModuleSourceConfig config) {
    return config.externalOutput() ? config.outputDir() : config.latheClassesDir();
  }
}
