package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.BatchWrite;
import io.github.aglibs.lathe.analysis.TransientSource;
import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import io.github.aglibs.lathe.core.schema.SourceStamp;
import io.github.aglibs.lathe.core.typeindex.ClassSourceFile;
import io.github.aglibs.lathe.core.typeindex.SourceTypeScanner;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.lathe.server.module.WorkspaceModuleGraph;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.DiagnosticSeverity;

// Keeps .lathe/ current with the sources on disk: each pass scans the source trees for files whose
// content differs from what was last compiled into the mirror, compiles them there tree by tree in
// dependency order, records their stamps, and copies changed resources. One pass at a time, under
// .lathe/'s write lock (shared with any other Lathe server on the workspace). Event-loop state; the
// compiles run on the module workers.
final class WritePass {

  private static final Logger LOG = Logger.getLogger(WritePass.class.getName());

  private static final long DEFAULT_SCAN_INTERVAL_MS = 2_000L;
  private static final String WRITE_LOCK_KEY = "write-lock";

  // What a pass needs from the session that owns the open documents.
  // heldByEditor: an open document whose buffer, not the disk, is the truth (an editor's; the
  // engine's documents follow the disk). reread: files changed on disk that the session's
  // documents follow. refreshOpenFiles: re-analyze the open files of these module dirs.
  // keepDiagnostics: what a write reported that analysis cannot (processor, plugin findings).
  // renameMembers: files a rename is applying now, written together when it commits.
  record Hooks(
      Predicate<String> heldByEditor,
      Consumer<List<Path>> reread,
      Consumer<Set<Path>> refreshOpenFiles,
      BiConsumer<List<TransientSource>, BatchWrite> keepDiagnostics,
      Supplier<Set<Path>> renameMembers) {

    Hooks {
      ValidCheck.check()
          .notNull(heldByEditor, "heldByEditor")
          .notNull(reread, "reread")
          .notNull(refreshOpenFiles, "refreshOpenFiles")
          .notNull(keepDiagnostics, "keepDiagnostics")
          .notNull(renameMembers, "renameMembers")
          .validate();
    }
  }

  private final ServerEventLoop eventLoop;
  private final WorkspaceState state;
  private final ReactorTypeIndex reactorIndex;
  private final ProgressReporter progressReporter;
  private final Hooks hooks;
  // Stale sources and their mtimes seen on the previous tick. An outside change is written only
  // once its mtime has held across two ticks, so a file mid-write is left to settle first.
  private Map<Path, Long> pendingStale = Map.of();
  // Sources whose last compile emitted no class (a compile error), with the mtime compiled and the
  // first error. Not retried alone until edited; they join their tree's next write, and a write
  // upstream lets them try again.
  private final Map<Path, FailedWrite> failedCompiles = new HashMap<>();
  // The write pass: one at a time. A request while one runs is answered by the next pass, started
  // when this one ends; eager if any request for it was. A reload bumps the generation, so a pass
  // started before it stops writing.
  private CompletableFuture<List<Path>> runningPass;
  private CompletableFuture<List<Path>> nextPass;
  private boolean nextPassEager;
  // The running pass's hold on .lathe/'s write lock, which another Lathe server on this workspace
  // (an editor's and an agent's) takes for its passes too.
  private Closeable writeLock;
  // The pass is waiting for another server to release the write lock.
  private boolean waitingForWriteLock;
  // Each source tree's stamps as this server last knew them, its own writes included: stamps that
  // differ at the next pass were written by another server on the workspace (or a build).
  private final Map<ModuleSourceConfig, Map<Path, SourceStamp>> stampsSeen = new HashMap<>();
  private long passGeneration;
  // What the passes owe once none is left to run: the module dirs whose open files to re-analyze,
  // and a reactor type index rebuild. Deferred while another pass is requested, so a `:wa` burst
  // analyzes each open file once and rebuilds the index (~200ms on a large reactor) once.
  private final Set<Path> refreshScope = new HashSet<>();
  // The resource files the last pass found, so the next one can drop the copies of deleted ones.
  private Set<Path> scannedResources = Set.of();
  // Files the editor saved or the MCP engine wrote, each with the number of its latest report: the
  // pass writes them without waiting for them to settle, while a build holds the reactor lock, and
  // even when their mtime equals their stamp -- the writer said they changed. A file leaves once
  // written, unless reported again after its content was read.
  private final Map<Path, Long> reported = new HashMap<>();
  // Files the pass wrote since a verify_change last took them, reported or caught up from disk: the
  // write stamps them, so verify_change's own stale scan would not list them. At most every source.
  private final Set<Path> writtenSinceVerify = new HashSet<>();
  // The lombok.config files over the source roots (in a directory from one up to the workspace
  // root) when the workspace loaded, with their mtimes as the last pass saw them, 0 once deleted;
  // null until the first pass after a load finds them.
  private Map<Path, Long> lombokConfigMtimes;
  // Sources below a lombok.config edited since the build: their classes change though their text
  // is what the build compiled, so verify_change counts them as changed.
  private final Set<Path> lombokChangedSources = new HashSet<>();
  private long reportCount;

  WritePass(
      final ServerEventLoop eventLoop,
      final WorkspaceState state,
      final ReactorTypeIndex reactorIndex,
      final ProgressReporter progressReporter,
      final Hooks hooks) {
    this.eventLoop = eventLoop;
    this.state = state;
    this.reactorIndex = reactorIndex;
    this.progressReporter = progressReporter;
    this.hooks = hooks;
  }

  // A reload replaces the configs and workers a running pass writes through: the pass started
  // before it stops writing, and the lombok.config state is read afresh.
  void onReload() {
    eventLoop.checkOnLoop();
    lombokChangedSources.clear();
    lombokConfigMtimes = null;
    passGeneration++;
  }

  // The workspace or the reactor mirror was just re-read: the stale sources seen before no longer
  // count as settled.
  void forgetPendingStale() {
    eventLoop.checkOnLoop();
    pendingStale = Map.of();
  }

  boolean waitingForWriteLock() {
    eventLoop.checkOnLoop();
    return waitingForWriteLock;
  }

  // The sources whose last write failed, with the mtime compiled and the first error.
  Map<Path, FailedWrite> failedCompiles() {
    eventLoop.checkOnLoop();
    return Collections.unmodifiableMap(failedCompiles);
  }

  // Whether a lombok.config over the source changed since the build.
  boolean lombokChanged(final Path source) {
    eventLoop.checkOnLoop();
    return lombokChangedSources.contains(source);
  }

  List<Path> takeWrittenSinceVerify() {
    eventLoop.checkOnLoop();
    final List<Path> taken = List.copyOf(writtenSinceVerify);
    writtenSinceVerify.clear();
    return taken;
  }

  static long scanIntervalMs() {
    final long configured = Long.getLong(LatheFlags.SCAN_INTERVAL_MS, DEFAULT_SCAN_INTERVAL_MS);
    return configured > 0 ? configured : DEFAULT_SCAN_INTERVAL_MS;
  }

  // Copies a changed resource into .lathe/ so a resource-only edit is picked up without a rebuild.
  // Returns the .lathe/ destination written, or empty if the file maps to no resource root or the
  // copy failed.
  Optional<Path> refreshResource(final String uri) {
    final var file = LatheUri.toPath(uri);
    final Optional<Path> dest = state.manifest().resourceDestination(file);
    if (dest.isEmpty()) {
      LOG.fine(() -> "[resource] %s not a tracked resource → ignored".formatted(uri));
      return Optional.empty();
    }

    return copyResource(file, dest.get());
  }

  private Optional<Path> copyResource(final Path file, final Path dest) {
    try {
      FileUtil.copyFileAtomically(file, dest);
      LOG.info(() -> "[resource] %s → %s".formatted(file, dest));
      return Optional.of(dest);
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[resource] copy failed for %s".formatted(file));
      return Optional.empty();
    }
  }

  // Deletes the source's class outputs except those the latest compile wrote (keptBinaryNames):
  // empty for a deleted source, the compile's output set to prune what a save removed.
  static int deleteClassOutputs(
      final ModuleSourceConfig config, final Path source, final Set<String> keptBinaryNames) {
    if (!FileUtil.isJavaFile(source)) {
      return 0;
    }

    final var classDir = classDirOf(config, source);
    if (classDir == null || !Files.isDirectory(classDir)) {
      return 0;
    }

    try (final var stream = Files.list(classDir)) {
      // A source at the tree's root (module-info.java) shares its directory with the packages.
      final var deletedClassFiles =
          stream
              .filter(path -> path.getFileName().toString().endsWith(".class"))
              .filter(
                  path ->
                      !keptBinaryNames.contains(
                          FileUtil.classBinaryName(config.latheClassesDir(), path)))
              .filter(path -> classOutputOf(source, path))
              .toList();
      deletedClassFiles.forEach(path -> IOUtil.unchecked(() -> Files.deleteIfExists(path)));
      if (!deletedClassFiles.isEmpty()) {
        LOG.fine(
            () ->
                "[delete] %s classes=%d".formatted(source.getFileName(), deletedClassFiles.size()));
      }

      return deletedClassFiles.size();
    } catch (final IOException | UncheckedIOException e) {
      LOG.log(Level.WARNING, e, () -> "[delete] class cleanup failed for %s".formatted(source));
      return 0;
    }
  }

  // A deleted source leaves its classes in the mirror, still resolvable by every sibling. A stamp
  // key with no surviving source is that orphan: drop its classes and prune the stamp. Returns the
  // class files removed (0 when a mismatched class cannot be located — a full mvn clean repairs
  // it).
  static int deleteOrphanedClassOutputs(final TreeState tree) {
    final ModuleSourceConfig config = tree.config();
    final List<Path> roots = config.sourceRoots();
    if (roots.isEmpty()) {
      return 0;
    }

    final Set<Path> orphans = tree.orphans();
    if (orphans.isEmpty()) {
      return 0;
    }

    LOG.fine(() -> "[delete] %s orphans=%s".formatted(config.moduleDir().getFileName(), orphans));
    final Path root = roots.get(0);
    int removed = 0;
    for (final var rel : orphans) {
      removed += deleteClassOutputs(config, root.resolve(rel), Set.of());
    }

    pruneOrphanStamps(config, orphans);
    return removed;
  }

  private static void pruneOrphanStamps(final ModuleSourceConfig config, final Set<Path> orphans) {
    try {
      CompiledStamps.prune(config.moduleDir(), config.sourceTree(), orphans);
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING,
          e,
          () -> "[delete] stamp prune failed for %s".formatted(config.moduleDir()));
    }
  }

  // The last scan found stale sources: the next tick writes those still settling.
  boolean settling() {
    return !pendingStale.isEmpty();
  }

  // Test/tool seam: request a pass and complete once its writes finish. Must be invoked on the
  // event loop. eager mirrors the startup pass (no two-tick wait).
  CompletableFuture<Void> reconcileNow(final boolean eager) {
    eventLoop.checkOnLoop();
    return requestPass(eager).thenAccept(stale -> {});
  }

  // Asks for a write pass; completes with the stale sources it considered once its writes finish.
  // eager treats every stale source as ready (startup, an MCP query) instead of waiting a tick for
  // a file that may still be being written. Runs on the event loop, which owns all pass state; the
  // pass is registered as running before any of its work, so one that finishes at once still ends
  // cleanly.
  CompletableFuture<List<Path>> requestPass(final boolean eager) {
    eventLoop.checkOnLoop();
    if (runningPass != null) {
      if (nextPass == null) {
        nextPass = new CompletableFuture<>();
      }

      nextPassEager |= eager;
      return nextPass;
    }

    final var pass = new CompletableFuture<List<Path>>();
    runningPass = pass;
    pass.whenComplete((stale, error) -> endPass(stale == null ? List.of() : stale));
    runLocked(eager, pass);
    return pass;
  }

  // Two servers writing one module at once can read each other's half-written classes and record a
  // sound file as failed. So a pass runs under .lathe/'s write lock, taken before it scans: after
  // the other server's pass, its stamps show what it wrote as current, and this one skips it.
  private void runLocked(final boolean eager, final CompletableFuture<List<Path>> pass) {
    try {
      final Optional<Closeable> lock =
          LatheLock.tryExclusive(
              LatheLayout.writeLockFile(state.root().resolve(LatheLayout.LATHE_DIR)));
      // The other server's pass can run for minutes (a branch switch); trying again only as often
      // as the change scan keeps an editor's idle server from waking more while it waits.
      if (lock.isEmpty()) {
        waitingForWriteLock = true;
        eventLoop.schedule(WRITE_LOCK_KEY, scanIntervalMs(), () -> runLocked(eager, pass));
        return;
      }

      waitingForWriteLock = false;
      writeLock = lock.get();
      runPass(eager, pass);
    } catch (final IOException | RuntimeException e) {
      waitingForWriteLock = false;
      LOG.log(Level.SEVERE, e, () -> "[reconcile] pass failed");
      pass.completeExceptionally(e); // never leave a pass running, or every later request waits
    }
  }

  // One pass: drop the classes of deleted sources, claim the stale sources that are ready, copy
  // changed resources, then write the claimed sources level by level, upstream first. While a
  // build holds the reactor lock only reported files are written; external catch-up waits for it.
  // The open files in and downstream of what it wrote are re-analyzed once it ends -- or after the
  // next pass, if one was requested meanwhile.
  private void runPass(final boolean eager, final CompletableFuture<List<Path>> done) {
    final boolean building = reactorBuildInProgress();
    final var scanned = Stopwatch.start();
    final List<TreeState> trees =
        state.registry().allConfigs().stream().map(TreeState::read).toList();
    final Map<Path, Long> sourceMtimes =
        trees.stream()
            .flatMap(tree -> tree.sourcesByRoot().values().stream())
            .flatMap(sources -> sources.entrySet().stream())
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, Map.Entry::getValue, (first, ignored) -> first));
    trees.forEach(this::refreshIfWrittenElsewhere);
    trees.forEach(this::reconcileDeletedSourcesIn);
    // Under a build the configs it compiles with are its own; the first pass after it compares.
    if (!building) {
      eventLoop.checkOnLoop();
      reportLombokConfigChanges(trees);
    }

    // Not under a build: it rewrites the stamps itself, and a touched source is current meanwhile.
    if (!building) {
      trees.forEach(tree -> recordStamps(tree.config(), tree.touched()));
    }

    final StaleScan scan = withReported(staleModules(trees));
    final long sourcesMs = scanned.elapsedMs();
    // By mtime, not stamp: the MCP engine stamps the files it writes, so an editor's server on the
    // same workspace never finds them stale.
    final List<Path> reread =
        state.candidateIndex().syncFromDisk(sourceMtimes, hooks.heldByEditor());
    hooks.reread().accept(reread);
    final Map<ModuleSourceConfig, List<Path>> batches = claimReady(scan, eager, building);
    final var resourcesScanned = Stopwatch.start();
    final int resources = building ? 0 : reconcileResources();
    // Per pass, so a slow file system (macOS) shows what a scan costs there, and per file.
    LOG.fine(
        () ->
            "[stale] scanned %d source trees: %d sources %dms, %d resources %dms, stale=%d"
                .formatted(
                    trees.size(),
                    sourceMtimes.size(),
                    sourcesMs,
                    resources,
                    resourcesScanned.elapsedMs(),
                    scan.modules().size()));

    reportRecompile(
        batches.values().stream()
            .flatMap(List::stream)
            .filter(source -> !reported.containsKey(source))
            .count(),
        done);
    final List<List<ModuleSourceConfig>> levels =
        WorkspaceModuleGraph.levels(List.copyOf(batches.keySet()));
    writeLevels(new ArrayDeque<>(levels), batches, passGeneration)
        .whenComplete(
            (written, error) -> {
              if (error != null) {
                LOG.log(Level.SEVERE, error, () -> "[reconcile] write failed");
              }

              done.complete(scan.staleFiles());
            });
  }

  // A reported file is written even when its mtime equals its stamp, so the scan may not list it.
  private StaleScan withReported(final StaleScan scan) {
    reported.keySet().removeIf(source -> !Files.exists(source));
    return scan.with(
        reported.keySet().stream()
            .flatMap(
                source ->
                    state
                        .registry()
                        .moduleSourceFor(source)
                        // A generated source is the processors' to rewrite, never an input.
                        .filter(config -> !config.isGenerated(source))
                        .map(config -> Map.entry(config, source))
                        .stream())
            .collect(
                Collectors.groupingBy(
                    Map.Entry::getKey,
                    Collectors.mapping(Map.Entry::getValue, Collectors.toList()))));
  }

  // Starts the pass requested meanwhile, handing it this pass's refresh, so each open file is
  // analyzed once after both. A request made during this pass may concern a file this pass already
  // wrote, so its answer covers both passes' stale sources.
  private void endPass(final List<Path> finished) {
    runningPass = null;
    if (writeLock != null) {
      IOUtil.unchecked(writeLock::close);
      writeLock = null;
    }

    final CompletableFuture<List<Path>> next = nextPass;
    if (next == null) {
      refreshAfterPasses();
      return;
    }

    nextPass = null;
    final boolean eager = nextPassEager;
    nextPassEager = false;
    requestPass(eager)
        .whenComplete(
            (stale, error) -> {
              if (error != null) {
                next.completeExceptionally(error);
              } else {
                next.complete(Stream.concat(finished.stream(), stale.stream()).distinct().toList());
              }
            });
  }

  private void refreshAfterPasses() {
    if (!refreshScope.isEmpty()) {
      final Set<Path> scope = Set.copyOf(refreshScope);
      refreshScope.clear();
      hooks.refreshOpenFiles().accept(scope);
    }

    reactorIndex.rebuildIfStale();
  }

  // The stale sources ready to write, by source tree in write order. Ready: reported, or -- unless
  // a build is running -- settled across one tick (or eager) and not failed with no change since;
  // never held by an open rename. A tree that writes anything takes its failed sources along: they
  // may have failed only for want of what this write brings.
  private Map<ModuleSourceConfig, List<Path>> claimReady(
      final StaleScan scan, final boolean eager, final boolean building) {
    final Map<Path, Long> stale = staleMtimes(scan);
    final Map<Path, Long> current = withoutFailedCompiles(stale, failedCompiles);
    final Set<Path> settled = eager ? current.keySet() : stableSources(current, pendingStale);
    pendingStale = current;
    final Set<Path> ready =
        building
            ? Set.copyOf(reported.keySet())
            : Stream.concat(settled.stream(), reported.keySet().stream())
                .collect(Collectors.toUnmodifiableSet());
    final Set<Path> held = hooks.renameMembers().get();
    final Set<Path> failedUnchanged =
        stale.keySet().stream()
            .filter(source -> !current.containsKey(source))
            .collect(Collectors.toUnmodifiableSet());
    final var batches = new LinkedHashMap<ModuleSourceConfig, List<Path>>();
    compileOrder(scan.staleByModule().keySet())
        .forEach(
            config -> {
              final List<Path> batch =
                  treeBatch(scan.staleByModule().get(config), ready, failedUnchanged, held);
              if (!batch.isEmpty()) {
                batches.put(config, batch);
              }
            });
    return Collections.unmodifiableMap(batches);
  }

  // A tree's batch: its ready sources an open rename does not hold, with its failed-unchanged ones
  // riding along -- they may have failed only for want of what this write brings. Nothing when no
  // source of the tree is ready.
  static List<Path> treeBatch(
      final List<Path> treeStale,
      final Set<Path> ready,
      final Set<Path> failedUnchanged,
      final Set<Path> held) {
    if (treeStale.stream().noneMatch(source -> ready.contains(source) && !held.contains(source))) {
      return List.of();
    }

    return treeStale.stream()
        .filter(source -> ready.contains(source) || failedUnchanged.contains(source))
        .filter(source -> !held.contains(source))
        .toList();
  }

  // Files the editor saved or the MCP engine wrote: written by a pass, the next one if a pass is
  // running. The engine calls this holding the reactor lock, which defers everything but these.
  CompletableFuture<Void> writeReported(final Set<Path> changed) {
    eventLoop.checkOnLoop();
    report(changed);
    return requestPass(false).thenAccept(stale -> {});
  }

  private void report(final Collection<Path> changed) {
    changed.forEach(source -> reported.put(source, ++reportCount));
    writtenSinceVerify.addAll(changed);
  }

  // Lombok reads its config when it compiles, so an edited one rewrites every source below it. Only
  // the configs there were at load are watched, each pass a stat apiece (most projects have none);
  // one added later counts from the next reload. A config inside a source root's packages is not
  // watched: the usual one sits above the roots.
  private void reportLombokConfigChanges(final List<TreeState> trees) {
    final Map<Path, Long> previous = lombokConfigMtimes;
    final Set<Path> watched =
        previous != null
            ? previous.keySet()
            : trees.stream()
                .flatMap(tree -> tree.sourcesByRoot().keySet().stream())
                .flatMap(this::lombokConfigCandidates)
                .filter(Files::isRegularFile)
                .collect(Collectors.toUnmodifiableSet());
    final Map<Path, Long> current =
        watched.stream()
            .collect(Collectors.toUnmodifiableMap(Function.identity(), WritePass::mtimeMillis));
    lombokConfigMtimes = current;
    if (previous == null) {
      return;
    }

    final List<Path> changedDirs =
        current.entrySet().stream()
            .filter(config -> !config.getValue().equals(previous.get(config.getKey())))
            .map(config -> config.getKey().getParent())
            .toList();
    final List<Path> below =
        trees.stream()
            .flatMap(tree -> tree.sourcesByRoot().values().stream())
            .flatMap(sources -> sources.keySet().stream())
            .filter(source -> changedDirs.stream().anyMatch(source::startsWith))
            .toList();
    lombokChangedSources.addAll(below);
    report(below);
  }

  private Stream<Path> lombokConfigCandidates(final Path sourceRoot) {
    return Stream.iterate(
            sourceRoot, dir -> dir != null && dir.startsWith(state.root()), Path::getParent)
        .map(dir -> dir.resolve(LatheLayout.LOMBOK_CONFIG_FILE));
  }

  private void reconcileDeletedSourcesIn(final TreeState tree) {
    final ModuleSourceConfig config = tree.config();
    final int removed = deleteOrphanedClassOutputs(tree);
    if (removed == 0) {
      return;
    }

    // So verify_change checks what used the deleted types.
    final Path root = config.sourceRoots().get(0);
    tree.orphans().forEach(rel -> writtenSinceVerify.add(root.resolve(rel)));
    reactorIndex.refreshShard(config);
    refreshScope.addAll(state.moduleGraph().downstreamModuleDirs(config.moduleDir()));
    LOG.fine(() -> "[delete] %s removed=%d".formatted(config.moduleDir().getFileName(), removed));
  }

  // A self-clearing $/progress task spanning the in-process recompile, so the editor shows the
  // catch-up after an external change (a branch switch, a rename) and clears it once written.
  private void reportRecompile(final long count, final CompletableFuture<?> written) {
    if (count == 0) {
      return;
    }

    LOG.fine(() -> "[react] recompiling %d changed file(s)".formatted(count));
    final var progress = progressReporter.open(null, new CompletableFuture<>());
    progress.begin("Lathe: recompiling %d file(s)".formatted(count), 1);
    written.whenComplete((ignored, error) -> progress.finish(error));
  }

  static Map<Path, Long> staleMtimes(final StaleScan scan) {
    // A file can be stale under more than one config (overlapping build-tool source roots); its
    // mtime is the same either way, so dedupe by path rather than letting toMap throw on the
    // duplicate.
    return scan.staleFiles().stream()
        .collect(
            Collectors.toUnmodifiableMap(
                source -> source, WritePass::mtimeMillis, (first, ignored) -> first));
  }

  static boolean failedUnchanged(final FailedWrite failed, final long mtime) {
    return failed != null && failed.mtime() == mtime;
  }

  // "line: message" of the source's first error in the write, for the MCP "Not compiled" note.
  private static String firstError(final BatchWrite written, final Path source) {
    return written.diagnosticsByUri().getOrDefault(LatheUri.fromPath(source), List.of()).stream()
        .filter(diagnostic -> diagnostic.getSeverity() == DiagnosticSeverity.Error)
        .findFirst()
        .map(
            diagnostic ->
                "%d: %s"
                    .formatted(
                        diagnostic.getRange().getStart().getLine() + 1,
                        diagnostic.getMessage().getLeft().lines().findFirst().orElse("")))
        .orElse("no class was written");
  }

  record FailedWrite(long mtime, String error) {
    FailedWrite {
      ValidCheck.check().notNull(error, "error").validate();
    }
  }

  static Map<Path, Long> withoutFailedCompiles(
      final Map<Path, Long> stale, final Map<Path, FailedWrite> failedCompiles) {
    return stale.entrySet().stream()
        .filter(entry -> !failedUnchanged(failedCompiles.get(entry.getKey()), entry.getValue()))
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  // A source is safe to recompile once its mtime has held across two ticks, so a file still being
  // written (an agent or editor mid-flush) is skipped until it settles.
  static Set<Path> stableSources(final Map<Path, Long> current, final Map<Path, Long> previous) {
    return current.entrySet().stream()
        .filter(entry -> entry.getValue().equals(previous.get(entry.getKey())))
        .map(Map.Entry::getKey)
        .collect(Collectors.toUnmodifiableSet());
  }

  // Writes one level's trees together and starts the next level once all of them finish, so every
  // write resolves against the freshly written upstream classes. Stops at a reload (another
  // generation), and while a build holds the reactor lock writes only reported files; what is left
  // stays stale for the next pass. Completes on the event loop.
  private CompletableFuture<Void> writeLevels(
      final Deque<List<ModuleSourceConfig>> levels,
      final Map<ModuleSourceConfig, List<Path>> batches,
      final long generation) {
    if (levels.isEmpty() || generation != passGeneration) {
      return CompletableFuture.completedFuture(null);
    }

    final boolean building = reactorBuildInProgress();
    final CompletableFuture<?>[] writes =
        levels.removeFirst().stream()
            .map(
                tree ->
                    compileChangedBatch(
                        tree,
                        building ? onlyReported(batches.get(tree)) : batches.get(tree),
                        Set.of()))
            .toArray(CompletableFuture[]::new);
    return CompletableFuture.allOf(writes)
        .thenCompose(written -> writeLevels(levels, batches, generation));
  }

  // External changes wait for a pass after the build.
  private List<Path> onlyReported(final List<Path> batch) {
    return batch.stream().filter(reported::containsKey).toList();
  }

  // Modules upstream-first, and within a module the main tree before the test tree, the order
  // WorkspaceModuleGraph.levels groups into write levels.
  private List<ModuleSourceConfig> compileOrder(final Set<ModuleSourceConfig> configs) {
    final Map<Path, List<ModuleSourceConfig>> byModule =
        configs.stream().collect(Collectors.groupingBy(ModuleSourceConfig::moduleDir));
    return state.moduleGraph().upstreamFirst(byModule.keySet()).stream()
        .flatMap(dir -> byModule.get(dir).stream().sorted(mainTreeFirst()))
        .toList();
  }

  private static Comparator<ModuleSourceConfig> mainTreeFirst() {
    return Comparator.comparing(ModuleSourceConfig::isTestTree);
  }

  // One FULL compile over the tree's whole changed set, so mutually referencing files (a sealed
  // root and a new permitted subtype) resolve inside the batch. Each source's mtime is read with
  // its content, so an edit landing during the compile is not stamped as written. javac writes
  // nothing when any source fails, so a failing batch is written once more without the sources that
  // failed (failedFirst, empty on the first attempt).
  private CompletableFuture<Void> compileChangedBatch(
      final ModuleSourceConfig config, final List<Path> sources, final Set<Path> failedFirst) {
    final var transientSources = new ArrayList<TransientSource>(sources.size());
    final var stamps = new LinkedHashMap<Path, SourceStamp>();
    for (final var source : sources) {
      final long mtime = mtimeMillis(source);
      final Optional<String> read = FileUtil.readIfPresent(source);
      if (read.isEmpty()) {
        reported.remove(source);
        continue;
      }

      final String content = read.get();

      transientSources.add(new TransientSource(LatheUri.fromPath(source), content));
      // The content compiled, not a later read: a save during a long batch must not stamp it.
      stamps.put(
          source,
          new SourceStamp(mtime, ContentFingerprint.of(content.getBytes(StandardCharsets.UTF_8))));
    }

    if (transientSources.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }

    LOG.fine(
        () -> {
          final String names =
              stamps.keySet().stream()
                  .limit(20)
                  .map(source -> source.getFileName().toString())
                  .collect(Collectors.joining(", "));
          return "[react] writing %d file(s) in %s: %s%s"
              .formatted(
                  stamps.size(),
                  state.layout().moduleRel(config),
                  names,
                  stamps.size() > 20 ? " +%d more".formatted(stamps.size() - 20) : "");
        });

    // Reported again after this read, a file stays reported for the next pass.
    final Map<Path, Long> reports =
        stamps.keySet().stream()
            .filter(reported::containsKey)
            .collect(Collectors.toUnmodifiableMap(source -> source, reported::get));
    return state
        .registry()
        .workerFor(config)
        .compileBatch(List.copyOf(transientSources))
        .handle(
            (written, error) -> {
              reports.forEach(reported::remove);
              if (written != null) {
                hooks.keepDiagnostics().accept(transientSources, written);
              }

              return afterChangedBatch(config, Map.copyOf(stamps), written, error, failedFirst);
            })
        .thenCompose(next -> next);
  }

  private CompletableFuture<Void> afterChangedBatch(
      final ModuleSourceConfig config,
      final Map<Path, SourceStamp> stamps,
      final BatchWrite written,
      final Throwable error,
      final Set<Path> failedFirst) {
    if (error != null || written == null) {
      LOG.log(
          Level.FINE,
          error,
          () -> "[react] batch compile failed in %s".formatted(state.layout().moduleRel(config)));
      stamps.forEach(
          (source, stamp) ->
              failedCompiles.put(source, new FailedWrite(stamp.mtime(), "the compiler failed")));
      return CompletableFuture.completedFuture(null);
    }

    if (!written.failedUris().isEmpty() && failedFirst.isEmpty()) {
      final Map<Boolean, List<Path>> byFailure =
          stamps.keySet().stream()
              .collect(
                  Collectors.partitioningBy(
                      source -> written.failedUris().contains(LatheUri.fromPath(source))));
      byFailure
          .get(true)
          .forEach(
              source ->
                  failedCompiles.put(
                      source,
                      new FailedWrite(stamps.get(source).mtime(), firstError(written, source))));
      LOG.fine(
          () ->
              "[react] %d of %d file(s) failed in %s, writing the rest"
                  .formatted(
                      byFailure.get(true).size(), stamps.size(), state.layout().moduleRel(config)));
      // The failed files' open buffers get their write diagnostics.
      refreshScope.add(config.moduleDir());
      final List<Path> rest = byFailure.get(false);
      return rest.isEmpty()
          ? CompletableFuture.completedFuture(null)
          : compileChangedBatch(config, rest, Set.copyOf(byFailure.get(true)));
    }

    if (!written.writtenBinaryNames().isEmpty()) {
      retryFailuresDownstream(config, failedFirst);
    }

    stamps.forEach(
        (source, stamp) -> {
          try {
            freshenClassOutputs(config, source, written, stamp);
          } catch (final RuntimeException e) {
            // Left unstamped, the file would be rewritten by every next pass.
            LOG.log(Level.SEVERE, e, () -> "[react] freshening %s failed".formatted(source));
            failedCompiles.put(
                source, new FailedWrite(stamp.mtime(), "internal error, see the server log"));
          }
        });
    writtenSinceVerify.addAll(stamps.keySet());
    refreshScope.addAll(state.moduleGraph().downstreamModuleDirs(config.moduleDir()));
    reactorIndex.refreshShard(config);
    LOG.fine(
        () ->
            "[react] recompiled %d file(s) in %s"
                .formatted(stamps.size(), state.layout().moduleRel(config)));
    return CompletableFuture.completedFuture(null);
  }

  // A write that produced classes may supply what earlier failures here or downstream were missing,
  // so they get another try; the files that failed in this very write (failedFirst) keep their
  // record. A retry that failed too wrote nothing, so it must not reopen them -- or every pass
  // would retry them and fail again. Unchanged since they failed, they count as settled and the
  // next pass, run right after this one, writes them: waiting for them to settle again kept an
  // open file's stale error for two more scans.
  private void retryFailuresDownstream(
      final ModuleSourceConfig config, final Set<Path> failedFirst) {
    final Set<Path> downstream = state.moduleGraph().downstreamModuleDirs(config.moduleDir());
    final Map<Path, Long> retried =
        failedCompiles.entrySet().stream()
            .filter(
                failed ->
                    !failedFirst.contains(failed.getKey())
                        && state
                            .registry()
                            .moduleSourceFor(failed.getKey())
                            .map(failedConfig -> downstream.contains(failedConfig.moduleDir()))
                            .orElse(true))
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, failed -> failed.getValue().mtime()));
    if (retried.isEmpty()) {
      return;
    }

    failedCompiles.keySet().removeAll(retried.keySet());
    final var settled = new HashMap<>(pendingStale);
    settled.putAll(retried);
    pendingStale = Map.copyOf(settled);
    requestPass(false);
  }

  // Only prune + stamp a source the compile actually emitted a class for. A source that failed to
  // compile keeps its old mirror class and stays unstamped, so a later batch retries it once its
  // dependencies land -- otherwise the stamp would mark it fresh while its mirror class is stale.
  private void freshenClassOutputs(
      final ModuleSourceConfig config,
      final Path source,
      final BatchWrite written,
      final SourceStamp stamp) {
    final Set<String> writtenBinaryNames = written.writtenBinaryNames();
    if (!wroteClassOf(config, source, writtenBinaryNames)) {
      failedCompiles.put(source, new FailedWrite(stamp.mtime(), firstError(written, source)));
      return;
    }

    deleteClassOutputs(config, source, writtenBinaryNames);
    recordCompileStamp(config, source, stamp);
  }

  // Any written class of the source counts (Foo.class, Foo$*.class, or a sibling type), so a file
  // declaring no type named after itself still freshens.
  static boolean wroteClassOf(
      final ModuleSourceConfig config, final Path source, final Set<String> writtenBinaryNames) {
    // No type to key on (package-info/module-info or no source root) -- do not block.
    if (config
        .sourceRootOf(source)
        .flatMap(root -> SourceTypeScanner.deriveEntry(root, source))
        .isEmpty()) {
      return true;
    }

    final var classDir = classDirOf(config, source);
    return writtenBinaryNames.stream()
        .map(name -> config.latheClassesDir().resolve("%s.class".formatted(name.replace('.', '/'))))
        .filter(classFile -> classFile.getParent().equals(classDir))
        .anyMatch(classFile -> classOutputOf(source, classFile));
  }

  // The mirror directory holding the source's package classes; null outside the config's roots.
  private static Path classDirOf(final ModuleSourceConfig config, final Path source) {
    final Optional<Path> sourceRoot = config.sourceRootOf(source);
    if (sourceRoot.isEmpty()) {
      return null;
    }

    final var packageRel = sourceRoot.get().relativize(source).getParent();
    return packageRel != null
        ? config.latheClassesDir().resolve(packageRel)
        : config.latheClassesDir();
  }

  // Copy any resource whose .lathe/ destination is missing or older than the source, so an external
  // resource edit is picked up without Maven; the copy bumps the destination mtime, so the next
  // pass sees it as fresh. Remove the copy of a resource deleted since the last pass. Returns how
  // many resources were checked.
  private int reconcileResources() {
    final Map<Path, Long> resources =
        state.manifest().resourceSourceDirs().stream()
            .flatMap(sourceDir -> resourceMtimesUnder(sourceDir).entrySet().stream())
            .collect(
                Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    resources.forEach(this::copyIfStale);
    // Only files the last scan saw: a copy with no source may be the build's own output.
    final Set<Path> deleted =
        scannedResources.stream()
            .filter(file -> !resources.containsKey(file) && Files.notExists(file))
            .collect(Collectors.toUnmodifiableSet());
    deleted.forEach(
        file ->
            state
                .manifest()
                .resourceDestination(file)
                .ifPresent(dest -> deleteResource(file, dest)));
    scannedResources = resources.keySet();
    return resources.size();
  }

  private Map<Path, Long> resourceMtimesUnder(final Path sourceDir) {
    try {
      return FileUtil.fileMtimes(sourceDir, file -> true);
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[resource] scan failed under %s".formatted(sourceDir));
      return Map.of();
    }
  }

  private void deleteResource(final Path file, final Path dest) {
    try {
      Files.deleteIfExists(dest);
      LOG.info(() -> "[resource] %s deleted → removed %s".formatted(file, dest));
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[resource] delete failed for %s".formatted(dest));
    }
  }

  // A missing copy reads as mtime 0, so it is copied too.
  private void copyIfStale(final Path file, final long mtime) {
    final Optional<Path> dest = state.manifest().resourceDestination(file);
    if (dest.isEmpty()) {
      return;
    }

    if (mtime > mtimeMillis(dest.get())) {
      copyResource(file, dest.get());
    }
  }

  // The Maven extension holds a heartbeated .lathe/lathe.lock for the whole reactor build; while it
  // is held, all reconciliation is suppressed, so a mid-build snapshot (some modules recompiled,
  // others not, workspace.json being rewritten) neither prompts spuriously nor copies against a
  // half-written mirror. A crashed build is reclaimed by the lock's staleness TTL.
  boolean reactorBuildInProgress() {
    return LatheLock.isBuilding(
        LatheLayout.reactorLockFile(state.root().resolve(LatheLayout.LATHE_DIR)));
  }

  // The stale sources by source tree; the annotation-processor root is excluded.
  record StaleScan(Map<ModuleSourceConfig, List<Path>> staleByModule) {
    StaleScan {
      staleByModule =
          staleByModule.entrySet().stream()
              .collect(
                  Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    Set<ModuleSourceConfig> modules() {
      return staleByModule.keySet();
    }

    List<Path> staleFiles() {
      return staleByModule.values().stream().flatMap(List::stream).toList();
    }

    StaleScan with(final Map<ModuleSourceConfig, List<Path>> more) {
      return new StaleScan(
          Stream.concat(staleByModule.entrySet().stream(), more.entrySet().stream())
              .collect(
                  Collectors.toMap(
                      Map.Entry::getKey,
                      Map.Entry::getValue,
                      (listed, added) ->
                          Stream.concat(listed.stream(), added.stream()).distinct().toList())));
    }
  }

  // Servers sharing a workspace take turns writing it, so a change another server compiled first is
  // never compiled here, and only a write here refreshes the open files that depend on it. Stamps
  // this server did not write show that write: the tree's open files and its dependents' are
  // re-analyzed as after one of its own.
  // The stamps of deleted sources are left out: this pass prunes them.
  private void refreshIfWrittenElsewhere(final TreeState tree) {
    final Set<Path> orphans = tree.orphans();
    final Map<Path, SourceStamp> current =
        tree.stamps().entrySet().stream()
            .filter(stamp -> !orphans.contains(stamp.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    final Map<Path, SourceStamp> seen = stampsSeen.put(tree.config(), current);
    if (seen != null && !seen.equals(current)) {
      refreshScope.add(tree.config().moduleDir());
      refreshScope.addAll(state.moduleGraph().downstreamModuleDirs(tree.config().moduleDir()));
    }
  }

  static StaleScan staleModules(final List<TreeState> trees) {
    return new StaleScan(
        trees.stream()
            .map(tree -> Map.entry(tree.config(), tree.stale()))
            .filter(entry -> !entry.getValue().isEmpty())
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)));
  }

  static long mtimeMillis(final Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (final IOException e) {
      return 0L;
    }
  }

  // Foo.java's outputs are Foo.class, Foo$*.class, and any package-private sibling top-level type
  // (Helper.class). Only the class file's SourceFile attribute ties a sibling back to Foo.java; a
  // class generated from another source names that source, so it is never matched.
  private static boolean classOutputOf(final Path source, final Path classFile) {
    final var name = classFile.getFileName().toString();
    if (!name.endsWith(".class")) {
      return false;
    }

    final var typeName = FileUtil.javaTypeName(source);
    if (name.equals("%s.class".formatted(typeName)) || name.startsWith("%s$".formatted(typeName))) {
      return true;
    }

    final var sourceName = source.getFileName().toString();
    return ClassSourceFile.of(classFile).filter(sourceName::equals).isPresent();
  }

  private void recordCompileStamp(
      final ModuleSourceConfig config, final Path savedSource, final SourceStamp stamp) {
    config
        .sourceRootOf(savedSource)
        .ifPresent(root -> recordStamps(config, Map.of(root.relativize(savedSource), stamp)));
  }

  private void recordStamps(final ModuleSourceConfig config, final Map<Path, SourceStamp> stamps) {
    if (stamps.isEmpty()) {
      return;
    }

    try {
      CompiledStamps.record(config.moduleDir(), config.sourceTree(), stamps);
      stampsSeen.computeIfAbsent(config, tree -> new HashMap<>()).putAll(stamps);
      LOG.fine(
          () ->
              "[stamp] %s recorded %d source(s)"
                  .formatted(config.moduleDir().getFileName(), stamps.size()));
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING,
          e,
          () ->
              "[stamp] %s record failed for %d source(s)"
                  .formatted(config.moduleDir().getFileName(), stamps.size()));
    }
  }
}
