package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.BatchWrite;
import io.github.aglibs.lathe.analysis.CodeActionRequest;
import io.github.aglibs.lathe.analysis.CompileMode;
import io.github.aglibs.lathe.analysis.DiagnosticPayload;
import io.github.aglibs.lathe.analysis.LearnedStaticImports;
import io.github.aglibs.lathe.analysis.MissingImportsResult;
import io.github.aglibs.lathe.analysis.RenameProvider;
import io.github.aglibs.lathe.analysis.RunTarget;
import io.github.aglibs.lathe.analysis.SemanticToken;
import io.github.aglibs.lathe.analysis.SourceFeatureRequest;
import io.github.aglibs.lathe.analysis.SourceLocator;
import io.github.aglibs.lathe.analysis.TokenScanner;
import io.github.aglibs.lathe.analysis.TransientSource;
import io.github.aglibs.lathe.analysis.TypeHierarchyExplorerResult;
import io.github.aglibs.lathe.analysis.TypeHierarchyItemDataCodec;
import io.github.aglibs.lathe.analysis.WorkspaceSymbolResolver;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.analysis.completion.CompletionOutcome;
import io.github.aglibs.lathe.core.ExceptionUtil;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.schema.RunKind;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.engine.LatheNotes;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import io.github.aglibs.lathe.server.module.CompileRequest;
import io.github.aglibs.lathe.server.module.CompileResponse;
import io.github.aglibs.lathe.server.module.ModuleNameDiscovery;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.lathe.server.module.WorkspaceModuleRegistry;
import io.github.aglibs.lathe.server.run.RunConfigWriter;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

/** Not thread-safe. All methods must be called from the {@link ServerEventLoop} thread. */
final class WorkspaceSession {

  private static final Logger LOG = Logger.getLogger(WorkspaceSession.class.getName());

  // Upper bound on transitive subtypes returned by the type-hierarchy explorer, so a near-Object or
  // huge marker-interface anchor cannot flood the picker or the source-locating pass. Beyond it the
  // result is flagged truncated.
  private static final int TYPE_HIERARCHY_NODE_CAP = 2000;

  // Title of the $/progress task reported while the workspace loads/reloads. The neotest adapter
  // matches on it to gate discovery on readiness, so it is a cross-process contract: keep it in
  // sync with the mirror in lua/lathe/neotest.lua. Also user-visible via vim.lsp.status().
  static final String WORKSPACE_PROGRESS_TITLE = "Lathe: indexing workspace";

  private final LanguageClient client;
  private final ProgressReporter progressReporter;
  private final ServerEventLoop eventLoop;
  private final long debounceMs;
  private final WorkspaceState state;
  private final ReactorTypeIndex reactorIndex;
  private final RunCoordinator runs;
  private final FeatureRouter router;
  private final ReferenceSearch search;
  private final WritePass writePass;
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
  // The -pl module selectors carried from the pending sync prompt to the sync request (empty = full
  // reactor). Set at prompt time because the request fires later, on the user's response.
  private List<String> pendingSyncModules = List.of();
  private final DocumentRegistry docs = new DocumentRegistry();
  // Set by the in-process engine: its documents are copies of the disk, not editor buffers, so a
  // change on disk replaces them.
  private boolean documentsFollowDisk;
  private final DiagnosticPublisher publisher;

  // Loop state like the rest: a format failure is reported to the user once, then only logged.
  private boolean formatFailureReported;
  // Formatting runs here, off the event loop: an external command waits on its process, a build
  // tool for up to a minute. One thread, so two formats never run a build in one module at once.
  private final ExecutorService formatThread =
      Executors.newSingleThreadExecutor(WorkspaceSession::newFormatThread);
  // The formats queued or running there, so close() can cancel them: the thread's shutdown drops
  // what is queued, and a caller would otherwise wait for an answer that cannot come.
  private final Set<CompletableFuture<List<TextEdit>>> formatsInFlight = new HashSet<>();

  WorkspaceSession(
      final LanguageClient client,
      final ProgressReporter progressReporter,
      final ServerEventLoop eventLoop,
      final long debounceMs) {
    this.client = client;
    this.progressReporter = progressReporter;
    this.eventLoop = eventLoop;
    this.debounceMs = debounceMs;
    this.publisher = new DiagnosticPublisher(client, docs);
    this.state = new WorkspaceState(eventLoop);
    this.reactorIndex = new ReactorTypeIndex(eventLoop, state, this::openSourcePaths);
    this.runs = new RunCoordinator(client, eventLoop, state, reactorIndex);
    this.router = new FeatureRouter(eventLoop, state, docs);
    this.search = new ReferenceSearch(eventLoop, state, reactorIndex, router, docs);
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
                () -> renameTransaction.memberPaths()));
    this.verifier = new ChangeVerifier(state, router, writePass);
    this.renames = new RenameService(eventLoop, state, reactorIndex, router, search, docs);
  }

  // The session's components, for callers that already run on the event loop (the services and
  // the engine): each one checks the loop itself.
  RunCoordinator runs() {
    return runs;
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

    formatThread.shutdownNow();
    List.copyOf(formatsInFlight).forEach(format -> format.cancel(false));
    state.registry().close();
  }

  private static Thread newFormatThread(final Runnable task) {
    final var thread = new Thread(task, "lathe-format");
    thread.setDaemon(true);
    return thread;
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

  RunConfigWriter.Saved saveRunConfig(
      final String name,
      final String moduleRel,
      final RunKind kind,
      final String mainClass,
      final List<TestSelection> selectors,
      final boolean overwrite)
      throws IOException {
    return runs.saveRunConfig(name, moduleRel, kind, mainClass, selectors, overwrite);
  }

  CreateTypeResult createType(final CreateTypeArgs args) {
    // module-info.java always belongs to the main source root, whatever scope the client sent.
    final var scope = args.type() == TypeKind.MODULE_INFO ? SourceScope.MAIN : args.kind();
    final var sourceRoot = state.layout().sourceRootFor(args.moduleRel(), scope);
    LOG.info(
        () ->
            "[createType] %s %s.%s kind=%s type=%s"
                .formatted(args.moduleRel(), args.pkg(), args.name(), args.kind(), args.type()));
    return NewTypeTemplates.render(sourceRoot, args.pkg(), args.type(), args.name());
  }

  List<ResourceEntry> resources() {
    return ResourceFinder.list(
        state.manifest().resourceDirModules(), state.manifest().libraries().jarToGav());
  }

  // The tests of the file's type, or from a test the type it tests.
  TestCounterparts testCounterparts(final String uri) {
    final Path file = LatheUri.toPath(uri);
    return TestCounterparts.find(
        file,
        state.layout().filePackage(file),
        state.registry()::moduleSourceFor,
        reactorIndex.current(),
        state.registry().allSourceRoots());
  }

  // Extracts one dependency jar entry to disk (read-only) and returns its path -- the only bytes
  // written, on demand. Reuses the dep's sources cache dir when present, else a temp dir. Reactor
  // resources need no extraction (they carry their real path).
  String resourceOpen(final String jar, final String entry) {
    final var jarPath = Path.of(jar);
    final Path dest =
        state
            .manifest()
            .libraries()
            .dependencySourceDir(jarPath)
            .map(dir -> dir.resolve(entry))
            .orElseGet(ResourceFinder.tempDest(entry));
    try {
      FileUtil.extractEntry(jarPath, entry, dest);
    } catch (final IOException e) {
      throw new UncheckedIOException("extract %s from %s".formatted(entry, jar), e);
    }

    return dest.toString();
  }

  CompletableFuture<List<RunTarget>> runnablesFuture(final String uri) {
    // Only an open document has editor content to analyze. The neotest client discovers against the
    // server-confirmed open (its first publishDiagnostics), so a miss here means the file genuinely
    // isn't open — answer empty rather than speculatively compile from disk.
    final OpenDocument doc = docs.get(uri);
    if (doc == null) {
      LOG.fine(() -> "[runnables] %s no open doc → empty".formatted(uri));
      return CompletableFuture.completedFuture(List.of());
    }

    final var t = Stopwatch.start();
    if (router.routeCompiler(uri) instanceof final CompilerRoute.Module module) {
      router.touchAnalysisCache(uri);
      return module
          .worker()
          .runnables(uri, doc.content(), doc.version(), state.layout().moduleRel(module.config()))
          .thenApply(FeatureAnswers.logged(LOG, "runnables", uri, t, "targets"));
    }

    return CompletableFuture.completedFuture(List.of());
  }

  // The registry module owning the file, as a command formatter's %MODULE% names it: "." for the
  // root module.
  Optional<String> moduleRelOf(final Path file) {
    return state
        .registry()
        .moduleSourceFor(file)
        .map(state.layout()::moduleRel)
        .map(SourceLayout::displayed);
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
        && FileUtil.readIfPresent(LatheUri.toPath(uri)).filter(content::equals).isPresent()) {
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

  // A reactor file checked without opening it: no open document, no analysis kept. verify_change
  // checks a change's files and their callers this way, many at once.
  CompletableFuture<List<Diagnostic>> diagnosticsTransientFuture(
      final String uri, final String content) {
    return router.routeCompiler(uri) instanceof final CompilerRoute.Module module
        ? module.worker().diagnosticsTransient(uri, content)
        : CompletableFuture.completedFuture(List.of());
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
      FileUtil.readIfPresent(source).ifPresent(content -> docs.put(uri, content, open.version()));
    }
  }

  CompletableFuture<Hover> hoverFuture(final String uri, final Position pos) {
    return router.openDocFeature(
        "hover", uri, null, (worker, doc) -> worker.hover(router.featureRequest(doc, pos)));
  }

  // Whether the position is on a name. What cannot be checked (a file no module compiles, a failed
  // check) passes, and the tool gives its own answer.
  CompletableFuture<Boolean> onNameFuture(final String uri, final Position pos) {
    return router.openDocFeature(
        "onName", uri, true, (worker, doc) -> worker.onName(router.featureRequest(doc, pos)));
  }

  CompletableFuture<SignatureHelp> signatureHelpFuture(final String uri, final Position pos) {
    return router.openDocFeature(
        "signatureHelp",
        uri,
        null,
        (worker, doc) -> worker.signatureHelp(router.featureRequest(doc, pos)));
  }

  CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      definitionFuture(final String uri, final Position pos) {
    return locationFuture("definition", uri, pos, CompilationWorker::definition);
  }

  CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      declarationFuture(final String uri, final Position pos) {
    return locationFuture("declaration", uri, pos, CompilationWorker::declaration);
  }

  CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      typeDefinitionFuture(final String uri, final Position pos) {
    return locationFuture("typeDefinition", uri, pos, CompilationWorker::typeDefinition);
  }

  // One location for the open document's position, found by the document's module worker.
  private CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      locationFuture(
          final String operation,
          final String uri,
          final Position pos,
          final BiFunction<
                  CompilationWorker, SourceFeatureRequest, CompletableFuture<Optional<Location>>>
              find) {
    LOG.info(
        () ->
            "[%s] %s line=%d character=%d"
                .formatted(operation, uri, pos.getLine(), pos.getCharacter()));
    return router.openDocFeature(
        operation,
        uri,
        Either.forLeft(List.of()),
        (worker, doc) ->
            find.apply(worker, router.featureRequest(doc, pos))
                .thenApply(
                    location ->
                        FeatureAnswers.definitionResult(
                            location.map(List::of).orElseGet(List::of))));
  }

  CompletableFuture<List<TypeHierarchyItem>> prepareTypeHierarchyFuture(
      final String uri, final Position pos) {
    final var indexSnapshot = reactorIndex.current();
    final var t = Stopwatch.start();
    return router
        .openDocFeature(
            "typeHierarchy:prepare",
            uri,
            List.of(),
            (worker, doc) -> {
              final var request = router.featureRequest(doc, pos);
              return worker.prepareTypeHierarchy(request, indexSnapshot);
            })
        .thenApply(FeatureAnswers.logged(LOG, "typeHierarchy:prepare", uri, t, "items"));
  }

  CompletableFuture<List<TypeHierarchyItem>> typeHierarchySupertypesFuture(
      final TypeHierarchyItem item) {
    return typeHierarchyLevel(
        "typeHierarchy:supertypes", item, CompilationWorker::typeHierarchySupertypes);
  }

  CompletableFuture<List<TypeHierarchyItem>> typeHierarchySubtypesFuture(
      final TypeHierarchyItem item) {
    return typeHierarchyLevel(
        "typeHierarchy:subtypes", item, CompilationWorker::typeHierarchySubtypes);
  }

  // One level of a type's hierarchy, answered by the worker of the module that holds it.
  private CompletableFuture<List<TypeHierarchyItem>> typeHierarchyLevel(
      final String operation, final TypeHierarchyItem item, final TypeHierarchyStep step) {
    final var data = TypeHierarchyItemDataCodec.decode(item.getData());
    if (data == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    final var indexSnapshot = reactorIndex.current();
    final List<Path> sourceDirs = state.typeSourceDirs();
    final var t = Stopwatch.start();
    return router
        .routeFeature(
            operation,
            data.routingUri(),
            List.of(),
            worker -> step.apply(worker, item, indexSnapshot, sourceDirs))
        .thenApply(FeatureAnswers.logged(LOG, operation, data.binaryName(), t, "items"));
  }

  @FunctionalInterface
  private interface TypeHierarchyStep {
    CompletableFuture<List<TypeHierarchyItem>> apply(
        CompilationWorker worker,
        TypeHierarchyItem item,
        WorkspaceTypeIndex index,
        List<Path> sourceDirs);
  }

  CompletableFuture<TypeHierarchyExplorerResult> typeHierarchyExplorerFuture(
      final String uri, final Position pos) {
    final var t = Stopwatch.start();
    return prepareTypeHierarchyFuture(uri, pos)
        .thenCompose(
            items -> {
              if (items.isEmpty()) {
                return CompletableFuture.completedFuture(TypeHierarchyExplorerResult.empty());
              }

              final var self = items.get(0);
              final var data = TypeHierarchyItemDataCodec.decode(self.getData());
              final var indexSnapshot = reactorIndex.current();
              final List<Path> sourceDirs = state.typeSourceDirs();
              return router.routeFeature(
                  "typeHierarchy:explore",
                  data.routingUri(),
                  TypeHierarchyExplorerResult.empty(),
                  worker ->
                      worker.typeHierarchyExplore(
                          self, indexSnapshot, sourceDirs, TYPE_HIERARCHY_NODE_CAP));
            })
        .thenApply(
            result -> {
              LOG.info(
                  () ->
                      "[typeHierarchy:explore] %s %dms supertypes=%d subtypes=%d%s"
                          .formatted(
                              uri,
                              t.elapsedMs(),
                              result.supertypes().size(),
                              result.subtypes().size(),
                              result.truncated() ? " truncated" : ""));
              return result;
            });
  }

  CompletableFuture<CompletionOutcome> completionFuture(
      final String uri, final Position pos, final CompletionContext context) {
    final var indexSnapshot = reactorIndex.current();
    final var route = router.routeCompiler(uri);
    final LearnedStaticImports staticImports = router.learnedStaticImports(route);
    return router.openDocFeature(
        "completion",
        uri,
        CompletionOutcome.of(List.of()),
        (worker, doc) ->
            worker.complete(
                uri,
                doc.content(),
                doc.version(),
                pos,
                context,
                indexSnapshot,
                completionModuleNames(route, uri),
                staticImports));
  }

  CompletableFuture<List<Either<Command, CodeAction>>> codeActionFuture(
      final String uri,
      final Range range,
      final CodeActionContext context,
      final boolean resolveSupported) {
    final OpenDocument openFile = docs.get(uri);
    if (openFile == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    final CompilerRoute route = router.routeCompiler(uri);
    if (route instanceof CompilerRoute.External) {
      return CompletableFuture.completedFuture(List.of());
    }

    final List<CodeActionRequest> requests =
        context.getDiagnostics().stream()
            .map(diag -> toCodeActionRequest(uri, diag))
            .filter(Objects::nonNull)
            .toList();

    final Set<String> neededTypeNames =
        requests.stream()
            .filter(r -> r.payload().kind().offersTypeImport())
            .map(r -> r.payload().name())
            .collect(Collectors.toUnmodifiableSet());
    final var baseIndex = reactorIndex.current();
    final List<List<TypeIndexEntry>> reactorEntries = reactorIndex.shards();
    final LearnedStaticImports staticImports = router.learnedStaticImports(route);
    return router.routeFeature(
        "codeAction",
        uri,
        List.of(),
        moduleWorker ->
            moduleWorker
                .cachedTypeEntries(neededTypeNames)
                .thenCompose(openEntries -> enrichedIndex(baseIndex, reactorEntries, openEntries))
                .thenCompose(
                    enriched ->
                        moduleWorker.codeAction(
                            uri,
                            openFile.content(),
                            openFile.version(),
                            range,
                            requests,
                            enriched,
                            staticImports))
                .thenApply(
                    actions ->
                        resolveSupported
                            ? CompileCheckedActions.defer(uri, openFile.version(), actions)
                            : actions));
  }

  // The check may compile twice (the rewritten text, then the original to compare errors); the
  // second starts from the first one's result, which the worker delivers on the loop.
  CompletableFuture<CodeAction> resolveCodeActionFuture(final CodeAction action) {
    return CompileCheckedActions.resolve(action, docs::get, this::diagnosticsTransientFuture);
  }

  CompletableFuture<MissingImportsResult> missingImportsFuture(final String uri) {
    final OpenDocument openFile = docs.get(uri);
    final CompilerRoute route = router.routeCompiler(uri);
    if (openFile == null || route instanceof CompilerRoute.External) {
      return CompletableFuture.completedFuture(MissingImportsResult.empty());
    }

    final var indexSnapshot = reactorIndex.current();
    final LearnedStaticImports staticImports = router.learnedStaticImports(route);
    return router.routeFeature(
        "missingImports",
        uri,
        MissingImportsResult.empty(),
        moduleWorker ->
            moduleWorker.missingImports(
                uri, openFile.content(), openFile.version(), indexSnapshot, staticImports));
  }

  // The index with the open files' types added. Built off the loop, as a rebuild takes 100-200ms on
  // a large reactor (as ReactorTypeIndex's), and back on the loop with the result.
  private CompletableFuture<WorkspaceTypeIndex> enrichedIndex(
      final WorkspaceTypeIndex base,
      final List<List<TypeIndexEntry>> reactorEntries,
      final List<TypeIndexEntry> openFileEntries) {
    if (openFileEntries.isEmpty()) {
      return CompletableFuture.completedFuture(base);
    }

    final List<List<TypeIndexEntry>> allReactor =
        Stream.concat(reactorEntries.stream(), Stream.of(openFileEntries)).toList();
    return eventLoop.rejoin(
        CompletableFuture.supplyAsync(() -> base.withReactorEntries(allReactor)));
  }

  private static CodeActionRequest toCodeActionRequest(final String uri, final Diagnostic diag) {
    final DiagnosticPayload payload = DiagnosticPayloadCodec.extractPayload(diag.getData());
    return payload != null ? new CodeActionRequest(uri, diag, payload) : null;
  }

  CompletableFuture<SemanticTokens> semanticTokensFuture(final String uri) {
    return router.openDocFeature(
        "semanticTokens",
        uri,
        null,
        (worker, doc) ->
            worker
                .semanticTokens(uri, doc.content(), doc.version())
                .thenApply(WorkspaceSession::encodeTokensOrNull));
  }

  CompletableFuture<List<DocumentSymbol>> documentSymbolFuture(final String uri) {
    return router.openDocFeature(
        "documentSymbol",
        uri,
        List.of(),
        (worker, doc) -> worker.documentSymbol(uri, doc.content()));
  }

  CompletableFuture<List<FoldingRange>> foldingRangeFuture(final String uri) {
    final OpenDocument doc = docs.get(uri);
    if (doc == null) {
      LOG.warning(() -> "[foldingRange] %s no folds document not open".formatted(uri));
      return CompletableFuture.completedFuture(List.of());
    }

    return router.routeFeature(
        "foldingRange", uri, List.of(), worker -> worker.foldingRange(uri, doc.content()));
  }

  CompletableFuture<List<TextEdit>> formatFuture(final String uri, final FormatEngine engine) {
    return formatDocument(uri, (content, target) -> JavaFormatter.format(engine, content, target));
  }

  CompletableFuture<List<TextEdit>> formatRangesFuture(
      final String uri, final List<Range> ranges, final FormatEngine engine) {
    return formatDocument(
        uri, (content, target) -> JavaFormatter.formatRanges(engine, content, ranges));
  }

  // For the agent, which must tell a failure from an already formatted file: a refusal that
  // carries the formatter's reason.
  CompletableFuture<List<TextEdit>> formatOrRefuseFuture(
      final String uri, final FormatEngine engine) {
    return formatted(uri, (content, target) -> JavaFormatter.format(engine, content, target))
        .exceptionally(
            failure -> {
              throw FeatureAnswers.refused(
                  "Could not format %s: %s"
                      .formatted(
                          LatheUri.toPath(uri).getFileName(),
                          ExceptionUtil.rootCause(failure).getMessage()));
            });
  }

  // Formats the open document's text, reporting the outcome once and answering no edits on a
  // failure.
  private CompletableFuture<List<TextEdit>> formatDocument(
      final String uri, final DocumentFormat format) {
    final var t = Stopwatch.start();
    return formatted(uri, format)
        .handle(
            (result, failure) -> {
              if (failure == null) {
                // Formatting is a synchronous request (format-on-save blocks the editor), so a
                // progress spinner would only paint after the edit; report the outcome once it is
                // done instead. Stay quiet when nothing changed, so saving an already-formatted
                // file does not nag.
                if (!result.isEmpty()) {
                  client.showMessage(
                      new MessageParams(
                          MessageType.Info, "Lathe: formatted in %dms".formatted(t.elapsedMs())));
                }

                return result;
              }

              final Throwable cause = ExceptionUtil.completionCause(failure);
              if (cause instanceof CancellationException) {
                LOG.fine(() -> "[format] %s cancelled: the server is closing".formatted(uri));
                return List.of();
              }

              LOG.log(
                  Level.SEVERE,
                  cause,
                  () -> "[format] %s failed %dms".formatted(uri, t.elapsedMs()));
              // Format-on-save would repeat the same failure (a formatter that cannot run on this
              // JDK, a file that does not parse) on every save; say why once, then only log.
              if (!formatFailureReported) {
                formatFailureReported = true;
                client.showMessage(
                    new MessageParams(
                        MessageType.Warning,
                        "Lathe: formatting failed: %s (further failures are only logged)"
                            .formatted(ExceptionUtil.rootCause(cause).getMessage())));
              }

              return List.of();
            });
  }

  // The open document's text and its FormatTarget are read on the loop; the format runs on the
  // format thread, as an external command waits on its process; the result comes back on the loop,
  // where a pending rename hears of it.
  private CompletableFuture<List<TextEdit>> formatted(
      final String uri, final DocumentFormat format) {
    final var t = Stopwatch.start();
    final OpenDocument openFile = docs.get(uri);
    final String content = openFile != null ? openFile.content() : null;
    final FormatTarget target = formatTarget(LatheUri.toPath(uri));
    final CompletableFuture<List<TextEdit>> running =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return format.apply(content, target);
              } catch (final Exception e) {
                if (e instanceof InterruptedException) {
                  Thread.currentThread().interrupt();
                }

                throw ExceptionUtil.asCompletion(e);
              }
            },
            formatThread);
    formatsInFlight.add(running);
    return eventLoop
        .rejoin(running)
        .whenComplete((result, failure) -> formatsInFlight.remove(running))
        .thenApply(
            result -> {
              LOG.info(
                  () -> "[format] %s %dms edits=%d".formatted(uri, t.elapsedMs(), result.size()));
              if (!result.isEmpty() && renameTransaction.isMember(uri)) {
                renameTransaction.onFormat(uri, SourceLocator.applyEdits(content, result));
              }

              return result;
            });
  }

  private FormatTarget formatTarget(final Path file) {
    return new FormatTarget(
        file, moduleRelOf(file).orElse("."), state.manifest().buildTool().orElse(null));
  }

  @FunctionalInterface
  private interface DocumentFormat {
    List<TextEdit> apply(String content, FormatTarget target) throws Exception;
  }

  List<SymbolInformation> workspaceSymbol(final String query, final boolean reactorOnly) {
    final var t = Stopwatch.start();
    final List<Path> sourceDirs = state.typeSourceDirs();
    final List<SymbolInformation> results =
        WorkspaceSymbolResolver.resolve(
            query,
            reactorIndex.current(),
            sourceDirs,
            entry -> !reactorOnly || reactorIndex.current().isReactorType(entry.binaryName()));
    LOG.info(
        () -> "[symbol] query=%s %dms hits=%d".formatted(query, t.elapsedMs(), results.size()));
    return results;
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
                  : FileUtil.readIfPresent(LatheUri.toPath(uri));
          original.ifPresent(
              text ->
                  tx.add(
                      moves.getOrDefault(uri, uri), text, SourceLocator.applyEdits(text, edits)));
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
            OTHER_SERVER_GRACE_MS, TimeUnit.MILLISECONDS, eventLoop::execute)
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
  // Long enough for another server's pass after a save; a query waits no longer than this for it.
  private static final long OTHER_SERVER_GRACE_MS = 2_000;
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
        promptForSync("Build files changed. Lathe will run a full refresh.", List.of());
        writePass.requestPass(false);
      }
      // A prompt already shown stays (answering it still syncs), but MCP stops asking for a sync
      // and the next change prompts again.
      case BUILD_FILES_RESTORED -> {
        buildFilesChanged = false;
        pendingSyncModules = List.of();
        writePass.requestPass(false);
      }
      // Every tick: a pass with nothing stale only scans, about 25 ms on a 377-tree reactor.
      case NO_CHANGE -> writePass.requestPass(false);
    }
  }

  private void promptForSync(final String message, final List<String> scope) {
    if (buildFilesChanged) {
      LOG.fine(() -> "[sync] prompt suppressed — already pending");
      return;
    }

    pendingSyncModules = scope;
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
    final Object scope = pendingSyncModules.isEmpty() ? "full" : pendingSyncModules;
    LOG.info(() -> "[sync] requested scope=%s capture=%b".formatted(scope, captureTests));
    ((LatheLanguageClient) client)
        .sync(new LatheSyncParams(state.root().toString(), captureTests, pendingSyncModules));
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
    refreshOpenDocuments();
    old.close();
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
    for (final var uri : List.copyOf(docs.uris())) {
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
   * file they cannot edit (EG-041). Returns whether the snapshot was still current, so callers gate
   * their follow-up identically for both routes.
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

  private static List<String> completionModuleNames(final CompilerRoute route, final String uri) {
    if (!LatheLayout.MODULE_INFO_JAVA.equals(LatheUri.toPath(uri).getFileName().toString())) {
      return List.of();
    }

    if (route instanceof final CompilerRoute.Module module) {
      return ModuleNameDiscovery.observableModuleNames(module.config());
    }

    return List.of();
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
                .formatted(docs.all().size(), scope.size()));
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
        .compileOpenBatch(requests)
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

  private static SemanticTokens encodeTokensOrNull(final List<SemanticToken> tokens) {
    if (tokens == null) {
      return null;
    }

    final int[] encoded = TokenScanner.encode(tokens);
    return new SemanticTokens(IntStream.of(encoded).boxed().toList());
  }

  @FunctionalInterface
  private interface AfterCompile {
    void accept(OpenDocument snapshot, CompileResponse result);
  }
}
