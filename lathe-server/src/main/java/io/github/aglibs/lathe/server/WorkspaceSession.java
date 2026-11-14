package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.BatchWrite;
import io.github.aglibs.lathe.analysis.CompileMode;
import io.github.aglibs.lathe.analysis.Positions;
import io.github.aglibs.lathe.analysis.RenameProvider;
import io.github.aglibs.lathe.analysis.TransientSource;
import io.github.aglibs.lathe.core.ExceptionUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.engine.LatheNotes;
import io.github.aglibs.lathe.server.files.ServerFiles;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import io.github.aglibs.lathe.server.module.CompileRequest;
import io.github.aglibs.lathe.server.module.CompileResponse;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.lathe.server.module.WorkspaceModuleRegistry;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

// The workspace session: builds and wires the loop components, and keeps what ties them together:
// the open documents' lifecycle (open, change, save, close), compile scheduling and publishing, the
// rename transaction, the scan tick and a reload. A feature belongs in the component that owns it,
// not here. Event-loop state: call it on the ServerEventLoop only.
final class WorkspaceSession {

  private static final Logger LOG = Logger.getLogger(WorkspaceSession.class.getName());

  // Title of the $/progress task reported while the workspace loads/reloads. The neotest adapter
  // matches on it to gate discovery on readiness, so it is a cross-process contract: keep it in
  // sync with the mirror in lua/lathe/neotest.lua. Also user-visible via vim.lsp.status().
  static final String WORKSPACE_PROGRESS_TITLE = "Lathe: indexing workspace";

  private final LatheLanguageClient client;
  private final ProgressReporter progressReporter;
  private final ServerEventLoop eventLoop;
  private final long debounceMs;
  private final WorkspaceState state;
  private final ReactorTypeIndex reactorIndex;
  private final RunCoordinator runs;
  private final FeatureRouter router;
  private final DocumentFeatures features;
  private final FormatService formats;
  private final WorkspaceQueries queries;
  private final ReferenceSearch search;
  private final WritePass writePass;
  private final ResourceMirror resourceMirror;
  private final ChangeVerifier verifier;
  private final RenameService renames;
  private WorkspaceWatcher watcher;
  // Build files changed and the change is not dealt with: until a reload, or until the user answers
  // the sync prompt. An agent has no prompt to answer, so for it this lasts until the reload; its
  // answers say a sync is due meanwhile.
  private boolean buildFilesChanged;
  // A user answers prompts (an editor); the in-process engine's agent does not.
  private boolean interactive = true;
  private MemoryPressure memoryPressure;
  // A pressure round released the sessions of modules without open files; the next tick checks
  // whether that was enough.
  private boolean pressureFollowUp;
  private final ScanPace scanPace = new ScanPace();
  // Per open file, the diagnostics of its last write and the content written: merged into its
  // publishes while it still has that content, for what analysis cannot report (annotation
  // processor and compiler plugin findings).
  private final Map<String, WriteDiagnostics> writeDiagnostics = new HashMap<>();
  // Open files whose buffer differs from disk: analysis of another file in their tree includes
  // them, so it sees their unsaved declarations.
  private final Set<String> unsaved = new HashSet<>();
  // A rename the editor is applying and saving; its files compile together at the commit. Empty
  // when none is open.
  private RenameTransaction renameTransaction = new RenameTransaction();
  private final DocumentRegistry docs;
  // Set by the in-process engine: its documents are copies of the disk, not editor buffers, so a
  // change on disk replaces them.
  private boolean documentsFollowDisk;
  private final DiagnosticPublisher publisher;

  WorkspaceSession(
      final LatheLanguageClient client,
      final ProgressReporter progressReporter,
      final ServerEventLoop eventLoop,
      final long debounceMs) {
    this.client = client;
    this.progressReporter = progressReporter;
    this.eventLoop = eventLoop;
    this.debounceMs = debounceMs;
    this.docs = new DocumentRegistry(eventLoop);
    this.publisher = new DiagnosticPublisher(client, docs);
    this.state = new WorkspaceState(eventLoop);
    this.reactorIndex = new ReactorTypeIndex(eventLoop, state, this::openSourcePaths);
    this.runs = new RunCoordinator(client, eventLoop, state, reactorIndex);
    this.router = new FeatureRouter(eventLoop, state, docs);
    this.features = new DocumentFeatures(state, reactorIndex, router, docs, client, eventLoop);
    this.formats = new FormatService(eventLoop, client, state, docs, this::onFormatted);
    this.search = new ReferenceSearch(eventLoop, state, reactorIndex, router, docs);
    this.resourceMirror = new ResourceMirror(state);
    this.writePass =
        new WritePass(
            eventLoop,
            state,
            reactorIndex,
            progressReporter,
            new WritePass.Hooks(
                uri -> !documentsFollowDisk && docs.get(uri) != null,
                reread -> {
                  if (documentsFollowDisk) {
                    reread.forEach(this::reloadFromDisk);
                  }
                },
                this::refreshOpenFiles,
                this::keepWriteDiagnostics,
                () -> renameTransaction.memberPaths()),
            resourceMirror);
    this.queries = new WorkspaceQueries(state, writePass);
    this.verifier = new ChangeVerifier(state, router, writePass);
    this.renames = new RenameService(eventLoop, state, reactorIndex, router, search, docs);
  }

  // The session's components, for callers that already run on the event loop (the services and
  // the engine): each one checks the loop itself.
  DocumentFeatures features() {
    return features;
  }

  FormatService formats() {
    return formats;
  }

  WorkspaceQueries queries() {
    return queries;
  }

  RunCoordinator runs() {
    return runs;
  }

  ResourceMirror resourceMirror() {
    return resourceMirror;
  }

  WritePass writePass() {
    return writePass;
  }

  // Test seam: every module worker's queued work done and its results delivered.
  CompletableFuture<Void> workersDrained() {
    eventLoop.checkOnLoop();
    return state.registry().drained();
  }

  ChangeVerifier verifier() {
    return verifier;
  }

  RenameService renames() {
    return renames;
  }

  ReferenceSearch search() {
    return search;
  }

  SourceLayout layout() {
    return state.layout();
  }

  // The notes an agent's answer ends with, including a build-file sync this session knows is due.
  LatheNotes notes(final List<Path> files) {
    return verifier.notes(files, buildFilesChanged);
  }

  void initialize(final Path root) {
    memoryPressure = new MemoryPressure(eventLoop::execute, this::releaseUnderPressure);
    final var progress = progressReporter.open(null, new CompletableFuture<>());
    progress.begin(WORKSPACE_PROGRESS_TITLE, 1);
    final var t = Stopwatch.start();
    try {
      loadWorkspace(root);
    } finally {
      progress.finish(null);
      LOG.info(() -> "[workspace] ready %dms".formatted(t.elapsedMs()));
    }
  }

  private void loadWorkspace(final Path root) {
    final boolean configured = Files.isDirectory(root.resolve(LatheLayout.LATHE_DIR));
    state.load(root).close();
    reactorIndex.load();
    watcher = new WorkspaceWatcher(root);
    watcher.updateBuildFiles(
        state.manifest().buildFiles(), state.manifest().buildFileFingerprints());
    eventLoop.scheduleAtFixedRate(WritePass.scanIntervalMs(), this::onScanTick);
    if (configured) {
      showWorkspaceStatus("Lathe: workspace ready.");
    } else {
      client.showMessage(new MessageParams(MessageType.Warning, LatheLayout.SETUP_REMEDIATION));
    }

    // Startup reconciliation for the cold-start delta (changes while Lathe was down). Eager: the
    // delta is settled on disk, so compile it now rather than waiting out the two-tick guard.
    writePass.requestPass(true);
  }

  void close() {
    if (memoryPressure != null) {
      memoryPressure.close();
    }

    formats.close();
    state.registry().close();
  }

  // The heap is still mostly full after a collection: the sessions of modules with no open document
  // go first; the next tick lets the rest go too if that was not enough.
  private void releaseUnderPressure() {
    logRelease(
        "pressure", state.registry().releaseSessions(modulesWithOpenDocuments(), PRESSURE_IDLE_MS));
    pressureFollowUp = true;
  }

  // On the change-scan tick, which runs anyway. If a pressure round did not bring memory back under
  // the threshold, the sessions of modules with open files go too; and any session long unused.
  private int releaseSessionsOnTick() {
    int released = 0;
    if (pressureFollowUp && memoryPressure.exceeded()) {
      released +=
          logRelease(
              "pressure, files open", state.registry().releaseSessions(Set.of(), PRESSURE_IDLE_MS));
    }

    pressureFollowUp = false;
    if (state.registry().hasSessionIdleFor(IDLE_SESSION_MS)) {
      released +=
          logRelease(
              "idle",
              state.registry().releaseSessions(modulesWithOpenDocuments(), IDLE_SESSION_MS));
    }

    return released;
  }

  // INFO only when a round freed something: under pressure the JVM reports many times a second.
  private int logRelease(final String trigger, final int released) {
    final Level level = released > 0 ? Level.INFO : Level.FINE;
    LOG.log(
        level,
        () ->
            "[memory] %s heap=%s released %d module session(s)"
                .formatted(trigger, memoryPressure.usage(), released));
    return released;
  }

  private Set<ModuleSourceConfig> modulesWithOpenDocuments() {
    return docs.uris().stream()
        .map(uri -> state.registry().moduleSourceFor(LatheUri.toPath(uri)))
        .flatMap(Optional::stream)
        .collect(Collectors.toUnmodifiableSet());
  }

  void setDocumentsFollowDisk(final boolean follow) {
    documentsFollowDisk = follow;
  }

  void setInteractive(final boolean interactive) {
    this.interactive = interactive;
  }

  void setIdleScanPause(final long ms) {
    scanPace.pauseAfter(ms);
  }

  void onOpen(final String uri, final String content, final int version) {
    final var snapshot = docs.put(uri, content, version);
    LOG.info(() -> "[open] %s".formatted(uri));
    state.candidateIndex().update(uri, content);
    // An editor may open a buffer restored with unsaved changes.
    if (LatheUri.isFileUri(uri)
        && ServerFiles.readIfPresent(LatheUri.toPath(uri)).filter(content::equals).isPresent()) {
      unsaved.remove(uri);
    } else {
      unsaved.add(uri);
    }

    if (trackRename(renameTransaction.onContent(uri, content))
        == RenameTransaction.Action.SUPPRESS) {
      return; // compiled with the rest of the rename at its commit
    }

    compileAndPublish(snapshot, CompileMode.OPEN);
  }

  /**
   * Diagnostics for {@code content} at {@code uri}, completed as a future instead of published to
   * the client — the read path the in-process (MCP) facade uses. Each call reports the diagnostics
   * for the content it submitted; a read-only external or non-workspace file yields none.
   */
  CompletableFuture<List<Diagnostic>> diagnosticsFuture(
      final String uri, final String content, final int version) {
    LOG.fine(() -> "[diagnostics] %s".formatted(uri));
    final CompilerRoute route = router.routeCompiler(uri);
    if (route instanceof CompilerRoute.Missing) {
      return CompletableFuture.completedFuture(List.of());
    }

    // A dependency or JDK source is registered and compiled too, as the editor's open does, so the
    // engine's queries on it find an analysis; its diagnostics are not the agent's to fix.
    final boolean reactor = route instanceof CompilerRoute.Module;
    final var snapshot = docs.put(uri, content, version);
    if (reactor) {
      // The engine read this content from disk: indexed as a disk file, the pass keeps it current.
      state.candidateIndex().indexFromDisk(LatheUri.toPath(uri));
    }

    final var result = new CompletableFuture<List<Diagnostic>>();
    // A compile that throws (javac refusing the JVM's release, say) fails the request at once with
    // its cause, rather than leaving the caller to its timeout.
    submitCompile(
            route,
            snapshot,
            CompileMode.OPEN,
            (ignored, response) -> result.complete(reactor ? response.diagnostics() : List.of()))
        .exceptionally(
            ex -> {
              result.completeExceptionally(ex);
              return null;
            });
    return result;
  }

  void onChange(final String uri, final String content, final int version) {
    docs.put(uri, content, version);
    unsaved.add(uri);
    LOG.fine(() -> "[change] %s".formatted(uri));
    state.candidateIndex().update(uri, content);
    eventLoop.cancel(uri);
    if (trackRename(renameTransaction.onContent(uri, content))
        == RenameTransaction.Action.SUPPRESS) {
      return;
    }

    publisher.publishEmpty(uri);
    eventLoop.schedule(
        uri,
        debounceMs,
        () -> {
          final OpenDocument latest = docs.get(uri);
          if (latest != null) {
            compileAndPublish(latest, CompileMode.FAST);
          }
        });
  }

  void onClose(final String uri) {
    docs.remove(uri);
    unsaved.remove(uri);
    LOG.info(() -> "[close] %s".formatted(uri));
    eventLoop.cancel(uri);
    renameTransaction.onClose(uri);

    router.forget(uri);
    writeDiagnostics.remove(uri);
    state.registry().dropFromAllCaches(uri);
    publisher.publishEmpty(uri);
    state.candidateIndex().indexFromDisk(LatheUri.toPath(uri));
  }

  void onSave(final String uri, final String savedContent) {
    LOG.info(() -> "[save] %s".formatted(uri));
    unsaved.remove(uri);
    eventLoop.cancel(uri);

    final var snapshot = snapshotForSave(uri, savedContent);
    if (snapshot == null) {
      return;
    }

    final RenameTransaction.Action action =
        trackRename(renameTransaction.onSave(uri, savedContent));
    if (action == RenameTransaction.Action.COMMIT) {
      commitRenameTransaction();
    }

    if (action != RenameTransaction.Action.NORMAL) {
      return;
    }

    // A reactor file is written by a pass, whose refresh then publishes it with its dependents.
    final var route = router.routeCompiler(uri);
    if (route instanceof CompilerRoute.Module) {
      writePass.writeReported(Set.of(LatheUri.toPath(uri)));
      return;
    }

    final AfterCompile afterCompile =
        route instanceof CompilerRoute.External
            ? publishThen(route, () -> scheduleAstRefresh(uri))
            : publisher::publishIfCurrent;
    submitCompile(route, snapshot, CompileMode.OPEN, afterCompile);
  }

  // A document that follows the disk takes the file's new content as a new generation; a query
  // already running keeps the snapshot it took.
  private void reloadFromDisk(final Path source) {
    final String uri = LatheUri.fromPath(source);
    final OpenDocument open = docs.get(uri);
    if (open != null) {
      ServerFiles.readIfPresent(source)
          .ifPresent(content -> docs.put(uri, content, open.version()));
    }
  }

  // A rename member's formatted text is what its commit compiles.
  private void onFormatted(final String uri, final String content, final List<TextEdit> edits) {
    if (renameTransaction.isMember(uri)) {
      renameTransaction.onFormat(uri, Positions.applyEdits(content, edits));
    }
  }

  // The editor applies a rename's edit and saves its files; until the last save they are not
  // compiled one by one but together at the commit. A rename while one is open merges into it.
  void beginRenameTransaction(final WorkspaceEdit edit) {
    final Map<String, List<TextEdit>> textEdits = RenameProvider.textEdits(edit);
    if (textEdits.isEmpty()) {
      return;
    }

    if (renameTransaction.size() == 0) {
      renameTransaction = new RenameTransaction(); // drop what a fully abandoned one left behind
    }

    final RenameTransaction tx = renameTransaction;
    final Map<String, String> moves = RenameProvider.fileMoves(edit);
    textEdits.forEach(
        (uri, edits) -> {
          final OpenDocument open = docs.get(uri);
          final Optional<String> original =
              open != null
                  ? Optional.of(open.content())
                  : ServerFiles.readIfPresent(LatheUri.toPath(uri));
          original.ifPresent(
              text ->
                  tx.add(moves.getOrDefault(uri, uri), text, Positions.applyEdits(text, edits)));
        });
    moves.forEach(tx::addMovedAway);
    LOG.fine(() -> "[rename-tx] begin members=%d".formatted(tx.size()));
    eventLoop.schedule(RENAME_UNAPPLIED_KEY, RENAME_UNAPPLIED_MS, this::dropUnappliedRename);
  }

  // An edit the editor never applied (a cancelled refactor preview) was dormant all along, so its
  // files were handled as usual: forget it. One it applied waits for its saves however long the
  // user reviews it.
  private void dropUnappliedRename() {
    if (renameTransaction.size() > 0 && !renameTransaction.applied()) {
      LOG.fine(
          () -> "[rename-tx] dropped unapplied members=%d".formatted(renameTransaction.size()));
      renameTransaction = new RenameTransaction();
    }
  }

  // Once one of the rename's files is saved, each event on its files re-arms the idle commit; a
  // rename the user undid everywhere before saving is dropped.
  private RenameTransaction.Action trackRename(final RenameTransaction.Action action) {
    if (action == RenameTransaction.Action.ABANDON) {
      // Dropping it recompiles every open member, this one included.
      LOG.fine(() -> "[rename-tx] abandoned members=%d".formatted(renameTransaction.size()));
      commitRenameTransaction();
      return RenameTransaction.Action.SUPPRESS;
    }

    if (action != RenameTransaction.Action.NORMAL && renameTransaction.anySaved()) {
      eventLoop.schedule(RENAME_COMMIT_KEY, RENAME_IDLE_MS, this::commitRenameTransaction);
    }

    return action;
  }

  // Compiles the rename's saved files as one change set; its dependents refresh then publishes the
  // open ones. Files never saved become ordinary open documents again.
  private void commitRenameTransaction() {
    final RenameTransaction tx = renameTransaction;
    if (tx.size() == 0) {
      return;
    }

    renameTransaction = new RenameTransaction();
    eventLoop.cancel(RENAME_COMMIT_KEY);
    eventLoop.cancel(RENAME_UNAPPLIED_KEY);
    final Set<Path> saved = tx.savedPaths();
    final Set<String> unsaved = tx.unsavedUris();
    LOG.info(
        () -> "[rename-tx] commit saved=%d unsaved=%d".formatted(saved.size(), unsaved.size()));
    writePass.writeReported(saved);
    // The commit's dependents refresh recompiles open files downstream of what it compiled.
    final Set<Path> refreshed =
        saved.stream()
            .flatMap(path -> state.registry().moduleSourceFor(path).stream())
            .flatMap(
                config -> state.moduleGraph().downstreamModuleDirs(config.moduleDir()).stream())
            .collect(Collectors.toUnmodifiableSet());
    unsaved.stream()
        .filter(uri -> docs.get(uri) != null && !inDownstreamScope(uri, refreshed))
        .forEach(this::scheduleOpenFile);
  }

  // The engine's freshen step, before every MCP query and verify_change: the watcher's check, so
  // a build file the agent just edited is seen now rather than on the next tick, then an eager
  // pass. If its result can't be trusted (a build-file sync pending, or a running build) it
  // carries a DeferReason so verify_change falls back to the build. The outcome lists every stale
  // source, including one that failed unchanged since and so was not recompiled. Runs on the
  // event loop.
  CompletableFuture<ReconcileOutcome> reconcileForVerify() {
    checkForChanges();
    if (writePass.reactorBuildInProgress()) {
      return CompletableFuture.completedFuture(
          new ReconcileOutcome(List.of(), ReconcileOutcome.DeferReason.BUILD_IN_PROGRESS));
    }

    final ReconcileOutcome.DeferReason deferral =
        buildFilesChanged
            ? ReconcileOutcome.DeferReason.BUILD_FILES_PENDING
            : ReconcileOutcome.DeferReason.NONE;
    // Rejoined, so it runs in a later loop task, after the pass's own completion handlers: one of
    // them starts the index rebuild this waits for, and a plain thenCompose would run first.
    final CompletableFuture<ReconcileOutcome> outcome =
        eventLoop
            .rejoin(writePass.requestPass(true))
            .thenCompose(
                stale ->
                    reactorIndex
                        .rebuilt()
                        .thenApply(rebuilt -> new ReconcileOutcome(stale, deferral)));
    // Another server's pass holding the write lock may run for minutes: past a grace, the query
    // answers from the mirror as it is, and this pass still runs once the lock is free.
    CompletableFuture.delayedExecutor(
            WritePass.OTHER_SERVER_GRACE_MS, TimeUnit.MILLISECONDS, eventLoop::execute)
        .execute(
            () -> {
              if (writePass.waitingForWriteLock()) {
                outcome.complete(
                    new ReconcileOutcome(
                        List.of(), ReconcileOutcome.DeferReason.OTHER_SERVER_WRITING));
              }
            });
    return outcome;
  }

  private void keepWriteDiagnostics(final List<TransientSource> sources, final BatchWrite written) {
    sources.stream()
        .filter(source -> docs.get(source.uri()) != null)
        .forEach(
            source ->
                writeDiagnostics.put(
                    source.uri(),
                    new WriteDiagnostics(
                        source.content(),
                        written.diagnosticsByUri().getOrDefault(source.uri(), List.of()))));
  }

  // Adds what the file's last write reported and analysis cannot, while the analyzed content is
  // the written one.
  private CompileResponse withWriteDiagnostics(
      final OpenDocument snapshot, final CompileResponse result) {
    final WriteDiagnostics write = writeDiagnostics.get(snapshot.uri());
    if (write == null || !write.content().equals(snapshot.content())) {
      return result;
    }

    final Set<String> analyzed =
        result.diagnostics().stream()
            .map(WorkspaceSession::diagnosticKey)
            .collect(Collectors.toUnmodifiableSet());
    final List<Diagnostic> writeOnly =
        write.diagnostics().stream()
            .filter(diagnostic -> !analyzed.contains(diagnosticKey(diagnostic)))
            .toList();
    return writeOnly.isEmpty()
        ? result
        : new CompileResponse(
            result.uri(),
            result.generation(),
            Stream.concat(result.diagnostics().stream(), writeOnly.stream()).toList());
  }

  private static String diagnosticKey(final Diagnostic diagnostic) {
    return "%s|%s".formatted(diagnostic.getRange(), diagnostic.getMessage());
  }

  private Set<Path> openSourcePaths() {
    return docs.uris().stream()
        .filter(LatheUri::isFileUri)
        .map(LatheUri::toPath)
        .collect(Collectors.toUnmodifiableSet());
  }

  // Once a rename's first file is saved, this long without another of its files' events commits
  // the saved ones, so an editor that never saves the rest cannot hold the others back.
  private static final long RENAME_IDLE_MS = 10_000;
  private static final String RENAME_COMMIT_KEY = "rename-commit";
  // A rename none of whose files shows the edit this long after the response was never applied.
  private static final long RENAME_UNAPPLIED_MS = 30_000;
  private static final String RENAME_UNAPPLIED_KEY = "rename-unapplied";
  // A module session unused this long, in a module with no open file, is released.
  private static final long IDLE_SESSION_MS = 5 * 60 * 1000;
  // Under pressure, a session asked for within this long is left: it would only be rebuilt at once.
  private static final long PRESSURE_IDLE_MS = 10_000;
  private static final String SYNC_ACTION = "Sync";
  private static final String SYNC_CAPTURE_ACTION = "Sync + capture tests";
  private static final String LATER_ACTION = "Later";

  private void onScanTick() {
    switch (scanPace.next(eventLoop.idleMs(), writePass.settling())) {
      case SCAN -> checkForChanges();
      case SKIP -> {}
      case PAUSE -> {
        LOG.info(() -> "[watcher] paused, no request for %ds".formatted(eventLoop.idleMs() / 1000));
        releaseWhilePaused();
        returnFreeHeap();
      }
      case PAUSED -> {
        if (releaseWhilePaused() > 0) {
          returnFreeHeap();
        }
      }
    }
  }

  // The engine keeps every file it has queried as an open document, so a paused server lets the
  // sessions of their modules go too: no editor shows them, and the next query rebuilds one.
  private int releaseWhilePaused() {
    return logRelease("paused", state.registry().releaseSessions(Set.of(), IDLE_SESSION_MS));
  }

  // G1 hands freed heap back to the OS only after a full collection, so without one an idle server
  // keeps the peak of its last burst of work.
  private static void returnFreeHeap() {
    System.gc();
  }

  private void checkForChanges() {
    if (watcher == null) {
      return;
    }

    releaseSessionsOnTick();
    // Defer every reactive branch (staleness prompt, build-file/workspace reload) while a build
    // runs, so a mid-build workspace.json rewrite cannot re-arm the prompt. The first poll after
    // the lock clears picks up the settled state in one reload.
    if (writePass.reactorBuildInProgress()) {
      LOG.fine(() -> "[watcher] skipped — reactor build in progress");
      return;
    }

    final WorkspaceWatcher.PollResult change = watcher.poll();
    if (change != WorkspaceWatcher.PollResult.NO_CHANGE) {
      scanPace.changed();
    }

    switch (change) {
      case WORKSPACE_CHANGED -> {
        reload();
        writePass.forgetPendingStale();
        LOG.fine(() -> "[reload] pending-stale reset");
      }
      case REACTOR_REFRESH -> {
        refreshReactorTypeIndex();
        writePass.forgetPendingStale();
      }
      case BUILD_FILES_CHANGED -> {
        promptForSync("Build files changed. Lathe will run a full refresh.");
        writePass.requestPass(false);
      }
      // A prompt already shown stays (answering it still syncs), but MCP stops asking for a sync
      // and the next change prompts again.
      case BUILD_FILES_RESTORED -> {
        buildFilesChanged = false;
        writePass.requestPass(false);
      }
      // Every tick: a pass with nothing stale only scans, about 25 ms on a 377-tree reactor.
      case NO_CHANGE -> writePass.requestPass(false);
    }
  }

  private void promptForSync(final String message) {
    if (buildFilesChanged) {
      LOG.fine(() -> "[sync] prompt suppressed — already pending");
      return;
    }

    buildFilesChanged = true;
    if (!interactive) {
      LOG.info(() -> "[sync] due, no prompt: nobody to answer it");
      return;
    }

    final var request =
        new ShowMessageRequestParams(
            List.of(
                new MessageActionItem(SYNC_ACTION),
                new MessageActionItem(SYNC_CAPTURE_ACTION),
                new MessageActionItem(LATER_ACTION)));
    request.setMessage(message);
    request.setType(MessageType.Warning);
    eventLoop.rejoin(client.showMessageRequest(request)).thenAccept(this::onSyncPromptResponse);
  }

  // Sync / Sync + capture ask the client to run Maven (the server never does); Later just
  // dismisses. The watcher reports each build-file change once, so either way it stays quiet until
  // the files change again.
  private void onSyncPromptResponse(final MessageActionItem action) {
    buildFilesChanged = false;
    final String title = action == null ? null : action.getTitle();
    LOG.info(() -> "[sync] answered action=%s".formatted(title));
    if (SYNC_ACTION.equals(title)) {
      requestSync(false);
    } else if (SYNC_CAPTURE_ACTION.equals(title)) {
      requestSync(true);
    }
  }

  private void requestSync(final boolean captureTests) {
    LOG.info(() -> "[sync] requested capture=%b".formatted(captureTests));
    client.sync(new LatheSyncParams(state.root().toString(), captureTests));
  }

  private void reload() {
    LOG.info(() -> "[reload] workspace changed, reloading");
    final var progress = progressReporter.open(null, new CompletableFuture<>());
    progress.begin(WORKSPACE_PROGRESS_TITLE, 1);
    try {
      reloadWorkspace();
    } finally {
      progress.finish(null);
    }
  }

  private void reloadWorkspace() {
    writePass.onReload();
    final WorkspaceModuleRegistry old = state.load(state.root());
    if (watcher != null) {
      final WorkspaceManifest newManifest = state.manifest();
      watcher.updateBuildFiles(newManifest.buildFiles(), newManifest.buildFileFingerprints());
      buildFilesChanged = false;
    }

    refreshReactorTypeIndex();
    // What the last write reported came from the old build: the sync that changed the build also
    // compiled the sources, so no later write replaces it.
    writeDiagnostics.clear();
    refreshOpenDocuments();
    // Not waited for, which would hold the loop behind a batch write: the old workers finish their
    // queued work and close, and a pass still writing through them ends before the next starts.
    old.closeAsync();
    scheduleAllOpenFiles();
    showWorkspaceStatus("Lathe: workspace reloaded.");
  }

  // A workspace.json from another schema, or one that cannot be read, loads as empty, so say why
  // instead of "ready". The problem text is shared with the agent's notes, which carry no prefix.
  private void showWorkspaceStatus(final String okText) {
    client.showMessage(
        state
            .manifest()
            .loadProblem()
            .map(text -> new MessageParams(MessageType.Warning, "Lathe: %s".formatted(text)))
            .orElseGet(() -> new MessageParams(MessageType.Info, okText)));
  }

  // Re-scans the reactor mirror and rebuilds the type index without the full-reload work (no
  // candidate-index rebuild, no open-document refresh, no reload message).
  private void refreshReactorTypeIndex() {
    final var t = Stopwatch.start();
    reactorIndex.load();
    LOG.info(() -> "[refresh] reactor type index %dms".formatted(t.elapsedMs()));
  }

  private void refreshOpenDocuments() {
    for (final var uri : docs.uris()) {
      final OpenDocument f = docs.get(uri);
      docs.put(uri, f.content(), f.version());
    }
  }

  private void compileAndPublish(final OpenDocument snapshot, final CompileMode mode) {
    final var route = router.routeCompiler(snapshot.uri());
    submitCompile(route, snapshot, mode, (snap, result) -> publishDiagnostics(route, snap, result));
  }

  /**
   * Publishes real diagnostics for a workspace ({@code Module}) source, but an empty list for a
   * read-only external (JDK/dependency) source, so the user is not shown compiler diagnostics on a
   * file they cannot edit. Returns whether the snapshot was still current, so callers gate their
   * follow-up identically for both routes.
   */
  private boolean publishDiagnostics(
      final CompilerRoute route, final OpenDocument snapshot, final CompileResponse result) {
    return route instanceof CompilerRoute.External
        ? publisher.publishEmptyIfCurrent(snapshot, result)
        : publisher.publishIfCurrent(snapshot, withWriteDiagnostics(snapshot, result));
  }

  // The compile, which completes exceptionally if it threw; its error is published either way.
  private CompletableFuture<CompileResponse> submitCompile(
      final CompilerRoute route,
      final OpenDocument snapshot,
      final CompileMode mode,
      final AfterCompile afterCompile) {
    if (route instanceof final CompilerRoute.Missing missing) {
      publisher.publishMissing(missing.uri(), missing.message());
      return CompletableFuture.completedFuture(null);
    }

    final CompilationWorker moduleWorker = route.analysisWorker().orElseThrow();
    router.touchAnalysisCache(snapshot.uri());
    final List<CompileRequest> siblings =
        route instanceof final CompilerRoute.Module module
            ? unsavedSiblings(module.config(), snapshot)
            : List.of();
    final CompletableFuture<CompileResponse> compiled =
        moduleWorker.compile(requestFor(snapshot, mode), siblings);
    // Delivered on the loop; runLogged keeps a failure in the callback from vanishing into the
    // future this returns, which no one reads.
    compiled.whenComplete(
        (result, failure) -> {
          if (failure == null) {
            eventLoop.runLogged(() -> afterCompile.accept(snapshot, result));
          } else {
            publisher.publishError(snapshot, mode, ExceptionUtil.asCompletion(failure));
          }
        });
    return compiled;
  }

  // The tree's other unsaved open buffers, most recently edited first; the worker takes those that
  // parse, up to its cap.
  private List<CompileRequest> unsavedSiblings(
      final ModuleSourceConfig config, final OpenDocument target) {
    return unsaved.stream()
        .filter(uri -> !uri.equals(target.uri()))
        .map(docs::get)
        .filter(Objects::nonNull)
        .filter(doc -> inSourceTree(doc.uri(), config))
        .sorted(Comparator.comparingLong(OpenDocument::generation).reversed())
        .map(doc -> requestFor(doc, CompileMode.OPEN))
        .toList();
  }

  private boolean inSourceTree(final String uri, final ModuleSourceConfig config) {
    return state.registry().moduleSourceFor(LatheUri.toPath(uri)).map(config::equals).orElse(false);
  }

  private AfterCompile publishThen(final CompilerRoute route, final Runnable followUp) {
    return (snapshot, result) -> {
      if (publishDiagnostics(route, snapshot, result)) {
        LOG.info(() -> "[save] compiled %s".formatted(snapshot.uri()));
        followUp.run();
      }
    };
  }

  // Re-analyze the open files in the scope's modules, so what the passes wrote shows in their
  // diagnostics.
  private void refreshOpenFiles(final Set<Path> scope) {
    LOG.fine(
        () ->
            "[dependents] checking %d open file(s) across %d module(s)"
                .formatted(docs.count(), scope.size()));
    final List<String> dependents =
        docs.all().stream()
            .map(OpenDocument::uri)
            // An open rename's files compile at its commit, not against the half-renamed mirror.
            .filter(uri -> !renameTransaction.isMember(uri))
            .filter(uri -> inDownstreamScope(uri, scope))
            .toList();
    // A module's dependents are analyzed one task per source tree, not one compile per file.
    final var byTree = new LinkedHashMap<ModuleSourceConfig, List<String>>();
    for (final String uri : dependents) {
      if (router.routeCompiler(uri) instanceof final CompilerRoute.Module module) {
        byTree.computeIfAbsent(module.config(), config -> new ArrayList<>()).add(uri);
      } else {
        scheduleDependent(uri);
      }
    }

    byTree.forEach(
        (config, uris) -> {
          for (int from = 0; from < uris.size(); from += CompilationWorker.ANALYSIS_BATCH_MAX) {
            refreshAsBatch(
                config,
                uris.subList(
                    from, Math.min(from + CompilationWorker.ANALYSIS_BATCH_MAX, uris.size())));
          }
        });
  }

  private void refreshAsBatch(final ModuleSourceConfig config, final List<String> uris) {
    final List<OpenDocument> snapshots =
        uris.stream().map(docs::get).filter(Objects::nonNull).toList();
    if (snapshots.size() < 2) {
      snapshots.forEach(snapshot -> scheduleDependent(snapshot.uri()));
      return;
    }

    final var requests = new ArrayList<CompileRequest>(snapshots.size());
    for (final OpenDocument snapshot : snapshots) {
      eventLoop.cancel(snapshot.uri());
      router.touchAnalysisCache(snapshot.uri());
      requests.add(requestFor(snapshot, CompileMode.OPEN));
    }

    LOG.fine(
        () ->
            "[dependents] batch %d file(s) in %s"
                .formatted(snapshots.size(), state.layout().moduleRel(config)));
    state
        .registry()
        .workerFor(config)
        .compileOpenBatch(List.copyOf(requests))
        .whenComplete(
            (responses, failure) -> {
              if (failure == null) {
                eventLoop.runLogged(() -> publishBatch(snapshots, responses));
              } else {
                LOG.log(
                    Level.FINE, failure, () -> "[dependents] batch failed, compiling one by one");
                snapshots.forEach(s -> scheduleDependent(s.uri()));
              }
            });
  }

  private static CompileRequest requestFor(final OpenDocument snapshot, final CompileMode mode) {
    return new CompileRequest(
        snapshot.uri(), snapshot.content(), snapshot.version(), snapshot.generation(), mode);
  }

  // Each result publishes as its own compile would; semantic tokens refresh once for the batch.
  private void publishBatch(
      final List<OpenDocument> snapshots, final List<CompileResponse> responses) {
    boolean published = false;
    for (int i = 0; i < snapshots.size(); i++) {
      if (publisher.publishQuietlyIfCurrent(
          snapshots.get(i), withWriteDiagnostics(snapshots.get(i), responses.get(i)))) {
        published = true;
      }
    }

    if (published) {
      publisher.refreshTokens();
    }
  }

  private void scheduleDependent(final String uri) {
    LOG.fine(() -> "[dependents] recompiling %s".formatted(uri));
    scheduleOpenFile(uri);
  }

  record WriteDiagnostics(String content, List<Diagnostic> diagnostics) {
    WriteDiagnostics {
      ValidCheck.check().notNull(content, "content").notNull(diagnostics, "diagnostics").validate();
      diagnostics = List.copyOf(diagnostics);
    }
  }

  private boolean inDownstreamScope(final String uri, final Set<Path> scope) {
    return state
        .registry()
        .moduleSourceFor(LatheUri.toPath(uri))
        .map(m -> scope.contains(m.moduleDir()))
        .orElse(false);
  }

  private void scheduleAllOpenFiles() {
    docs.all().stream().map(OpenDocument::uri).toList().forEach(this::scheduleOpenFile);
  }

  private void scheduleOpenFile(final String uri) {
    scheduleCompile(uri, openFile -> compileAndPublish(openFile, CompileMode.OPEN));
  }

  private void scheduleAstRefresh(final String uri) {
    scheduleCompile(
        uri,
        openFile ->
            submitCompile(
                router.routeCompiler(uri),
                openFile,
                CompileMode.FAST,
                publisher::refreshTokensIfCurrent));
  }

  // Compiles the open document once the loop is free; nothing if it was closed meanwhile.
  private void scheduleCompile(final String uri, final Consumer<OpenDocument> compile) {
    eventLoop.schedule(
        uri,
        0L,
        () -> {
          final OpenDocument openFile = docs.get(uri);
          if (openFile != null) {
            compile.accept(openFile);
          }
        });
  }

  private OpenDocument snapshotForSave(final String uri, final String savedContent) {
    final OpenDocument openFile = docs.get(uri);
    if (openFile == null) {
      return null;
    }

    return savedContent != null ? docs.put(uri, savedContent, openFile.version()) : openFile;
  }

  @FunctionalInterface
  private interface AfterCompile {
    void accept(OpenDocument snapshot, CompileResponse result);
  }
}
