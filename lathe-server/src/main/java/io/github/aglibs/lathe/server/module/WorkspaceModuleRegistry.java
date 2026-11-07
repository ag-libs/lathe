package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class WorkspaceModuleRegistry implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(WorkspaceModuleRegistry.class.getName());

  private final CompilationAdmission compilationAdmission;
  // Where every worker completes its results: the server's event loop.
  private final Executor delivery;
  private final List<ModuleSourceConfig> moduleSources;
  private final Map<String, CompilationWorker> workers = new HashMap<>();
  private final CompilationWorker externalWorker;

  private WorkspaceModuleRegistry(
      final List<ModuleSourceConfig> moduleSources,
      final WorkspaceManifest manifest,
      final Executor delivery) {
    this.compilationAdmission =
        new CompilationAdmission(Runtime.getRuntime().availableProcessors());
    this.delivery = delivery;
    this.moduleSources = moduleSources;
    this.externalWorker = CompilationWorker.external(manifest, compilationAdmission, delivery);
  }

  public static WorkspaceModuleRegistry empty(final Executor delivery) {
    return new WorkspaceModuleRegistry(List.of(), WorkspaceManifest.empty(), delivery);
  }

  // Module by module, main, then test, then any other tree by name: a file two trees' roots hold (a
  // Gradle test suite sharing test's folder) belongs to the first, the same on every scan.
  private static final Comparator<Path> TREE_ORDER =
      Comparator.comparing(Path::getParent)
          .thenComparingInt(WorkspaceModuleRegistry::treeRank)
          .thenComparing(Path::getFileName);

  private static int treeRank(final Path paramsFile) {
    final String tree = LatheLayout.paramsSourceTree(paramsFile);
    if (LatheLayout.CLASSES_DIR.equals(tree)) {
      return 0;
    }

    return LatheLayout.TEST_CLASSES_DIR.equals(tree) ? 1 : 2;
  }

  public static WorkspaceModuleRegistry scan(
      final Path workspaceRoot, final WorkspaceManifest manifest, final Executor delivery) {
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    if (!Files.isDirectory(latheDir)) {
      LOG.warning(() -> "[workspace] .lathe/ not found at %s".formatted(workspaceRoot));
      return new WorkspaceModuleRegistry(List.of(), manifest, delivery);
    }

    final var moduleSources = new ArrayList<ModuleSourceConfig>();
    try (final Stream<Path> stream = Files.walk(latheDir)) {
      for (final var paramsFile :
          stream.filter(LatheLayout::isParamsFile).sorted(TREE_ORDER).toList()) {
        final Path moduleRel = latheDir.relativize(paramsFile.getParent());
        if (isNestedWorkspace(workspaceRoot, moduleRel)) {
          LOG.fine(() -> "[workspace] %s ignored — nested workspace".formatted(moduleRel));
          continue;
        }

        try {
          moduleSources.add(
              ModuleSourceConfig.load(
                  paramsFile, paramsFile.getParent(), manifest.reactorRewrite()));
        } catch (final IOException e) {
          LOG.log(Level.WARNING, e, () -> "[workspace] failed to load %s".formatted(paramsFile));
        }
      }
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[workspace] scan failed");
    }

    LOG.info(
        () ->
            "[workspace] loaded %d module source config(s) from %s"
                .formatted(moduleSources.size(), workspaceRoot));
    return new WorkspaceModuleRegistry(List.copyOf(moduleSources), manifest, delivery);
  }

  // A module whose source dir sits under a directory that has its own .lathe/ belongs to a nested
  // workspace (a git worktree or checkout under the repo) — its params leak into this .lathe/ when
  // a build runs inside it. Only an ancestor .lathe/ marks a nested workspace; a .lathe/ at the
  // module's own dir is a stray and does not disqualify a real module.
  private static boolean isNestedWorkspace(final Path workspaceRoot, final Path moduleRel) {
    Path dir = workspaceRoot.resolve(moduleRel).getParent();
    while (dir != null && dir.startsWith(workspaceRoot) && !dir.equals(workspaceRoot)) {
      if (Files.isDirectory(dir.resolve(LatheLayout.LATHE_DIR))) {
        return true;
      }

      dir = dir.getParent();
    }
    return false;
  }

  public List<ModuleSourceConfig> allConfigs() {
    return moduleSources;
  }

  public Optional<ModuleSourceConfig> moduleSourceFor(final Path filePath) {
    return moduleSources.stream()
        .filter(
            m ->
                m.sourceRoots().stream().anyMatch(filePath::startsWith)
                    || filePath.startsWith(m.generatedSourcesDir()))
        .findFirst();
  }

  // The worker of the module whose sources hold the file.
  public Optional<CompilationWorker> moduleWorkerFor(final Path filePath) {
    return moduleSourceFor(filePath).map(this::workerFor);
  }

  public List<Path> allSourceRoots() {
    return moduleSources.stream().flatMap(m -> m.searchRoots().stream()).toList();
  }

  // Every tree's search roots, the file's own tree first, then the trees it compiles against: a
  // source lookup takes the first root holding a class, so a class two trees declare (two Gradle
  // source sets of one module can) resolves to the one the file's compile sees.
  public List<Path> sourceRootsFor(final Path filePath) {
    final Optional<ModuleSourceConfig> asking = moduleSourceFor(filePath);
    if (asking.isEmpty()) {
      return allSourceRoots();
    }

    // Remapped once per request, not per tree: this runs for every hover and completion.
    final Set<Path> seen = asking.get().compiledAgainst();
    final Map<Integer, List<Path>> byRank =
        moduleSources.stream()
            .collect(
                Collectors.groupingBy(
                    config -> lookupRank(asking.get(), seen, config),
                    TreeMap::new,
                    Collectors.flatMapping(
                        config -> config.searchRoots().stream(), Collectors.toList())));
    return byRank.values().stream().flatMap(List::stream).toList();
  }

  private static int lookupRank(
      final ModuleSourceConfig asking, final Set<Path> seen, final ModuleSourceConfig config) {
    if (config.equals(asking)) {
      return 0;
    }

    return seen.contains(config.latheClassesDir()) ? 1 : 2;
  }

  public CompilationWorker workerFor(final ModuleSourceConfig config) {
    return workers.computeIfAbsent(
        key(config), key -> CompilationWorker.module(config, compilationAdmission, delivery));
  }

  public CompilationWorker externalWorker() {
    return externalWorker;
  }

  // Releases every session idle for at least idleMs but those of the modules in keep; returns how
  // many it released.
  public int releaseSessions(final Set<ModuleSourceConfig> keep, final long idleMs) {
    final Set<CompilationWorker> kept =
        keep.stream()
            .map(config -> workers.get(key(config)))
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());
    final List<CompilationWorker> idle =
        allWorkers().filter(worker -> !kept.contains(worker) && worker.idleFor(idleMs)).toList();
    idle.forEach(CompilationWorker::releaseSession);
    return idle.size();
  }

  // A cheap check before working out which modules have open documents.
  public boolean hasSessionIdleFor(final long idleMs) {
    return allWorkers().anyMatch(worker -> worker.idleFor(idleMs));
  }

  // Test seam, on the event loop: completes there once no worker has a request queued or a result
  // undelivered, again until the results delivered meanwhile start no more requests.
  public CompletableFuture<Void> drained() {
    final long before = submitted();
    return CompletableFuture.allOf(
            allWorkers().map(CompilationWorker::drained).toArray(CompletableFuture[]::new))
        .thenCompose(
            done -> submitted() == before ? CompletableFuture.completedFuture(null) : drained());
  }

  private long submitted() {
    return allWorkers().mapToLong(CompilationWorker::submitted).sum();
  }

  private Stream<CompilationWorker> allWorkers() {
    return Stream.concat(workers.values().stream(), Stream.of(externalWorker));
  }

  private static String key(final ModuleSourceConfig config) {
    return config.latheClassesDir().toString();
  }

  public void dropFromAllCaches(final String uri) {
    for (final var w : workers.values()) {
      w.dropFromCache(uri);
    }
    externalWorker.dropFromCache(uri);
  }

  @Override
  public void close() {
    final var closeFutures =
        allWorkers().map(CompilationWorker::closeAsync).toArray(CompletableFuture[]::new);
    CompletableFuture.allOf(closeFutures).join();
    workers.clear();
  }
}
