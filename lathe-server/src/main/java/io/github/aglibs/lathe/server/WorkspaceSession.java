package io.github.aglibs.lathe.server;

import static java.util.logging.Level.SEVERE;

import io.github.aglibs.lathe.core.CollectionUtil;
import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.ExceptionUtil;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.PortUtil;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.launch.JdwpOptions;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.RunKind;
import io.github.aglibs.lathe.core.schema.SourceStamp;
import io.github.aglibs.lathe.core.schema.TestFilterData;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import io.github.aglibs.lathe.core.typeindex.ClassFileTypeScanner;
import io.github.aglibs.lathe.core.typeindex.ClassSourceFile;
import io.github.aglibs.lathe.core.typeindex.SourceTypeScanner;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.analysis.BatchWrite;
import io.github.aglibs.lathe.server.analysis.CallHierarchyItemData;
import io.github.aglibs.lathe.server.analysis.CallHierarchyItemDataCodec;
import io.github.aglibs.lathe.server.analysis.CodeActionRequest;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.DiagnosticPayload;
import io.github.aglibs.lathe.server.analysis.LearnedStaticImports;
import io.github.aglibs.lathe.server.analysis.MissingImportsResult;
import io.github.aglibs.lathe.server.analysis.ReferenceMatch;
import io.github.aglibs.lathe.server.analysis.ReferenceTarget;
import io.github.aglibs.lathe.server.analysis.RenameProvider;
import io.github.aglibs.lathe.server.analysis.SemanticToken;
import io.github.aglibs.lathe.server.analysis.SourceFeatureRequest;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import io.github.aglibs.lathe.server.analysis.TokenScanner;
import io.github.aglibs.lathe.server.analysis.TransientSource;
import io.github.aglibs.lathe.server.analysis.TypeHierarchyExplorerResult;
import io.github.aglibs.lathe.server.analysis.TypeHierarchyItemDataCodec;
import io.github.aglibs.lathe.server.analysis.TypeSourceLocator;
import io.github.aglibs.lathe.server.analysis.WorkspaceSymbolResolver;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.server.analysis.completion.CompletionOutcome;
import io.github.aglibs.lathe.server.debug.DapHost;
import io.github.aglibs.lathe.server.debug.DebugStartResult;
import io.github.aglibs.lathe.server.debug.LatheProviderContext;
import io.github.aglibs.lathe.server.engine.LatheModuleSummary;
import io.github.aglibs.lathe.server.engine.LatheNotes;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import io.github.aglibs.lathe.server.module.CompileRequest;
import io.github.aglibs.lathe.server.module.CompileResponse;
import io.github.aglibs.lathe.server.module.ModuleNameDiscovery;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.lathe.server.module.WorkspaceModuleGraph;
import io.github.aglibs.lathe.server.module.WorkspaceModuleRegistry;
import io.github.aglibs.lathe.server.run.CompletenessGate;
import io.github.aglibs.lathe.server.run.CompletenessResult;
import io.github.aglibs.lathe.server.run.LaunchOutcome;
import io.github.aglibs.lathe.server.run.LaunchReader;
import io.github.aglibs.lathe.server.run.LaunchSession;
import io.github.aglibs.lathe.server.run.Launcher;
import io.github.aglibs.lathe.server.run.MainClassScope;
import io.github.aglibs.lathe.server.run.ResolvedLaunch;
import io.github.aglibs.lathe.server.run.RunConfigInfo;
import io.github.aglibs.lathe.server.run.RunConfigReader;
import io.github.aglibs.lathe.server.run.RunConfigWriter;
import io.github.aglibs.lathe.server.run.RunItem;
import io.github.aglibs.lathe.server.run.RunOverlay;
import io.github.aglibs.lathe.server.run.RunOverlaySet;
import io.github.aglibs.lathe.server.run.RunTarget;
import io.github.aglibs.lathe.server.run.TestEventParams;
import io.github.aglibs.lathe.server.run.TestFinishedParams;
import io.github.aglibs.lathe.server.run.TestOutputParams;
import io.github.aglibs.lathe.server.run.TestResult;
import io.github.aglibs.lathe.server.run.TranscriptLine;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.services.LanguageClient;

/** Not thread-safe. All methods must be called from the {@link ServerEventLoop} thread. */
final class WorkspaceSession {

  private static final Logger LOG = Logger.getLogger(WorkspaceSession.class.getName());

  // Small: attributing disk candidates in one javac task amortizes its fixed per-invocation cost,
  // while a small batch bounds peak memory, cancellation latency, and analyze()-crash blast radius.
  private static final int REFERENCE_BATCH_SIZE = 8;

  // Upper bound on transitive subtypes returned by the type-hierarchy explorer, so a near-Object or
  // huge marker-interface anchor cannot flood the picker or the source-locating pass. Beyond it the
  // result is flagged truncated.
  private static final int TYPE_HIERARCHY_NODE_CAP = 2000;
  private static final long DEFAULT_SCAN_INTERVAL_MS = 2_000L;
  private static final long JDWP_READY_TIMEOUT_MS = 15_000;

  // Title of the $/progress task reported while the workspace loads/reloads. The neotest adapter
  // matches on it to gate discovery on readiness, so it is a cross-process contract: keep it in
  // sync with the mirror in lua/lathe/neotest.lua. Also user-visible via vim.lsp.status().
  static final String WORKSPACE_PROGRESS_TITLE = "Lathe: indexing workspace";

  private final LanguageClient client;
  private final ProgressReporter progressReporter;
  private final ServerEventLoop worker;
  private final long debounceMs;
  private Path workspaceRoot;
  private WorkspaceManifest manifest = WorkspaceManifest.empty();
  private WorkspaceModuleRegistry workspace = WorkspaceModuleRegistry.empty();
  private WorkspaceModuleGraph moduleGraph = WorkspaceModuleGraph.build(List.of());
  private ReferenceCandidateIndex candidateIndex = ReferenceCandidateIndex.build(List.of());
  private WorkspaceTypeIndex typeIndex = WorkspaceTypeIndex.empty();
  private final Map<ModuleSourceConfig, List<TypeIndexEntry>> reactorShards = new LinkedHashMap<>();
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
  private WorkspaceWatcher watcher;
  private boolean buildFilesNotificationPending;
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
  private long passGeneration;
  // What the passes owe once none is left to run: the module dirs whose open files to re-analyze,
  // and a reactor type index rebuild. Deferred while another pass is requested, so a `:wa` burst
  // analyzes each open file once and rebuilds the index (~200ms on a large reactor) once.
  private final Set<Path> refreshScope = new HashSet<>();
  private boolean reactorIndexStale;
  private long reactorIndexBuilds;
  // Done once the last rebuild is applied (or superseded): MCP answers wait for it, so a type the
  // pass just wrote is already searchable.
  private CompletableFuture<Void> reactorIndexRebuilt = CompletableFuture.completedFuture(null);
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
  private long reportCount;
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
  private final AnalysisLru analysisLru = new AnalysisLru();
  private final DiagnosticPublisher publisher;

  // In-flight replays keyed by run token, so a cancel can reach the right LaunchSession. A plain
  // map, not a concurrent one: it is mutated only on the worker thread. The run and exit threads
  // marshal put/remove via worker.execute (see runTestFuture) rather than touching it directly,
  // keeping the single-threaded discipline of every other field here.
  private final Map<String, LaunchSession> activeRuns = new HashMap<>();
  private final Map<String, DapHost> activeDebugHosts = new HashMap<>();
  // Test discoveries per module, valid while the module's test-classes stamp is unchanged, and the
  // last one queued: discovery JVMs run one at a time. Both touched on the worker thread only.
  private final Map<String, CachedDiscovery> discoveries = new HashMap<>();
  private CompletableFuture<TestDiscovery> lastDiscovery =
      CompletableFuture.completedFuture(new TestDiscovery("", List.of(), List.of()));
  private final AtomicBoolean formatFailureReported = new AtomicBoolean();

  WorkspaceSession(
      final LanguageClient client,
      final ProgressReporter progressReporter,
      final ServerEventLoop worker,
      final long debounceMs) {
    this.client = client;
    this.progressReporter = progressReporter;
    this.worker = worker;
    this.debounceMs = debounceMs;
    this.publisher = new DiagnosticPublisher(client, docs);
  }

  void initialize(final Path root) {
    this.workspaceRoot = root;
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
    manifest = WorkspaceManifest.load(root);
    workspace = WorkspaceModuleRegistry.scan(root, manifest);
    moduleGraph = WorkspaceModuleGraph.build(workspace.allConfigs());
    candidateIndex = ReferenceCandidateIndex.build(workspace.allConfigs());
    scanReactorShards();
    typeIndex =
        WorkspaceTypeIndex.build(manifest.typeIndexShardPaths(), reactorShards.values())
            .withUsageCounts(reactorUsageCounts);
    watcher = new WorkspaceWatcher(root);
    watcher.updateBuildFiles(manifest.buildFiles(), manifest.buildFileFingerprints());
    final long scanIntervalMs = Long.getLong(LatheFlags.SCAN_INTERVAL_MS, DEFAULT_SCAN_INTERVAL_MS);
    worker.scheduleAtFixedRate(
        scanIntervalMs > 0 ? scanIntervalMs : DEFAULT_SCAN_INTERVAL_MS, this::checkForChanges);
    if (configured) {
      showWorkspaceStatus("Lathe: workspace ready.");
    } else {
      client.showMessage(new MessageParams(MessageType.Warning, LatheLayout.SETUP_REMEDIATION));
    }

    // Startup reconciliation for the cold-start delta (changes while Lathe was down). Eager: the
    // delta is settled on disk, so compile it now rather than waiting out the two-tick guard.
    requestPass(true);
  }

  void close() {
    workspace.close();
  }

  CompletableFuture<LaunchOutcome> runTestFuture(
      final String moduleRel, final List<TestSelection> selections, final String token) {
    return startTestRun(moduleRel, selections, token, baselineOverlay(moduleRel, RunKind.TEST));
  }

  private CompletableFuture<LaunchOutcome> startTestRun(
      final String moduleRel,
      final List<TestSelection> selections,
      final String token,
      final RunItem overlay) {
    final List<Path> runnerClasspath = manifest.runnerClasspath();
    if (runnerClasspath.isEmpty()) {
      LOG.warning(
          () ->
              "[launch] %s %s blocked no lathe-test-runner jar recorded"
                  .formatted(moduleRel, selectionLabel(selections)));
      return CompletableFuture.completedFuture(
          LaunchOutcome.blocked(List.of("no lathe-test-runner jar recorded — run a build first")));
    }

    final Consumer<TranscriptLine> onLine = streamConsumer(token);
    final Consumer<TestResult> onResult = resultConsumer(token);
    final Path root = workspaceRoot;
    final ReactorRewrite reactorRewrite = manifest.reactorRewrite();
    final var t = Stopwatch.start();
    final var result = new CompletableFuture<LaunchOutcome>();
    // Register/deregister the run by token so a cancel can reach it. Marshaled onto the worker so
    // activeRuns keeps the single-threaded discipline of every other field. Removal covers the
    // blocked/error paths too (removing an unregistered token is a no-op).
    result.whenComplete((outcome, error) -> worker.execute(() -> activeRuns.remove(token)));
    final Consumer<LaunchSession> onStart =
        session -> worker.execute(() -> activeRuns.put(token, session));

    final var thread =
        new Thread(
            () ->
                launchTest(
                    root,
                    reactorRewrite,
                    runnerClasspath,
                    moduleRel,
                    selections,
                    overlay,
                    onLine,
                    onResult,
                    onStart,
                    t,
                    result),
            "lathe-launch-" + moduleRel);
    thread.setDaemon(true);
    thread.start();
    return result;
  }

  void cancelRun(final String token) {
    final LaunchSession session = activeRuns.get(token);
    if (session == null) {
      LOG.fine(() -> "[cancel] %s no active run".formatted(token));
      return;
    }

    LOG.info(() -> "[cancel] %s pid=%d".formatted(token, session.pid()));
    session.cancel();
  }

  /**
   * Launches the selected test suspended under a JDWP agent and opens an in-process DAP host wired
   * to the module's source model, returning the ports the editor's debug client attaches to. Runs
   * on the session worker; the launched JVM is tracked by {@code token} so {@link #cancelRun} kills
   * it, and the DAP host is closed when the debuggee exits.
   */
  DebugStartResult debugTest(
      final String moduleRel, final List<TestSelection> selections, final String token) {
    return startDebugTest(moduleRel, selections, token, baselineOverlay(moduleRel, RunKind.TEST));
  }

  private DebugStartResult startDebugTest(
      final String moduleRel,
      final List<TestSelection> selections,
      final String token,
      final RunItem overlay) {
    final List<Path> runnerClasspath = manifest.runnerClasspath();
    if (runnerClasspath.isEmpty()) {
      throw new IllegalStateException("no lathe-test-runner jar recorded — run a build first");
    }

    try {
      final TestLaunchData template =
          new LaunchReader(workspaceRoot)
              .test(moduleRel)
              .orElseThrow(() -> new IllegalStateException(noCapturedLaunch(moduleRel)));
      assertLaunchComplete(
          CompletenessGate.verify(template, workspaceRoot, manifest.reactorRewrite()));

      final int jdwpPort = PortUtil.free();
      final var jdwp = new JdwpOptions(jdwpPort);
      final var jdwpReady = new CompletableFuture<Void>();
      final Path resultsSink = Files.createTempFile("lathe-results-", ".ndjson");
      final ResolvedLaunch resolved =
          RunOverlay.applyToTest(
              template,
              workspaceRoot,
              manifest.reactorRewrite(),
              runnerClasspath,
              withTestFilter(workspaceRoot, moduleRel, selections),
              resultsSink,
              overlay,
              jdwp);
      final var session =
          Launcher.launch(
              resolved.argv(),
              overlay.configLabel(),
              resultsSink,
              jdwpReadyConsumer(jdwp, jdwpReady, streamConsumer(token)),
              resultConsumer(token),
              resolved.env(),
              resolved.cwd());
      return attachDebugHost(
          moduleRel, token, session, jdwpPort, jdwpReady, selectionLabel(selections));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Launches the module's {@code mainClass} suspended under a JDWP agent and opens a DAP host — the
   * main-class twin of {@link #debugTest}. A main run has no JUnit result stream, so it spawns with
   * a null sink and a no-op result consumer.
   */
  DebugStartResult debugMain(final String moduleRel, final String mainClass, final String token) {
    return startDebugMain(moduleRel, mainClass, token, baselineOverlay(moduleRel, RunKind.MAIN));
  }

  private DebugStartResult startDebugMain(
      final String moduleRel, final String mainClass, final String token, final RunItem overlay) {
    try {
      final int jdwpPort = PortUtil.free();
      final var jdwp = new JdwpOptions(jdwpPort);
      final MainLaunchPlan plan =
          resolveMainLaunch(
              workspaceRoot, manifest.reactorRewrite(), moduleRel, mainClass, overlay, jdwp);
      if (plan.blocked()) {
        throw new IllegalStateException("debug launch incomplete: " + plan.blockedReasons());
      }

      final var jdwpReady = new CompletableFuture<Void>();
      final ResolvedLaunch resolved = plan.launch();
      final var session =
          Launcher.launch(
              resolved.argv(),
              overlay.configLabel(),
              null,
              jdwpReadyConsumer(jdwp, jdwpReady, streamConsumer(token)),
              ignored -> {},
              resolved.env(),
              resolved.cwd());
      return attachDebugHost(moduleRel, token, session, jdwpPort, jdwpReady, mainClass);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * A resolved main launch, or the reasons it is blocked — returned so run and debug report it
   * their own way.
   */
  private record MainLaunchPlan(ResolvedLaunch launch, List<String> blockedReasons) {
    static MainLaunchPlan ready(final ResolvedLaunch launch) {
      return new MainLaunchPlan(launch, List.of());
    }

    static MainLaunchPlan blocked(final List<String> reasons) {
      return new MainLaunchPlan(null, List.copyOf(reasons));
    }

    boolean blocked() {
      return launch == null;
    }
  }

  /**
   * Resolves the launch for a {@code main} run/debug, choosing the template by the class's source
   * scope: a {@code main} in the module's test sources runs against the captured test template (its
   * test-classes patched into the module, the full test-scope graph), with the user's class as the
   * entry point instead of the JUnit runner; a main-scope class uses the derived main template.
   * Blocked (missing template / incomplete capture) is returned, not thrown, so each caller reports
   * it its own way.
   */
  private static MainLaunchPlan resolveMainLaunch(
      final Path workspaceRoot,
      final ReactorRewrite reactorRewrite,
      final String moduleRel,
      final String mainClass,
      final RunItem overlay,
      final JdwpOptions jdwp)
      throws IOException {
    if (MainClassScope.isTestScope(workspaceRoot, moduleRel, mainClass)) {
      final Optional<TestLaunchData> template = new LaunchReader(workspaceRoot).test(moduleRel);
      if (template.isEmpty()) {
        return MainLaunchPlan.blocked(List.of(noCapturedLaunch(moduleRel)));
      }

      final CompletenessResult gate =
          CompletenessGate.verify(template.get(), workspaceRoot, reactorRewrite);
      if (!gate.complete()) {
        return MainLaunchPlan.blocked(gate.reasons());
      }

      return MainLaunchPlan.ready(
          RunOverlay.applyToTestMain(
              template.get(), workspaceRoot, reactorRewrite, mainClass, overlay, jdwp));
    }

    final Optional<MainLaunchData> template = new LaunchReader(workspaceRoot).main(moduleRel);
    if (template.isEmpty()) {
      return MainLaunchPlan.blocked(List.of("no derived main-launch.json for " + moduleRel));
    }

    final CompletenessResult gate =
        CompletenessGate.verify(template.get(), workspaceRoot, reactorRewrite);
    if (!gate.complete()) {
      return MainLaunchPlan.blocked(gate.reasons());
    }

    return MainLaunchPlan.ready(
        RunOverlay.applyToMain(
            template.get(), workspaceRoot, reactorRewrite, mainClass, overlay, jdwp));
  }

  /**
   * Shared debug tail: track the launched JVM under {@code token}, wait for its JDWP agent to start
   * listening, open the DAP host wired to the workspace's source routing, and return the ports. The
   * host is closed when the debuggee exits.
   */
  private DebugStartResult attachDebugHost(
      final String moduleRel,
      final String token,
      final LaunchSession session,
      final int jdwpPort,
      final CompletableFuture<Void> jdwpReady,
      final String label)
      throws IOException {
    activeRuns.put(token, session);
    session
        .onExit()
        .whenComplete(
            (outcome, error) ->
                worker.execute(
                    () -> {
                      publishTestFinished(token, outcome, error);
                      endDebugSession(token);
                    }));

    // Launcher spawns the JVM and returns before its -agentlib:jdwp socket is listening. Gate on
    // the
    // agent's own "Listening for transport ... at address:" banner (surfaced through the drain and
    // completed onto jdwpReady by jdwpReadyConsumer) rather than probing the port with a throwaway
    // TCP connect, which the agent reports as a failed debugger handshake. The DapHost's java-debug
    // connection is then the only connection the agent ever sees, so the editor's attach (once, no
    // retry) never races a probe. suspend=y guarantees the banner prints shortly, then parks.
    try {
      jdwpReady.get(JDWP_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted while waiting for the jdwp agent on port %d".formatted(jdwpPort), e);
    } catch (final ExecutionException | TimeoutException e) {
      throw new IllegalStateException(
          "jdwp agent did not start listening on port %d within %dms"
              .formatted(jdwpPort, JDWP_READY_TIMEOUT_MS),
          e);
    }

    // The debuggee is a replay Lathe owns, so a client disconnect terminates it (launch semantics)
    // rather than leaving it running as a plain attach would -- otherwise a long-running debuggee
    // would be orphaned when the user stops debugging.
    // The whole reactor plus the extracted dependency/JDK sources: a frame in any of those must
    // resolve to its source, else stepping into (or through) library/framework code stops on a
    // source-less frame the editor cannot show. JDK sources are laid out per module
    // (<jdk>/java.base/java/lang/…), so the module dirs -- not the JDK root -- are the roots a
    // by-name lookup can search.
    final List<Path> debugSourceRoots =
        Stream.of(
                workspace.allSourceRoots(),
                manifest.depSourceDirs(),
                manifest.jdkModuleSourceDirs())
            .flatMap(List::stream)
            .toList();
    final var host =
        DapHost.start(
            new LatheProviderContext(this::debugWorkerFor, debugSourceRoots, typeIndex),
            () -> worker.execute(() -> cancelRun(token)));
    activeDebugHosts.put(token, host);
    LOG.info(
        () -> "[debug] %s %s dap=%d jdwp=%d".formatted(moduleRel, label, host.port(), jdwpPort));
    return new DebugStartResult(host.port(), jdwpPort);
  }

  // Called on the debug adapter's threads: reads the workspace on the event loop like every other
  // access, and always the current registry, since a sync replaces it mid-session. Cancelled (so
  // the debugger request fails) once the session has closed.
  private Optional<CompilationWorker> debugWorkerFor(final Path file) {
    return worker.submit(() -> workspace.moduleSourceFor(file).map(workspace::workerFor)).join();
  }

  private static void assertLaunchComplete(final CompletenessResult gate) {
    if (!gate.complete()) {
      throw new IllegalStateException("debug launch incomplete: " + gate.reasons());
    }
  }

  /**
   * Publishes the debug session's final {@link LaunchOutcome} to the client over its run token —
   * the debug twin of the {@code lathe.run.test} command response. The debug launch returns its DAP
   * ports immediately, long before the debuggee exits, so this session-end notification is the only
   * way the client learns the run's aggregate result and can complete its results reconciliation
   * (gutters, summary, pass/fail). A blank token (no live surface listening) is a no-op; a null
   * outcome (the JVM died before producing one) is reported as blocked so the client never waits
   * forever. Fires for both {@code debugTest} and {@code debugMain}; a main debug carries no {@code
   * testResults}, which the client ignores for a token it never registered.
   */
  private void publishTestFinished(
      final String token, final LaunchOutcome outcome, final Throwable error) {
    if (token.isBlank()) {
      return;
    }

    final LaunchOutcome finished = finishedOutcome(outcome, error);
    LOG.fine(() -> "[debug] %s finished exit=%d".formatted(token, finished.exitCode()));
    ((LatheLanguageClient) client).testFinished(new TestFinishedParams(token, finished));
  }

  /**
   * The outcome to publish at debug session end: the real {@link LaunchOutcome} when {@code
   * session.onExit()} produced one, or a blocked outcome naming the failure when the JVM died
   * before one was read. Never null, so the client's results wait always completes. Package-private
   * for direct unit coverage without launching a suspended JVM.
   */
  static LaunchOutcome finishedOutcome(final LaunchOutcome outcome, final Throwable error) {
    if (outcome != null) {
      return outcome;
    }

    return LaunchOutcome.blocked(
        List.of("debug session ended without an outcome: %s".formatted(error)));
  }

  private void endDebugSession(final String token) {
    activeRuns.remove(token);
    final DapHost host = activeDebugHosts.remove(token);
    if (host == null) {
      return;
    }

    try {
      host.close();
    } catch (final IOException e) {
      LOG.log(Level.FINE, e, () -> "[debug] %s dap host close failed".formatted(token));
    }
  }

  private List<ModuleSourceConfig> configsFor(final String moduleRel) {
    final List<ModuleSourceConfig> configs =
        workspace.allConfigs().stream()
            .filter(config -> moduleRel(config).equals(moduleRel))
            .toList();
    if (configs.isEmpty()) {
      throw new IllegalStateException("no module for " + moduleRel);
    }

    return configs;
  }

  CreateTypeResult createType(final CreateTypeArgs args) {
    // module-info.java always belongs to the main source root, whatever scope the client sent.
    final var scope = args.type() == TypeKind.MODULE_INFO ? SourceScope.MAIN : args.kind();
    final var sourceRoot = sourceRootFor(args.moduleRel(), scope);
    LOG.info(
        () ->
            "[createType] %s %s.%s kind=%s type=%s"
                .formatted(args.moduleRel(), args.pkg(), args.name(), args.kind(), args.type()));
    return renderNewType(sourceRoot, args.pkg(), args.type(), args.name());
  }

  private Path sourceRootFor(final String moduleRel, final SourceScope scope) {
    return configsFor(moduleRel).stream()
        .filter(config -> scope.sourceTree.equals(config.sourceTree()))
        .flatMap(config -> primarySourceRoot(config).stream())
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "no %s source root for module '%s'".formatted(scope, moduleRel)));
  }

  // The real (non-generated) source directory a new file lands in.
  private static Optional<Path> primarySourceRoot(final ModuleSourceConfig config) {
    return config.sourceRoots().stream().findFirst();
  }

  List<String> modules() {
    return modules(workspace.allConfigs(), workspaceRoot);
  }

  List<PackageEntry> packages(final String moduleRel) {
    return packages(workspace.allConfigs(), workspaceRoot, moduleRel);
  }

  ContextInfo resolveContext(final String uri) {
    return resolveContext(workspace.allConfigs(), workspaceRoot, LatheUri.toPath(uri));
  }

  DirRun dirRun(final String uri) {
    return dirRun(workspace.allConfigs(), workspaceRoot, LatheUri.toPath(uri));
  }

  // Static cores below take (configs, workspaceRoot) so they are covered without a loaded
  // workspace.

  static List<String> modules(final List<ModuleSourceConfig> configs, final Path workspaceRoot) {
    return configs.stream()
        .map(config -> moduleRel(workspaceRoot, config.moduleDir()))
        .distinct()
        .sorted()
        .toList();
  }

  static List<PackageEntry> packages(
      final List<ModuleSourceConfig> configs, final Path workspaceRoot, final String moduleRel) {
    return configs.stream()
        .filter(config -> moduleRel(workspaceRoot, config.moduleDir()).equals(moduleRel))
        .flatMap(WorkspaceSession::packageEntries)
        .distinct()
        .sorted(Comparator.comparing(PackageEntry::pkg).thenComparing(PackageEntry::scope))
        .toList();
  }

  static ContextInfo resolveContext(
      final List<ModuleSourceConfig> configs, final Path workspaceRoot, final Path path) {
    return configs.stream()
        .flatMap(config -> contextIn(workspaceRoot, config, path).stream())
        .findFirst()
        .orElse(null);
  }

  // A package or module run executes the classes the build's own test run would (the module's
  // test-filter.json); a run naming classes or methods runs exactly those.
  private static List<TestSelection> withTestFilter(
      final Path workspaceRoot, final String moduleRel, final List<TestSelection> selections)
      throws IOException {
    return new LaunchReader(workspaceRoot)
        .testFilter(moduleRel)
        .map(filter -> withClassNameFilter(selections, filter))
        .orElse(selections);
  }

  static List<TestSelection> withClassNameFilter(
      final List<TestSelection> selections, final TestFilterData filter) {
    final boolean wide =
        !selections.isEmpty()
            && selections.stream()
                .allMatch(
                    selection ->
                        selection.kind() == TestSelectionKind.PACKAGE
                            || selection.kind() == TestSelectionKind.MODULE);
    if (!wide) {
      return selections;
    }

    return Stream.of(
            selections.stream(),
            filter.classNameIncludes().stream()
                .map(regex -> new TestSelection(TestSelectionKind.CLASS_NAME_INCLUDE, regex)),
            filter.classNameExcludes().stream()
                .map(regex -> new TestSelection(TestSelectionKind.CLASS_NAME_EXCLUDE, regex)))
        .flatMap(stream -> stream)
        .toList();
  }

  // Test selectors to run for a directory, from the real reactor layout, not the path string.
  // A dir inside a module's test tree runs just that package (selectPackage recurses); the
  // source root, or anything above it (module root, src/), runs every test package in one
  // launch. A dir enclosing more than one module (the reactor root) resolves to nothing.
  static DirRun dirRun(
      final List<ModuleSourceConfig> configs, final Path workspaceRoot, final Path dirPath) {
    final List<ModuleSourceConfig> testConfigs = testConfigs(configs);

    for (final ModuleSourceConfig config : testConfigs) {
      final Optional<Path> root = primarySourceRoot(config);
      if (root.isEmpty() || !dirPath.startsWith(root.get())) {
        continue;
      }

      final String pkg = packageOf(root.get(), dirPath);
      final String moduleRel = moduleRel(workspaceRoot, config.moduleDir());
      if (pkg.isEmpty()) {
        return moduleRun(configs, workspaceRoot, moduleRel);
      }

      return new DirRun(
          moduleRel, List.of(new DirRun.Selector(TestSelectionKind.PACKAGE.name(), pkg)));
    }

    // At or above a module's test tree (module root, src/): its real test root is a descendant
    // of the dir -- deterministic, no path parsing. Only when exactly one module lives under it;
    // several is a multi-module directory, which is not run.
    final List<ModuleSourceConfig> enclosed =
        testConfigs.stream().filter(config -> testRootUnder(config, dirPath)).toList();
    if (enclosed.size() == 1) {
      return moduleRun(
          configs, workspaceRoot, moduleRel(workspaceRoot, enclosed.get(0).moduleDir()));
    }

    return new DirRun("", List.of());
  }

  private static boolean testRootUnder(final ModuleSourceConfig config, final Path dirPath) {
    return primarySourceRoot(config).filter(root -> root.startsWith(dirPath)).isPresent();
  }

  private static List<ModuleSourceConfig> testConfigs(final List<ModuleSourceConfig> configs) {
    return configs.stream()
        .filter(config -> SourceScope.ofSourceTree(config.sourceTree()) == SourceScope.TEST)
        .toList();
  }

  private record CachedDiscovery(FileTime stamp, CompletableFuture<TestDiscovery> result) {

    CachedDiscovery {
      ValidCheck.check().notNull(result, "result").validate();
    }
  }

  // A module's discovery on disk with the test-classes stamp it was found for, so a new session
  // reuses it instead of launching a JVM per module again.
  private record PersistedDiscovery(long stampMillis, TestDiscovery discovery) {

    PersistedDiscovery {
      ValidCheck.check().notNull(discovery, "discovery").validate();
    }
  }

  List<String> testModules() {
    return testConfigs(workspace.allConfigs()).stream()
        .map(this::moduleRel)
        .distinct()
        .sorted()
        .toList();
  }

  // The tests a run of the module would execute, found by a discover-mode launch that runs none of
  // them. Answered from the cache until the module's test classes change (sync and save compiles
  // rewrite its test-classes stamp); a client asking for many modules gets a queue, not a JVM each.
  CompletableFuture<TestDiscovery> discoverTests(final String moduleRel) {
    final FileTime stamp = testClassesStamp(moduleRel);
    final CachedDiscovery cached = discoveries.get(moduleRel);
    if (cached != null && Objects.equals(cached.stamp(), stamp)) {
      return cached.result();
    }

    final Path persisted = moduleLatheDir(moduleRel).resolve(LatheLayout.TEST_DISCOVERY_FILE);
    final Optional<TestDiscovery> stored = readDiscovery(persisted, stamp);
    if (stored.isPresent()) {
      final var result = CompletableFuture.completedFuture(stored.get());
      discoveries.put(moduleRel, new CachedDiscovery(stamp, result));
      return result;
    }

    final CompletableFuture<TestDiscovery> result =
        lastDiscovery
            .handle((previous, error) -> moduleRel)
            .thenComposeAsync(this::launchDiscovery, worker::execute)
            .thenApply(discovery -> writeDiscovery(persisted, stamp, discovery));
    lastDiscovery = result;
    discoveries.put(moduleRel, new CachedDiscovery(stamp, result));
    return result;
  }

  private CompletableFuture<TestDiscovery> launchDiscovery(final String moduleRel) {
    final List<TestSelection> selections =
        moduleRun(workspace.allConfigs(), workspaceRoot, moduleRel).selections().stream()
            .map(
                selector ->
                    new TestSelection(
                        TestSelectionKind.valueOf(selector.selectorKind()),
                        selector.selectorValue()))
            .toList();
    if (selections.isEmpty()) {
      return CompletableFuture.completedFuture(new TestDiscovery(moduleRel, List.of(), List.of()));
    }

    final RunItem overlay =
        baselineOverlay(moduleRel, RunKind.TEST)
            .withJvmArg("-D%s=true".formatted(LatheFlags.DISCOVER));
    return startTestRun(moduleRel, selections, "", overlay)
        .thenApplyAsync(outcome -> discovery(moduleRel, outcome), worker::execute);
  }

  private TestDiscovery discovery(final String moduleRel, final LaunchOutcome outcome) {
    final Map<String, String> paths =
        testSources(moduleRel, outcome.testResults().stream().map(TestResult::className).toList())
            .stream()
            .collect(Collectors.toMap(TestSource::className, TestSource::path));
    return new TestDiscovery(
        moduleRel, outcome.blockedReasons(), discoveredTests(outcome.testResults(), paths));
  }

  // One entry per position id: a @Nested class inherited by two outer classes is two JUnit tests
  // but one position. `paths` maps a class name to its source file, as testSources resolves it.
  static List<DiscoveredTest> discoveredTests(
      final List<TestResult> results, final Map<String, String> paths) {
    return results.stream()
        .collect(
            Collectors.toMap(
                TestResult::positionId,
                result ->
                    new DiscoveredTest(
                        result.positionId(),
                        result.methodName(),
                        sourceUri(paths.getOrDefault(result.className(), ""))),
                (first, again) -> first,
                LinkedHashMap::new))
        .values()
        .stream()
        .toList();
  }

  private Path moduleLatheDir(final String moduleRel) {
    return workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleRel);
  }

  // The stored discovery, when it was found for the module's current test classes.
  static Optional<TestDiscovery> readDiscovery(final Path file, final FileTime stamp) {
    if (stamp == null || !Files.exists(file)) {
      return Optional.empty();
    }

    final PersistedDiscovery persisted =
        IOUtil.unchecked(() -> Json.read(file, PersistedDiscovery.class));
    return persisted.stampMillis() == stamp.toMillis()
        ? Optional.of(persisted.discovery())
        : Optional.empty();
  }

  // Stores a complete discovery; a blocked one (no capture yet) is not worth keeping.
  static TestDiscovery writeDiscovery(
      final Path file, final FileTime stamp, final TestDiscovery discovery) {
    if (stamp != null && discovery.blockedReasons().isEmpty()) {
      IOUtil.unchecked(
          () ->
              FileUtil.writeIfChanged(
                  file.getParent(),
                  file,
                  Json.toJson(new PersistedDiscovery(stamp.toMillis(), discovery)),
                  false));
    }

    return discovery;
  }

  private static String sourceUri(final String path) {
    return path.isEmpty() ? "" : LatheUri.fromPath(Path.of(path));
  }

  private FileTime testClassesStamp(final String moduleRel) {
    final Path stamps =
        moduleLatheDir(moduleRel)
            .resolve(LatheLayout.compiledStampsFileName(LatheLayout.TEST_CLASSES_DIR));
    return Files.exists(stamps) ? IOUtil.unchecked(() -> Files.getLastModifiedTime(stamps)) : null;
  }

  List<TestSource> testSources(final String moduleRel, final List<String> classNames) {
    return testSources(workspace.allConfigs(), workspaceRoot, moduleRel, classNames);
  }

  List<ResourceEntry> resources() {
    return resources(manifest.resourceDirModules(), manifest.dependencyJarsToGav());
  }

  // Every resource the finder lists: reactor resource roots (real editable files) and each dep
  // jar's
  // entries (read from its central directory -- no decompression, no extraction). `.class` is
  // excluded; origin comes from the owning module / GAV.
  static List<ResourceEntry> resources(
      final Map<Path, String> resourceDirs, final Map<Path, String> jarToGav) {
    return Stream.concat(
            resourceDirs.entrySet().stream()
                .flatMap(dir -> resourcesUnder(dir.getKey(), dir.getValue()).stream()),
            jarToGav.entrySet().stream()
                .flatMap(dep -> jarResources(dep.getKey(), dep.getValue()).stream()))
        .toList();
  }

  // Extracts one dependency jar entry to disk (read-only) and returns its path -- the only bytes
  // written, on demand. Reuses the dep's sources cache dir when present, else a temp dir. Reactor
  // resources need no extraction (they carry their real path).
  String resourceOpen(final String jar, final String entry) {
    final var jarPath = Path.of(jar);
    final Path dest =
        manifest
            .dependencySourceDir(jarPath)
            .map(dir -> dir.resolve(entry))
            .orElseGet(tempDest(entry));
    try {
      FileUtil.extractEntry(jarPath, entry, dest);
    } catch (final IOException e) {
      throw new UncheckedIOException("extract %s from %s".formatted(entry, jar), e);
    }

    return dest.toString();
  }

  private static List<ResourceEntry> resourcesUnder(final Path dir, final String module) {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }

    try (final var walk = Files.walk(dir)) {
      return walk.filter(Files::isRegularFile)
          .filter(WorkspaceSession::isResource)
          .map(
              file ->
                  ResourceEntry.reactor(
                      FileUtil.slashed(dir.relativize(file)), module, file.toString()))
          .toList();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[resources] walk %s failed".formatted(dir));
      return List.of();
    }
  }

  private static List<ResourceEntry> jarResources(final Path jar, final String gav) {
    try {
      return FileUtil.listEntries(jar, WorkspaceSession::isResource).stream()
          .map(entry -> ResourceEntry.dependency(gav, jar.toString(), entry))
          .toList();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[resources] list %s failed".formatted(jar));
      return List.of();
    }
  }

  // Excluding compiled classes is the whole filter -- a denylist keeps unusual resource extensions.
  private static boolean isResource(final String name) {
    return !name.endsWith(".class");
  }

  private static boolean isResource(final Path file) {
    return isResource(file.getFileName().toString());
  }

  private static Supplier<Path> tempDest(final String entry) {
    return () -> {
      try {
        return Files.createTempDirectory("lathe-resource-").resolve(Path.of(entry).getFileName());
      } catch (final IOException e) {
        throw new UncheckedIOException("temp dir for %s".formatted(entry), e);
      }
    };
  }

  // Source file for each test class, resolved from the module's real test source roots by path math
  // (top-level class -> <root>/<pkg>/<Simple>.java, existence-checked). No attribution, so it works
  // for classes whose files were never opened. Unresolved classes get an empty path.
  static List<TestSource> testSources(
      final List<ModuleSourceConfig> configs,
      final Path workspaceRoot,
      final String moduleRel,
      final List<String> classNames) {
    final List<Path> roots =
        testConfigs(configs).stream()
            .filter(config -> moduleRel(workspaceRoot, config.moduleDir()).equals(moduleRel))
            .flatMap(config -> config.sourceRoots().stream())
            .toList();
    return classNames.stream()
        .distinct()
        .map(name -> new TestSource(name, sourcePath(roots, name)))
        .toList();
  }

  private static String sourcePath(final List<Path> roots, final String className) {
    final int inner = className.indexOf('$');
    final String topLevel = inner < 0 ? className : className.substring(0, inner);
    final String rel = topLevel.replace('.', '/') + ".java";
    for (final Path root : roots) {
      final Path candidate = root.resolve(rel);
      if (Files.isRegularFile(candidate)) {
        return candidate.toString();
      }
    }
    return "";
  }

  private static DirRun moduleRun(
      final List<ModuleSourceConfig> configs, final Path workspaceRoot, final String moduleRel) {
    final List<DirRun.Selector> selectors =
        packages(configs, workspaceRoot, moduleRel).stream()
            .filter(entry -> entry.scope().equals(SourceScope.TEST.wire))
            .map(PackageEntry::pkg)
            .filter(pkg -> !pkg.isEmpty())
            .distinct()
            .map(pkg -> new DirRun.Selector(TestSelectionKind.PACKAGE.name(), pkg))
            .toList();
    return new DirRun(moduleRel, selectors);
  }

  private static Stream<PackageEntry> packageEntries(final ModuleSourceConfig config) {
    final String scope = scopeWire(config);
    return primarySourceRoot(config).stream()
        .flatMap(root -> packagesUnder(root).stream().map(pkg -> new PackageEntry(pkg, scope)));
  }

  private static String scopeWire(final ModuleSourceConfig config) {
    return SourceScope.ofSourceTree(config.sourceTree()).wire;
  }

  // A package is a directory that holds Java sources (the parent of each `.java`).
  // Intermediate dirs like `com` are not packages and must not be offered.
  private static List<String> packagesUnder(final Path root) {
    if (!Files.isDirectory(root)) {
      return List.of();
    }

    try (final var walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile)
          .filter(FileUtil::isJavaFile)
          .map(source -> packageOf(root, source.getParent()))
          .distinct()
          .sorted()
          .toList();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[packages] scan failed under %s".formatted(root));
      return List.of();
    }
  }

  private static Optional<ContextInfo> contextIn(
      final Path workspaceRoot, final ModuleSourceConfig config, final Path path) {
    return primarySourceRoot(config)
        .filter(path::startsWith)
        .map(
            root ->
                new ContextInfo(
                    moduleRel(workspaceRoot, config.moduleDir()),
                    scopeWire(config),
                    packageOf(root, path.getParent())));
  }

  private static String packageOf(final Path root, final Path dir) {
    return root.relativize(dir).toString().replace(root.getFileSystem().getSeparator(), ".");
  }

  // Pure/static so it is covered without a loaded workspace. The skeleton is left unformatted: an
  // empty type has nothing to format, and running the formatter would only collapse the body and
  // remove the caret line — the user's on-save formatter canonicalises it on first save.
  static CreateTypeResult renderNewType(
      final Path sourceRoot, final String pkg, final TypeKind type, final String name) {
    return switch (type) {
      case CLASS, INTERFACE, ENUM, RECORD, TEST -> renderType(sourceRoot, pkg, type, name);
      case PACKAGE_INFO -> renderPackageInfo(sourceRoot, pkg);
      case MODULE_INFO -> renderModuleInfo(sourceRoot, name);
    };
  }

  private static CreateTypeResult renderType(
      final Path sourceRoot, final String pkg, final TypeKind type, final String name) {
    if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
      throw new IllegalArgumentException("'%s' is not a valid Java type name".formatted(name));
    }

    final var path = sourceRoot.resolve(pkg.replace('.', '/')).resolve(name + ".java");
    return new CreateTypeResult(
        path.toString(), newTypeSource(type, name, pkg), caretFor(type, name, pkg));
  }

  // The default package cannot carry a package-info, so an empty package is rejected.
  private static CreateTypeResult renderPackageInfo(final Path sourceRoot, final String pkg) {
    if (pkg.isEmpty()) {
      throw new IllegalArgumentException("package-info requires a package");
    }

    final var path = sourceRoot.resolve(pkg.replace('.', '/')).resolve("package-info.java");
    final var content = "/**\n * \n */\npackage %s;\n".formatted(pkg);
    return new CreateTypeResult(path.toString(), content, new Position(1, 3));
  }

  // The module name is a qualified name, not a simple type identifier, so it is validated with
  // isName.
  private static CreateTypeResult renderModuleInfo(final Path sourceRoot, final String name) {
    if (!SourceVersion.isName(name)) {
      throw new IllegalArgumentException("'%s' is not a valid module name".formatted(name));
    }

    final var path = sourceRoot.resolve("module-info.java");
    return new CreateTypeResult(
        path.toString(), "module %s {\n\n}\n".formatted(name), new Position(1, 0));
  }

  private static String newTypeSource(final TypeKind type, final String name, final String pkg) {
    final var header = pkg.isEmpty() ? "" : "package %s;\n\n".formatted(pkg);
    return switch (type) {
      case RECORD -> "%spublic record %s() {\n}\n".formatted(header, name);
      case CLASS, INTERFACE, ENUM ->
          "%spublic %s %s {\n\n}\n".formatted(header, type.keyword, name);
      // A JUnit 5 test class: package-private (the Jupiter idiom, matching the codebase), with one
      // empty @Test method for the caret to land in. The import follows the package header.
      case TEST ->
          "%simport org.junit.jupiter.api.Test;\n\nclass %s {\n\n  @Test\n  void name() {\n\n  }\n}\n"
              .formatted(header, name);
      case PACKAGE_INFO, MODULE_INFO ->
          throw new IllegalStateException("special unit handled by renderNewType: " + type);
    };
  }

  // Computed from the template's known shape, not by scanning it: a package header adds two leading
  // lines; a record's caret is inside the () on the declaration line, the others' on the empty body
  // line. Mirrors the newTypeSource layout above.
  private static Position caretFor(final TypeKind type, final String name, final String pkg) {
    final int declLine = pkg.isEmpty() ? 0 : 2;
    if (type == TypeKind.RECORD) {
      return new Position(declLine, "public record %s(".formatted(name).length());
    }

    // The test skeleton's caret sits on the empty @Test method body line: six lines below the
    // import (import, blank, class, blank, @Test, void), which itself is at declLine.
    if (type == TypeKind.TEST) {
      return new Position(declLine + 6, 0);
    }

    return new Position(declLine + 1, 0);
  }

  // Copies a changed resource into .lathe/ so a resource-only edit is picked up without a rebuild.
  // Returns the .lathe/ destination written, or empty if the file maps to no resource root or the
  // copy failed.
  Optional<Path> refreshResource(final String uri) {
    final var file = LatheUri.toPath(uri);
    final Optional<Path> dest = manifest.resourceDestination(file);
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

  private static String selectionLabel(final List<TestSelection> selections) {
    return selections.stream().map(TestSelection::value).collect(Collectors.joining(", "));
  }

  /**
   * Streams each drained transcript line to the client over the run token so the client can render
   * it live. Fired from {@code LaunchSession}'s drain threads, not the worker: the payload is
   * immutable and carries no session state, and lsp4j serializes the writes, so publishing off the
   * worker is safe here. A blank token (no live surface listening) yields a no-op.
   */
  private Consumer<TranscriptLine> streamConsumer(final String token) {
    if (token.isBlank()) {
      return line -> {};
    }

    return line -> ((LatheLanguageClient) client).testOutput(new TestOutputParams(token, line));
  }

  /**
   * Wraps a transcript consumer so the debug worker learns the JDWP agent is listening the moment
   * the JVM prints its readiness banner -- completing {@code ready} -- while still forwarding every
   * line downstream. Lets {@link #attachDebugHost} gate on the banner instead of a throwaway TCP
   * probe the agent would misread as a failed handshake. Package-private for direct unit coverage
   * of the readiness gate without launching a suspended JVM.
   */
  static Consumer<TranscriptLine> jdwpReadyConsumer(
      final JdwpOptions jdwp,
      final CompletableFuture<Void> ready,
      final Consumer<TranscriptLine> downstream) {
    return line -> {
      downstream.accept(line);
      if (jdwp.isListeningLine(line.text())) {
        ready.complete(null);
      }
    };
  }

  /**
   * Streams each per-test result the sink tailer sees to the client over the run token, so it can
   * mark that position live mid-run. Same off-worker publication rationale as {@link
   * #streamConsumer}; a blank token yields a no-op.
   */
  private Consumer<TestResult> resultConsumer(final String token) {
    if (token.isBlank()) {
      return result -> {};
    }

    return result -> ((LatheLanguageClient) client).testEvent(new TestEventParams(token, result));
  }

  // With the command that captures it, so a reader (an agent above all) knows the remedy.
  private static String noCapturedLaunch(final String moduleRel) {
    return "no captured test launch for %s; run `%s` to capture it"
        .formatted(moduleRel, LatheLayout.captureTestsCommand(moduleRel));
  }

  private static void launchTest(
      final Path workspaceRoot,
      final ReactorRewrite reactorRewrite,
      final List<Path> runnerClasspath,
      final String moduleRel,
      final List<TestSelection> selections,
      final RunItem overlay,
      final Consumer<TranscriptLine> onLine,
      final Consumer<TestResult> onResult,
      final Consumer<LaunchSession> onStart,
      final Stopwatch t,
      final CompletableFuture<LaunchOutcome> result) {
    try {
      final var template = new LaunchReader(workspaceRoot).test(moduleRel);
      if (template.isEmpty()) {
        LOG.warning(
            () ->
                "[launch] %s %s blocked no captured test-launch.json"
                    .formatted(moduleRel, selectionLabel(selections)));
        result.complete(LaunchOutcome.blocked(List.of(noCapturedLaunch(moduleRel))));
        return;
      }

      final var gate = CompletenessGate.verify(template.get(), workspaceRoot, reactorRewrite);
      if (!gate.complete()) {
        LOG.warning(
            () ->
                "[launch] %s %s blocked reasons=%s"
                    .formatted(moduleRel, selectionLabel(selections), gate.reasons()));
        result.complete(LaunchOutcome.blocked(gate.reasons()));
        return;
      }

      final Path resultsSink = Files.createTempFile("lathe-results-", ".ndjson");
      final ResolvedLaunch resolved =
          RunOverlay.applyToTest(
              template.get(),
              workspaceRoot,
              reactorRewrite,
              runnerClasspath,
              withTestFilter(workspaceRoot, moduleRel, selections),
              resultsSink,
              overlay,
              JdwpOptions.NONE);
      final var session =
          Launcher.launch(
              resolved.argv(),
              overlay.configLabel(),
              resultsSink,
              onLine,
              onResult,
              resolved.env(),
              resolved.cwd());
      onStart.accept(session);
      final String label = selectionLabel(selections);
      session
          .onExit()
          .whenComplete(
              (outcome, error) ->
                  completeRun(moduleRel, label, overlay.configLabel(), t, result, outcome, error));
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING,
          e,
          () ->
              "[launch] %s %s failed to launch %dms"
                  .formatted(moduleRel, selectionLabel(selections), t.elapsedMs()));
      result.completeExceptionally(e);
    }
  }

  // Shared completion for both test and main replay: propagate a spawn/exit failure, or log the
  // exit code and hand the outcome back to the caller's run future.
  private static void completeRun(
      final String moduleRel,
      final String label,
      final String configLabel,
      final Stopwatch t,
      final CompletableFuture<LaunchOutcome> result,
      final LaunchOutcome outcome,
      final Throwable error) {
    if (error != null) {
      LOG.log(
          Level.WARNING,
          error,
          () -> "[launch] %s %s failed %dms".formatted(moduleRel, label, t.elapsedMs()));
      result.completeExceptionally(error);
      return;
    }

    LOG.info(
        () ->
            "[launch] %s %s config=%s exit=%d %dms"
                .formatted(moduleRel, label, configLabel, outcome.exitCode(), t.elapsedMs()));
    result.complete(outcome);
  }

  CompletableFuture<LaunchOutcome> runMainFuture(
      final String moduleRel, final String mainClass, final String token) {
    return startMainRun(moduleRel, mainClass, token, baselineOverlay(moduleRel, RunKind.MAIN));
  }

  private CompletableFuture<LaunchOutcome> startMainRun(
      final String moduleRel, final String mainClass, final String token, final RunItem overlay) {
    final Consumer<TranscriptLine> onLine = streamConsumer(token);
    final Path root = workspaceRoot;
    final ReactorRewrite reactorRewrite = manifest.reactorRewrite();
    final var t = Stopwatch.start();
    final var result = new CompletableFuture<LaunchOutcome>();
    result.whenComplete((outcome, error) -> worker.execute(() -> activeRuns.remove(token)));
    final Consumer<LaunchSession> onStart =
        session -> worker.execute(() -> activeRuns.put(token, session));

    final var thread =
        new Thread(
            () ->
                launchMain(
                    root,
                    reactorRewrite,
                    moduleRel,
                    mainClass,
                    overlay,
                    onLine,
                    onStart,
                    t,
                    result),
            "lathe-launch-" + moduleRel);
    thread.setDaemon(true);
    thread.start();
    return result;
  }

  private static void launchMain(
      final Path workspaceRoot,
      final ReactorRewrite reactorRewrite,
      final String moduleRel,
      final String mainClass,
      final RunItem overlay,
      final Consumer<TranscriptLine> onLine,
      final Consumer<LaunchSession> onStart,
      final Stopwatch t,
      final CompletableFuture<LaunchOutcome> result) {
    try {
      final MainLaunchPlan plan =
          resolveMainLaunch(
              workspaceRoot, reactorRewrite, moduleRel, mainClass, overlay, JdwpOptions.NONE);
      if (plan.blocked()) {
        LOG.warning(
            () ->
                "[launch] %s %s blocked reasons=%s"
                    .formatted(moduleRel, mainClass, plan.blockedReasons()));
        result.complete(LaunchOutcome.blocked(plan.blockedReasons()));
        return;
      }

      // A main run has no JUnit result stream, so it spawns with a null sink and a no-op result
      // consumer; the resolved module/class path is all replay needs.
      final ResolvedLaunch resolved = plan.launch();
      final var session =
          Launcher.launch(
              resolved.argv(),
              overlay.configLabel(),
              null,
              onLine,
              ignored -> {},
              resolved.env(),
              resolved.cwd());
      onStart.accept(session);
      session
          .onExit()
          .whenComplete(
              (outcome, error) ->
                  completeRun(
                      moduleRel, mainClass, overlay.configLabel(), t, result, outcome, error));
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING,
          e,
          () ->
              "[launch] %s %s failed to launch %dms"
                  .formatted(moduleRel, mainClass, t.elapsedMs()));
      result.completeExceptionally(e);
    }
  }

  CompletableFuture<LaunchOutcome> runNamedFuture(final String name, final String token) {
    final RunItem config = resolveConfig(name);
    return switch (config.kind()) {
      case MAIN -> startMainRun(config.module(), config.mainClass(), token, config);
      case TEST -> startTestRun(config.module(), config.selectors(), token, config);
    };
  }

  DebugStartResult debugNamed(final String name, final String token) {
    final RunItem config = resolveConfig(name);
    return switch (config.kind()) {
      case MAIN -> startDebugMain(config.module(), config.mainClass(), token, config);
      case TEST -> startDebugTest(config.module(), config.selectors(), token, config);
    };
  }

  List<RunConfigInfo> listRunConfigs() {
    return readRunConfig().configs().stream().map(WorkspaceSession::toInfo).toList();
  }

  RunConfigWriter.Saved saveRunConfig(
      final String name,
      final String moduleRel,
      final RunKind kind,
      final String mainClass,
      final List<TestSelection> selectors,
      final boolean overwrite)
      throws IOException {
    return new RunConfigWriter(workspaceRoot)
        .save(name, moduleRel, kind, mainClass, selectors, overwrite);
  }

  // Read fresh every call so an edited config file takes effect on the next run without a reload.
  private RunOverlaySet readRunConfig() {
    return new RunConfigReader(workspaceRoot).read();
  }

  private RunItem baselineOverlay(final String moduleRel, final RunKind kind) {
    return readRunConfig().defaultFor(moduleRel, kind);
  }

  private RunItem resolveConfig(final String name) {
    return readRunConfig()
        .byName(name)
        .orElseThrow(
            () -> new IllegalArgumentException("no run config named '%s'".formatted(name)));
  }

  private static RunConfigInfo toInfo(final RunItem config) {
    final String target =
        config.kind() == RunKind.MAIN ? config.mainClass() : selectionLabel(config.selectors());
    return new RunConfigInfo(
        config.name(),
        config.kind().name(),
        config.module(),
        target,
        String.join(" ", config.jvmArgs()));
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
    if (routeCompiler(uri) instanceof final CompilerRoute.Module module) {
      touchAnalysisCache(uri);
      return module
          .worker()
          .runnables(uri, doc.content(), doc.version(), moduleRel(module.config()))
          .thenApply(
              targets -> {
                LOG.fine(
                    () ->
                        "[runnables] %s %dms targets=%d"
                            .formatted(uri, t.elapsedMs(), targets.size()));
                return targets;
              });
    }

    return CompletableFuture.completedFuture(List.of());
  }

  private String moduleRel(final ModuleSourceConfig config) {
    return moduleRel(workspaceRoot, config.moduleDir());
  }

  // The registry module owning the file, as a command formatter's %MODULE% names it: "." for the
  // root module.
  Optional<Path> buildTool() {
    return manifest.buildTool();
  }

  Optional<String> moduleRelOf(final Path file) {
    return workspace
        .moduleSourceFor(file)
        .map(this::moduleRel)
        .map(rel -> rel.isEmpty() ? "." : rel);
  }

  // A module's .lathe directory as the reactor-relative module path the tools and the sync use.
  private static String moduleRel(final Path workspaceRoot, final Path moduleDir) {
    return FileUtil.slashed(workspaceRoot.resolve(LatheLayout.LATHE_DIR).relativize(moduleDir));
  }

  private String moduleRelForDir(final Path moduleDir) {
    return moduleRel(workspaceRoot, moduleDir);
  }

  void setDocumentsFollowDisk(final boolean follow) {
    documentsFollowDisk = follow;
  }

  void onOpen(final String uri, final String content, final int version) {
    final var snapshot = docs.put(uri, content, version);
    LOG.info(() -> "[open] %s".formatted(uri));
    candidateIndex.update(uri, content);
    // An editor may open a buffer restored with unsaved changes.
    if (LatheUri.isFileUri(uri) && content.equals(readSource(LatheUri.toPath(uri)))) {
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
    final CompilerRoute route = routeCompiler(uri);
    if (route instanceof CompilerRoute.Missing) {
      return CompletableFuture.completedFuture(List.of());
    }

    // A dependency or JDK source is registered and compiled too, as the editor's open does, so the
    // engine's queries on it find an analysis; its diagnostics are not the agent's to fix.
    final boolean reactor = route instanceof CompilerRoute.Module;
    final var snapshot = docs.put(uri, content, version);
    if (reactor) {
      // The engine read this content from disk: indexed as a disk file, the pass keeps it current.
      candidateIndex.indexFromDisk(LatheUri.toPath(uri));
    }

    final var result = new CompletableFuture<List<Diagnostic>>();
    submitCompile(
        route,
        snapshot,
        CompileMode.OPEN,
        (ignored, response) -> result.complete(reactor ? response.diagnostics() : List.of()));
    return result;
  }

  void onChange(final String uri, final String content, final int version) {
    docs.put(uri, content, version);
    unsaved.add(uri);
    LOG.fine(() -> "[change] %s".formatted(uri));
    candidateIndex.update(uri, content);
    worker.cancel(uri);
    if (trackRename(renameTransaction.onContent(uri, content))
        == RenameTransaction.Action.SUPPRESS) {
      return;
    }

    publisher.publishEmpty(uri);
    worker.schedule(
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
    worker.cancel(uri);
    renameTransaction.onClose(uri);

    analysisLru.remove(uri);
    writeDiagnostics.remove(uri);
    workspace.dropFromAllCaches(uri);
    publisher.publishEmpty(uri);
    candidateIndex.indexFromDisk(LatheUri.toPath(uri));
  }

  void onSave(final String uri, final String savedContent) {
    LOG.info(() -> "[save] %s".formatted(uri));
    unsaved.remove(uri);
    worker.cancel(uri);

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
    final var route = routeCompiler(uri);
    if (route instanceof CompilerRoute.Module) {
      writeReported(Set.of(LatheUri.toPath(uri)));
      return;
    }

    final AfterCompile afterCompile =
        route instanceof CompilerRoute.External
            ? publishThen(route, () -> scheduleAstRefresh(uri))
            : publisher::publishIfCurrent;
    submitCompile(route, snapshot, CompileMode.OPEN, afterCompile);
  }

  CompletableFuture<List<Location>> referencesFuture(
      final String uri,
      final Position pos,
      final boolean includeDeclaration,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    final OpenDocument openFile = docs.get(uri);
    if (openFile == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    final var request = featureRequest(openFile, pos);

    final Optional<CompilationWorker> routedWorker = routeCompiler(uri).analysisWorker();
    if (routedWorker.isEmpty()) {
      return CompletableFuture.completedFuture(List.of());
    }

    final CompilationWorker cursorWorker = routedWorker.get();

    touchAnalysisCache(openFile.uri());

    // Capture declaring module synchronously on the lathe-worker thread before any async hand-off
    final var cursorConfig = workspace.moduleSourceFor(LatheUri.toPath(uri));

    final var t = Stopwatch.start();
    final var targetName = new AtomicReference<String>();
    return cursorWorker
        .resolveTarget(request, cancelChecker)
        .thenCompose(
            target -> {
              cancelChecker.checkCanceled();
              if (target == null) {
                return CompletableFuture.completedFuture(List.of());
              }

              return referenceSearchTarget(cursorWorker, request, target, cancelChecker)
                  .thenCompose(
                      searchTarget -> {
                        targetName.set(searchTarget.simpleName());
                        return searchReferencesForTarget(
                            openFile,
                            cursorWorker,
                            cursorConfig.orElse(null),
                            searchTarget,
                            includeDeclaration,
                            cancelChecker,
                            progress,
                            "Finding references to %s".formatted(searchTarget.simpleName()));
                      });
            })
        .thenApply(
            matches -> {
              cancelChecker.checkCanceled();
              final var name = targetName.get();
              if (name != null) {
                LOG.info(
                    () ->
                        "[references] %s %dms target=%s hits=%d"
                            .formatted(uri, t.elapsedMs(), name, matches.size()));
              }
              return toLocations(matches);
            })
        .exceptionally(
            ex -> {
              if (ex instanceof CancellationException cancellation) {
                throw cancellation;
              }
              if (ex instanceof CompletionException completion
                  && completion.getCause() instanceof CancellationException cancellation) {
                throw cancellation;
              }
              LOG.log(
                  SEVERE,
                  ex,
                  () ->
                      "[references] %s target=%s %dms failed"
                          .formatted(uri, targetName.get(), t.elapsedMs()));
              throw ex instanceof CompletionException completion
                  ? completion
                  : new CompletionException(ex);
            });
  }

  CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>>
      prepareRenameFuture(final String uri, final Position pos, final boolean fileRenameSupported) {
    final OpenDocument openFile = docs.get(uri);
    if (openFile == null || !(routeCompiler(uri) instanceof final CompilerRoute.Module module)) {
      return CompletableFuture.completedFuture(null);
    }

    final var request = featureRequest(openFile, pos);
    return module
        .worker()
        .resolveRenameTarget(request)
        .thenApply(
            target -> {
              if (target == null) {
                return null;
              }

              requireRenameable(target);
              // Refuse a type whose declaring file must move when the client cannot apply the file
              // rename, rather than returning a half-rename that leaves a public type in a
              // mismatched file.
              if (!fileRenameSupported && renameDeclaringFile(target).isPresent()) {
                return null;
              }

              final Range range = RenameProvider.identifierRange(openFile.content(), pos);
              return range == null ? null : Either3.forFirst(range);
            });
  }

  // Renaming the uses of a declaration the rename cannot edit breaks every one of them: one outside
  // the reactor (JDK or a dependency), one a processor regenerates, or a method overriding either.
  private void requireRenameable(final ReferenceTarget target) {
    final List<Path> roots = workspace.allSourceRoots();
    final Map<String, Optional<Path>> declarations =
        Stream.concat(Stream.of(target.qualifiedName()), target.overriddenDeclarers().stream())
            .distinct()
            .collect(
                Collectors.toUnmodifiableMap(
                    Function.identity(),
                    binaryName -> TypeSourceLocator.findSourceFile(binaryName, roots)));
    // A secondary top-level type has no file of its own name, so the index vouches for it; the file
    // lookup still covers a type too new for the index.
    if (declarations.entrySet().stream()
        .anyMatch(
            declaration ->
                declaration.getValue().isEmpty()
                    && !typeIndex.isReactorType(declaration.getKey()))) {
      throw renameRefused(
          "`%s` is declared, or overrides a method declared, outside the project (JDK or a dependency), so it cannot be renamed"
              .formatted(target.simpleName()));
    }

    if (declarations.values().stream()
        .flatMap(Optional::stream)
        .anyMatch(this::isGeneratedSource)) {
      throw renameRefused(
          "`%s` is declared, or overrides a method declared, in generated source; rename the source it is generated from instead"
              .formatted(target.simpleName()));
    }
  }

  // An override the rename edits must not also override a method outside the renamed family:
  // renamed, it would silently stop overriding that one.
  private static void requireNoOverrideLeftBehind(
      final ReferenceTarget target, final List<ReferenceMatch> matches) {
    matches.stream()
        .filter(match -> !match.alsoOverrides().isEmpty())
        .findFirst()
        .ifPresent(
            match -> {
              throw renameRefused(
                  "`%s` cannot be renamed: its override in %s also overrides %s, which the rename would leave behind"
                      .formatted(
                          target.simpleName(),
                          LatheUri.toPath(match.uri()).getFileName(),
                          String.join(", ", match.alsoOverrides())));
            });
  }

  private static ResponseErrorException renameRefused(final String message) {
    return new ResponseErrorException(
        new ResponseError(ResponseErrorCode.RequestFailed, message, null));
  }

  // The source file to rename when the target is the file-defining type for its file (so `Foo.java`
  // must become `Bar.java`); empty for members, nested types, and secondary top-level types that
  // stay in place.
  private Optional<Path> renameDeclaringFile(final ReferenceTarget target) {
    if (!(target.kind().isClass() || target.kind().isInterface())) {
      return Optional.empty();
    }

    return TypeSourceLocator.findSourceFile(target.qualifiedName(), workspace.allSourceRoots())
        .filter(file -> file.getFileName().toString().equals(target.simpleName() + ".java"));
  }

  private WorkspaceEdit renameEdit(
      final ReferenceTarget target,
      final List<Location> occurrences,
      final String newName,
      final boolean fileRenameSupported) {
    // Generated sources are left alone: their processors regenerate them from the renamed sources,
    // and an edited copy written alongside would collide with the regenerated one.
    final List<Location> editable =
        occurrences.stream()
            .filter(location -> !isGeneratedSource(LatheUri.toPath(location.getUri())))
            .toList();
    if (editable.isEmpty()) {
      return null;
    }

    final Optional<Path> declaringFile = renameDeclaringFile(target);
    if (declaringFile.isEmpty()) {
      return RenameProvider.toWorkspaceEdit(editable, newName);
    }

    if (!fileRenameSupported) {
      return null;
    }

    final var file = declaringFile.get();
    final var oldUri = file.toUri().toString();
    final var newUri = file.resolveSibling(newName + ".java").toUri().toString();
    return RenameProvider.toWorkspaceEditWithFileRename(editable, newName, oldUri, newUri);
  }

  private boolean isGeneratedSource(final Path file) {
    return workspace.allConfigs().stream().anyMatch(config -> config.isGenerated(file));
  }

  CompletableFuture<WorkspaceEdit> renameFuture(
      final String uri,
      final Position pos,
      final String newName,
      final boolean fileRenameSupported,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    final OpenDocument openFile = docs.get(uri);
    if (openFile == null
        || !RenameProvider.isValidName(newName)
        || !(routeCompiler(uri) instanceof final CompilerRoute.Module module)) {
      return CompletableFuture.completedFuture(null);
    }

    final var cursorWorker = module.worker();
    final var request = featureRequest(openFile, pos);
    final Optional<ModuleSourceConfig> cursorConfig =
        workspace.moduleSourceFor(LatheUri.toPath(uri));
    final var t = Stopwatch.start();
    return cursorWorker
        .renameConflict(request, newName)
        .thenCompose(
            conflict -> {
              conflict.ifPresent(
                  reason -> {
                    throw renameRefused(reason);
                  });
              return cursorWorker.resolveRenameTarget(request);
            })
        .thenCompose(
            target -> {
              cancelChecker.checkCanceled();
              if (target == null) {
                return CompletableFuture.completedFuture((WorkspaceEdit) null);
              }

              requireRenameable(target);
              return referenceSearchTarget(cursorWorker, request, target, cancelChecker)
                  .thenCompose(
                      searchTarget ->
                          searchReferencesForTarget(
                                  openFile,
                                  cursorWorker,
                                  cursorConfig.orElse(null),
                                  searchTarget,
                                  true,
                                  cancelChecker,
                                  progress,
                                  "Renaming %s".formatted(searchTarget.simpleName()))
                              .thenApply(
                                  matches -> {
                                    LOG.info(
                                        () ->
                                            "[rename] %s %dms target=%s edits=%d"
                                                .formatted(
                                                    uri,
                                                    t.elapsedMs(),
                                                    searchTarget.simpleName(),
                                                    matches.size()));
                                    requireNoOverrideLeftBehind(searchTarget, matches);
                                    return renameEdit(
                                        searchTarget,
                                        toLocations(matches),
                                        newName,
                                        fileRenameSupported);
                                  }));
            });
  }

  // The request a position feature sends to the module's worker, for the open document.
  private SourceFeatureRequest featureRequest(final OpenDocument doc, final Position pos) {
    return new SourceFeatureRequest(
        doc.uri(), doc.content(), doc.version(), pos, workspace.allSourceRoots(), manifest);
  }

  CompletableFuture<List<Location>> instantiationsFuture(
      final String uri,
      final Position pos,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    final OpenDocument openFile = docs.get(uri);
    if (openFile == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    final var request = featureRequest(openFile, pos);

    final Optional<CompilationWorker> routedWorker = routeCompiler(uri).analysisWorker();
    if (routedWorker.isEmpty()) {
      return CompletableFuture.completedFuture(List.of());
    }

    final CompilationWorker cursorWorker = routedWorker.get();

    touchAnalysisCache(openFile.uri());
    final var cursorConfig = workspace.moduleSourceFor(LatheUri.toPath(uri));
    final var t = Stopwatch.start();

    return cursorWorker
        .instantiationTargets(request, cancelChecker)
        .thenCompose(
            targets -> {
              cancelChecker.checkCanceled();
              if (targets.isEmpty()) {
                return CompletableFuture.completedFuture(List.<Location>of());
              }

              // One reference search per constructor overload, unioned -- each match sits at the
              // `new XXX` identifier, so these are exactly the instantiation sites. All overloads
              // share the one progress task the caller finishes, so a multi-ctor type shows one
              // bar.
              final List<CompletableFuture<List<ReferenceMatch>>> searches =
                  targets.stream()
                      .map(
                          target ->
                              searchReferencesForTarget(
                                  openFile,
                                  cursorWorker,
                                  cursorConfig.orElse(null),
                                  target,
                                  false,
                                  cancelChecker,
                                  progress,
                                  "Finding instantiation sites"))
                      .toList();
              return joinCandidateResults(searches, cancelChecker)
                  .thenApply(WorkspaceSession::toLocations);
            })
        .thenApply(
            locations -> {
              cancelChecker.checkCanceled();
              LOG.info(
                  () ->
                      "[instantiations] %s %dms hits=%d"
                          .formatted(uri, t.elapsedMs(), locations.size()));
              return locations;
            });
  }

  private CompletableFuture<ReferenceTarget> referenceSearchTarget(
      final CompilationWorker cursorWorker,
      final SourceFeatureRequest request,
      final ReferenceTarget target,
      final CancelChecker cancelChecker) {
    if (target.kind() != ElementKind.METHOD) {
      return CompletableFuture.completedFuture(target);
    }

    return cursorWorker
        .resolveContractTarget(request, cancelChecker)
        .thenApply(contract -> contract != null ? contract : target);
  }

  private CompletableFuture<List<ReferenceMatch>> searchReferencesForTarget(
      final OpenDocument openFile,
      final CompilationWorker cursorWorker,
      final ModuleSourceConfig cursorConfig,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress,
      final String progressTitle) {
    if (target.scope() == ReferenceTarget.SearchScope.DECLARING_FILE) {
      progress.begin(progressTitle, 1);
      return cursorWorker
          .searchReferences(
              openFile.uri(),
              openFile.content(),
              openFile.version(),
              target,
              includeDeclaration,
              cancelChecker)
          .whenComplete(
              (matches, failure) -> {
                if (failure == null) {
                  progress.advance(false, matches.size());
                }
              });
    }

    final Path packageRel =
        target.scope() == ReferenceTarget.SearchScope.DECLARING_MODULE
            ? ReferenceCandidatePlanner.packageRelForQualifiedName(target.qualifiedName())
            : null;

    final ModuleSourceConfig searchConfig = declaringConfigFor(target, cursorConfig);
    final List<ModuleSourceConfig> configs = planSearchScope(target, searchConfig);
    final var planningTimer = Stopwatch.start();
    final List<ModuleSearchInputs> searchInputs =
        configs.stream()
            .map(
                config ->
                    new ModuleSearchInputs(
                        workspace.workerFor(config), planSearchInputs(config, target, packageRel)))
            .toList();
    final int openCount =
        searchInputs.stream()
            .map(ModuleSearchInputs::inputs)
            .mapToInt(inputs -> inputs.openDocuments().size())
            .sum();
    final int diskCount =
        searchInputs.stream()
            .map(ModuleSearchInputs::inputs)
            .mapToInt(inputs -> inputs.diskCandidates().size())
            .sum();
    final int maxModuleCandidates =
        searchInputs.stream()
            .map(ModuleSearchInputs::inputs)
            .mapToInt(WorkspaceSearchInputs::size)
            .max()
            .orElse(0);
    LOG.fine(
        () ->
            "[references:plan] %s planned modules=%d open=%d disk=%d maxModule=%d ready %dms"
                .formatted(
                    openFile.uri(),
                    searchInputs.size(),
                    openCount,
                    diskCount,
                    maxModuleCandidates,
                    planningTimer.elapsedMs()));
    progress.begin(
        progressTitle,
        searchInputs.stream()
            .map(ModuleSearchInputs::inputs)
            .mapToInt(WorkspaceSearchInputs::size)
            .sum());

    final List<CompletableFuture<List<ReferenceMatch>>> searches =
        searchInputs.stream()
            .flatMap(
                inputs ->
                    searchFutures(inputs, target, includeDeclaration, cancelChecker, progress))
            .toList();
    return joinCandidateResults(searches, cancelChecker);
  }

  private Stream<CompletableFuture<List<ReferenceMatch>>> searchFutures(
      final ModuleSearchInputs searchInputs,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    final CompilationWorker worker = searchInputs.worker();
    final WorkspaceSearchInputs inputs = searchInputs.inputs();
    final Stream<CompletableFuture<List<ReferenceMatch>>> openSearches =
        inputs.openDocuments().stream()
            .map(
                doc ->
                    searchOpenDocument(
                        worker, doc, target, includeDeclaration, cancelChecker, progress));
    final Stream<CompletableFuture<List<ReferenceMatch>>> diskSearches =
        CollectionUtil.partition(inputs.diskCandidates(), REFERENCE_BATCH_SIZE).stream()
            .map(
                chunk ->
                    searchDiskChunk(
                        worker, chunk, target, includeDeclaration, cancelChecker, progress));
    return Stream.concat(openSearches, diskSearches);
  }

  private CompletableFuture<List<ReferenceMatch>> searchOpenDocument(
      final CompilationWorker worker,
      final OpenDocument doc,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    touchAnalysisCache(doc.uri());
    return worker
        .searchReferences(
            doc.uri(), doc.content(), doc.version(), target, includeDeclaration, cancelChecker)
        .whenComplete(
            (matches, failure) -> {
              if (failure == null) {
                progress.advance(false, matches.size());
              }
            });
  }

  private CompletableFuture<List<ReferenceMatch>> searchDiskChunk(
      final CompilationWorker worker,
      final List<DiskCandidate> chunk,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    final List<TransientSource> sources =
        chunk.stream().map(d -> new TransientSource(d.uri(), d.content())).toList();
    return worker.searchReferencesTransient(
        sources, target, includeDeclaration, hits -> progress.advance(true, hits), cancelChecker);
  }

  private List<ModuleSourceConfig> planSearchScope(
      final ReferenceTarget target, final ModuleSourceConfig cursorConfig) {
    return switch (target.scope()) {
      case DECLARING_FILE -> List.of();
      case DECLARING_MODULE ->
          cursorConfig != null ? moduleGraph.configsForModule(cursorConfig.moduleDir()) : List.of();
      case REACTOR_MODULES ->
          cursorConfig != null
              ? moduleGraph.referenceSearchScope(cursorConfig)
              : workspace.allConfigs();
    };
  }

  private ModuleSourceConfig declaringConfigFor(
      final ReferenceTarget target, final ModuleSourceConfig fallback) {
    return reactorShards.entrySet().stream()
        .filter(
            entry ->
                entry.getValue().stream()
                    .anyMatch(type -> type.binaryName().equals(target.qualifiedName())))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(fallback);
  }

  private static <T> CompletableFuture<List<T>> joinCandidateResults(
      final List<CompletableFuture<List<T>>> futures, final CancelChecker cancelChecker) {
    return futures.stream()
        .reduce(
            CompletableFuture.completedFuture(List.of()),
            (f1, f2) ->
                f1.thenCombine(
                    f2,
                    (a, b) -> {
                      cancelChecker.checkCanceled();
                      return Stream.concat(a.stream(), b.stream()).toList();
                    }));
  }

  private static List<Location> toLocations(final List<ReferenceMatch> matches) {
    return matches.stream().map(ReferenceMatch::toLocation).toList();
  }

  // Document highlight is references narrowed to the current buffer: resolve the symbol under the
  // cursor, then reuse the single-file reference search. No workspace/candidate index is touched,
  // so it stays cheap even for fields and methods.
  CompletableFuture<List<DocumentHighlight>> documentHighlightFuture(
      final String uri, final Position pos) {
    return openDocFeature(
        uri,
        List.of(),
        (worker, doc) -> {
          final var request = featureRequest(doc, pos);
          final var t = Stopwatch.start();
          return worker
              .resolveTarget(request)
              .thenCompose(target -> documentHighlightsForTarget(worker, doc, target))
              .thenApply(
                  highlights -> {
                    LOG.fine(
                        () ->
                            "[documentHighlight] %s %dms hits=%d"
                                .formatted(uri, t.elapsedMs(), highlights.size()));
                    return highlights;
                  })
              .exceptionally(
                  ex ->
                      logAndReturn(
                          ex, "[documentHighlight] failed for %s".formatted(uri), List.of()));
        });
  }

  private CompletableFuture<List<DocumentHighlight>> documentHighlightsForTarget(
      final CompilationWorker worker, final OpenDocument doc, final ReferenceTarget target) {
    if (target == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    return worker
        .searchReferences(doc.uri(), doc.content(), doc.version(), target, true, () -> {})
        .thenApply(WorkspaceSession::toHighlights);
  }

  private static List<DocumentHighlight> toHighlights(final List<ReferenceMatch> matches) {
    return matches.stream().map(ReferenceMatch::toHighlight).toList();
  }

  private static Path declaringPackageRel(final Path cursorPath, final ModuleSourceConfig config) {
    if (config == null) {
      return null;
    }

    return config.sourceRoots().stream()
        .filter(cursorPath::startsWith)
        .map(root -> root.relativize(cursorPath.getParent()))
        .findFirst()
        .orElse(null);
  }

  static boolean isInPackageScope(
      final Path path, final List<Path> sourceRoots, final Path packageRel) {
    if (packageRel == null) {
      return sourceRoots.stream().anyMatch(path::startsWith);
    }

    return sourceRoots.stream().anyMatch(root -> path.startsWith(root.resolve(packageRel)));
  }

  // A document that follows the disk takes the file's new content as a new generation; a query
  // already running keeps the snapshot it took.
  private void reloadFromDisk(final Path source) {
    final String uri = LatheUri.fromPath(source);
    final OpenDocument open = docs.get(uri);
    final String content = readSource(source);
    if (open != null && content != null) {
      docs.put(uri, content, open.version());
    }
  }

  private static Optional<DiskCandidate> readDiskCandidate(final String uri) {
    try {
      return Optional.of(new DiskCandidate(uri, Files.readString(LatheUri.toPath(uri))));
    } catch (final IOException e) {
      LOG.log(Level.FINE, e, () -> "[references] failed to read candidate: %s".formatted(uri));
      return Optional.empty();
    }
  }

  private record DiskCandidate(String uri, String content) {}

  private record WorkspaceSearchInputs(
      List<OpenDocument> openDocuments, List<DiskCandidate> diskCandidates) {

    private int size() {
      return openDocuments.size() + diskCandidates.size();
    }
  }

  private record ModuleSearchInputs(CompilationWorker worker, WorkspaceSearchInputs inputs) {}

  private WorkspaceSearchInputs planSearchInputs(
      final ModuleSourceConfig config, final ReferenceTarget target, final Path packageRel) {
    // Include the generated-sources root so a module's annotation-processor output (a record's
    // @Builder) is in scope; it references the record by simple name and never lives under a
    // regular source root (FR-012/FR-013).
    final List<Path> searchRoots = config.searchRoots();
    final List<OpenDocument> openForConfig =
        docs.all().stream()
            .filter(
                doc ->
                    workspace
                        .moduleSourceFor(LatheUri.toPath(doc.uri()))
                        .map(c -> c.equals(config))
                        .orElse(false))
            .filter(doc -> isInPackageScope(LatheUri.toPath(doc.uri()), searchRoots, packageRel))
            .toList();
    final Set<String> openUris =
        openForConfig.stream().map(OpenDocument::uri).collect(Collectors.toUnmodifiableSet());
    final var planner = new ReferenceCandidatePlanner(candidateIndex, typeIndex);
    final List<DiskCandidate> diskCandidates =
        planner.planCandidates(config, target).stream()
            .filter(uri -> !openUris.contains(uri))
            .filter(uri -> isInPackageScope(LatheUri.toPath(uri), searchRoots, packageRel))
            .flatMap(uri -> readDiskCandidate(uri).stream())
            .toList();
    return new WorkspaceSearchInputs(openForConfig, diskCandidates);
  }

  CompletableFuture<Hover> hoverFuture(final String uri, final Position pos) {
    return openDocFeature(
        uri,
        null,
        (worker, doc) -> {
          final var request = featureRequest(doc, pos);
          return worker
              .hover(request)
              .exceptionally(ex -> logAndReturn(ex, "[hover] failed for %s".formatted(uri), null));
        });
  }

  CompletableFuture<SignatureHelp> signatureHelpFuture(final String uri, final Position pos) {
    return openDocFeature(
        uri,
        null,
        (worker, doc) -> {
          final var request = featureRequest(doc, pos);
          return worker
              .signatureHelp(request)
              .exceptionally(
                  ex -> logAndReturn(ex, "[signatureHelp] failed for %s".formatted(uri), null));
        });
  }

  CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      definitionFuture(final String uri, final Position pos) {
    LOG.info(
        () ->
            "[definition] %s line=%d character=%d"
                .formatted(uri, pos.getLine(), pos.getCharacter()));
    return openDocFeature(
        uri,
        Either.forLeft(List.of()),
        (worker, doc) -> {
          final var request = featureRequest(doc, pos);
          return worker
              .definition(request)
              .thenApply(location -> definitionResult(location.map(List::of).orElseGet(List::of)))
              .exceptionally(
                  ex ->
                      logAndReturn(
                          ex,
                          "[definition] failed for %s".formatted(uri),
                          Either.forLeft(List.of())));
        });
  }

  CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      declarationFuture(final String uri, final Position pos) {
    LOG.info(
        () ->
            "[declaration] %s line=%d character=%d"
                .formatted(uri, pos.getLine(), pos.getCharacter()));
    return openDocFeature(
        uri,
        Either.forLeft(List.of()),
        (worker, doc) -> {
          final var request = featureRequest(doc, pos);
          return worker
              .declaration(request)
              .thenApply(location -> definitionResult(location.map(List::of).orElseGet(List::of)))
              .exceptionally(
                  ex ->
                      logAndReturn(
                          ex,
                          "[declaration] failed for %s".formatted(uri),
                          Either.forLeft(List.of())));
        });
  }

  CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      implementationFuture(final String uri, final Position pos) {
    final OpenDocument openFile = docs.get(uri);
    if (openFile == null) {
      return CompletableFuture.completedFuture(Either.forLeft(List.of()));
    }

    final var request = featureRequest(openFile, pos);
    final var indexSnapshot = typeIndex;
    final var lambdaSnapshot = reactorLambdaImplementors;
    final Optional<CompilationWorker> routedWorker = routeCompiler(uri).analysisWorker();
    if (routedWorker.isEmpty()) {
      return CompletableFuture.completedFuture(Either.forLeft(List.of()));
    }

    final CompilationWorker cursorWorker = routedWorker.get();

    touchAnalysisCache(openFile.uri());

    final var t = Stopwatch.start();
    return cursorWorker
        .resolveTarget(request)
        .thenCompose(
            target ->
                worker
                    .submit(
                        () ->
                            implementationForTarget(
                                target, request, cursorWorker, indexSnapshot, lambdaSnapshot))
                    .thenCompose(future -> future))
        .thenApply(
            locations -> {
              LOG.fine(
                  () ->
                      "[implementation] %s %dms hits=%d"
                          .formatted(uri, t.elapsedMs(), locations.size()));
              return definitionResult(locations);
            });
  }

  private CompletableFuture<List<Location>> implementationForTarget(
      final ReferenceTarget target,
      final SourceFeatureRequest request,
      final CompilationWorker cursorWorker,
      final WorkspaceTypeIndex indexSnapshot,
      final Map<String, List<String>> lambdaSnapshot) {
    if (target == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    if (target.kind().isClass() || target.kind().isInterface()) {
      return cursorWorker.typeImplementations(request, indexSnapshot);
    }

    if (target.kind() != ElementKind.METHOD) {
      return CompletableFuture.completedFuture(List.of());
    }

    return cursorWorker
        .functionalInterfaceName(request)
        .thenCompose(
            samInterface ->
                implementationsForMethod(target, samInterface, indexSnapshot, lambdaSnapshot));
  }

  private CompletableFuture<List<Location>> implementationsForMethod(
      final ReferenceTarget target,
      final Optional<String> samInterface,
      final WorkspaceTypeIndex indexSnapshot,
      final Map<String, List<String>> lambdaSnapshot) {
    final Map<Path, Set<String>> candidatesByFile =
        Stream.of(
                subtypeImplementationFiles(target, indexSnapshot),
                lambdaImplementationFiles(samInterface),
                bytecodeLambdaImplementationFiles(
                    target, samInterface, indexSnapshot, lambdaSnapshot))
            .flatMap(stream -> stream)
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey, Map.Entry::getValue, WorkspaceSession::unionNames));
    return candidatesByFile.entrySet().stream()
        .map(entry -> methodImplementationFuture(entry.getKey(), entry.getValue(), target))
        .reduce(
            CompletableFuture.completedFuture(List.of()),
            (left, right) ->
                left.thenCombine(
                    right,
                    (first, second) -> Stream.concat(first.stream(), second.stream()).toList()));
  }

  private Stream<Map.Entry<Path, Set<String>>> subtypeImplementationFiles(
      final ReferenceTarget target, final WorkspaceTypeIndex indexSnapshot) {
    return indexSnapshot.transitiveSubtypes(target.qualifiedName()).stream()
        .filter(TypeSourceLocator::isNamedDeclaration)
        .flatMap(
            entry ->
                TypeSourceLocator.findSourceFile(entry, workspace.allSourceRoots()).stream()
                    .map(path -> Map.entry(path, Set.of(entry.binaryName()))));
  }

  // Files that spell the functional interface (via the identifier index) — the freshest path for
  // lambdas in open/edited files. Empty when the target method is not a functional-interface SAM.
  // Argument-position lambdas, which never spell the interface, are covered instead by
  // bytecodeLambdaImplementationFiles.
  private Stream<Map.Entry<Path, Set<String>>> lambdaImplementationFiles(
      final Optional<String> samInterface) {
    return samInterface.stream()
        .flatMap(name -> candidateIndex.candidateUris(name).stream())
        .map(LatheUri::toPath)
        .map(path -> Map.entry(path, Set.<String>of()));
  }

  // Classes the compiled bytecode records as converting a lambda / method reference to the target
  // SAM interface or a functional sub-interface of it — including argument-position lambdas whose
  // source never names the interface. Empty when the target method is not a functional-interface
  // SAM.
  private Stream<Map.Entry<Path, Set<String>>> bytecodeLambdaImplementationFiles(
      final ReferenceTarget target,
      final Optional<String> samInterface,
      final WorkspaceTypeIndex indexSnapshot,
      final Map<String, List<String>> lambdaSnapshot) {
    if (samInterface.isEmpty()) {
      return Stream.empty();
    }

    return functionalInterfaceFamily(target.qualifiedName(), indexSnapshot).stream()
        .flatMap(iface -> lambdaSnapshot.getOrDefault(iface, List.of()).stream())
        .distinct()
        .flatMap(
            binaryName ->
                TypeSourceLocator.findSourceFile(binaryName, workspace.allSourceRoots()).stream())
        .map(path -> Map.entry(path, Set.<String>of()));
  }

  // The interface itself plus its functional sub-interfaces: a lambda converted to a sub-interface
  // compiles to that sub-interface's descriptor, so the index must be queried for each.
  private static Set<String> functionalInterfaceFamily(
      final String interfaceBinaryName, final WorkspaceTypeIndex indexSnapshot) {
    return Stream.concat(
            Stream.of(interfaceBinaryName),
            indexSnapshot.transitiveSubtypes(interfaceBinaryName).stream()
                .filter(
                    entry ->
                        entry.kind() == io.github.aglibs.lathe.core.typeindex.TypeKind.INTERFACE)
                .map(TypeIndexEntry::binaryName))
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Set<String> unionNames(final Set<String> left, final Set<String> right) {
    return Stream.concat(left.stream(), right.stream()).collect(Collectors.toUnmodifiableSet());
  }

  private CompletableFuture<List<Location>> methodImplementationFuture(
      final Path sourceFile, final Set<String> candidateBinaryNames, final ReferenceTarget target) {
    final var config = workspace.moduleSourceFor(sourceFile);
    if (config.isEmpty()) {
      return CompletableFuture.completedFuture(List.of());
    }

    final var uri = sourceFile.toUri().toString();
    final OpenDocument openFile = docs.get(uri);
    final var diskFile = openFile == null ? readDiskCandidate(uri).orElse(null) : null;
    if (openFile == null && diskFile == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    final String content = openFile != null ? openFile.content() : diskFile.content();
    final int version = openFile != null ? openFile.version() : 0;
    final var worker = workspace.workerFor(config.get());
    if (openFile != null) {
      touchAnalysisCache(openFile.uri());
      return worker.methodImplementations(uri, content, version, target, candidateBinaryNames);
    }

    return worker.methodImplementationsTransient(uri, content, target, candidateBinaryNames);
  }

  CompletableFuture<List<CallHierarchyOutgoingCall>> outgoingCallsFuture(
      final CallHierarchyItem item) {
    final CallHierarchyItemData data = CallHierarchyItemDataCodec.decode(item.getData());
    if (data == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    final var t = Stopwatch.start();
    return openDocFeature(
            data.routingUri(),
            List.of(),
            (worker, doc) ->
                worker.outgoingCalls(
                    item, doc.uri(), doc.content(), doc.version(), typeSourceDirs()))
        .thenApply(
            calls -> {
              LOG.fine(
                  () ->
                      "[callHierarchy:outgoing] %s %dms calls=%d"
                          .formatted(data.routingUri(), t.elapsedMs(), calls.size()));
              return calls;
            });
  }

  CompletableFuture<List<CallHierarchyIncomingCall>> incomingCallsFuture(
      final CallHierarchyItem item,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    final CallHierarchyItemData data = CallHierarchyItemDataCodec.decode(item.getData());
    if (data == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    final var target =
        new ReferenceTarget(
            data.kind(),
            data.ownerBinaryName(),
            data.methodName(),
            data.erasedDescriptor(),
            data.scope(),
            List.of(),
            false,
            -1);
    final var progressTitle = "Finding callers of %s".formatted(target.simpleName());

    final var declaringPath = LatheUri.toPath(data.routingUri());
    final var declaringConfig = workspace.moduleSourceFor(declaringPath);

    if (target.scope() == ReferenceTarget.SearchScope.DECLARING_FILE) {
      final Optional<CompilationWorker> routedWorker =
          routeCompiler(data.routingUri()).analysisWorker();
      if (routedWorker.isEmpty()) {
        return CompletableFuture.completedFuture(List.of());
      }

      final CompilationWorker worker = routedWorker.get();
      progress.begin(progressTitle, 1);
      final OpenDocument declaringDoc = docs.get(data.routingUri());
      if (declaringDoc != null) {
        touchAnalysisCache(declaringDoc.uri());
        return worker
            .searchIncomingCalls(
                declaringDoc.uri(),
                declaringDoc.content(),
                declaringDoc.version(),
                target,
                cancelChecker)
            .whenComplete(
                (calls, failure) -> {
                  if (failure == null) {
                    progress.advance(false, calls.size());
                  }
                });
      }
      return readDiskCandidate(data.routingUri())
          .map(
              d ->
                  worker
                      .searchIncomingCallsTransient(d.uri(), d.content(), target, cancelChecker)
                      .whenComplete(
                          (calls, failure) -> {
                            if (failure == null) {
                              progress.advance(true, calls.size());
                            }
                          }))
          .orElseGet(() -> CompletableFuture.completedFuture(List.of()));
    }

    final Path packageRel =
        target.scope() == ReferenceTarget.SearchScope.DECLARING_MODULE
            ? declaringPackageRel(declaringPath, declaringConfig.orElse(null))
            : null;

    final List<ModuleSourceConfig> configs = planSearchScope(target, declaringConfig.orElse(null));

    final var t = Stopwatch.start();
    final List<CompletableFuture<List<CallHierarchyIncomingCall>>> searches =
        configs.stream()
            .flatMap(
                config -> incomingCallFutures(config, target, packageRel, cancelChecker, progress))
            .toList();
    progress.begin(progressTitle, searches.size());
    return joinCandidateResults(searches, cancelChecker)
        .thenApply(
            calls -> {
              LOG.fine(
                  () ->
                      "[callHierarchy:incoming] %s %dms calls=%d"
                          .formatted(data.routingUri(), t.elapsedMs(), calls.size()));
              return calls;
            });
  }

  private Stream<CompletableFuture<List<CallHierarchyIncomingCall>>> incomingCallFutures(
      final ModuleSourceConfig config,
      final ReferenceTarget target,
      final Path packageRel,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    final var worker = workspace.workerFor(config);
    final var inputs = planSearchInputs(config, target, packageRel);
    return Stream.concat(
        inputs.openDocuments().stream()
            .map(
                doc -> {
                  cancelChecker.checkCanceled();
                  touchAnalysisCache(doc.uri());
                  return worker
                      .searchIncomingCalls(
                          doc.uri(), doc.content(), doc.version(), target, cancelChecker)
                      .whenComplete(
                          (calls, failure) -> {
                            if (failure == null) {
                              progress.advance(false, calls.size());
                            }
                          });
                }),
        inputs.diskCandidates().stream()
            .map(
                d -> {
                  cancelChecker.checkCanceled();
                  return worker
                      .searchIncomingCallsTransient(d.uri(), d.content(), target, cancelChecker)
                      .whenComplete(
                          (calls, failure) -> {
                            if (failure == null) {
                              progress.advance(true, calls.size());
                            }
                          });
                }));
  }

  CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchyFuture(
      final String uri, final Position pos) {
    final var t = Stopwatch.start();
    return openDocFeature(
            uri,
            List.of(),
            (worker, doc) -> {
              final var request = featureRequest(doc, pos);
              return worker.prepareCallHierarchy(request);
            })
        .thenApply(
            items -> {
              LOG.fine(
                  () ->
                      "[callHierarchy:prepare] %s %dms items=%d"
                          .formatted(uri, t.elapsedMs(), items.size()));
              return items;
            });
  }

  CompletableFuture<List<TypeHierarchyItem>> prepareTypeHierarchyFuture(
      final String uri, final Position pos) {
    final var indexSnapshot = typeIndex;
    final var t = Stopwatch.start();
    return openDocFeature(
            uri,
            List.of(),
            (worker, doc) -> {
              final var request = featureRequest(doc, pos);
              return worker.prepareTypeHierarchy(request, indexSnapshot);
            })
        .thenApply(
            items -> {
              LOG.fine(
                  () ->
                      "[typeHierarchy:prepare] %s %dms items=%d"
                          .formatted(uri, t.elapsedMs(), items.size()));
              return items;
            });
  }

  CompletableFuture<List<TypeHierarchyItem>> typeHierarchySupertypesFuture(
      final TypeHierarchyItem item) {
    final var data = TypeHierarchyItemDataCodec.decode(item.getData());
    if (data == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    final var indexSnapshot = typeIndex;
    final List<Path> sourceDirs = typeSourceDirs();
    final var t = Stopwatch.start();
    return routeFeature(
            data.routingUri(),
            worker -> worker.typeHierarchySupertypes(item, indexSnapshot, sourceDirs),
            List.of())
        .thenApply(
            items -> {
              LOG.fine(
                  () ->
                      "[typeHierarchy:supertypes] %s %dms items=%d"
                          .formatted(data.binaryName(), t.elapsedMs(), items.size()));
              return items;
            });
  }

  CompletableFuture<List<TypeHierarchyItem>> typeHierarchySubtypesFuture(
      final TypeHierarchyItem item) {
    final var data = TypeHierarchyItemDataCodec.decode(item.getData());
    if (data == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    final var indexSnapshot = typeIndex;
    final List<Path> sourceDirs = typeSourceDirs();
    final var t = Stopwatch.start();
    return routeFeature(
            data.routingUri(),
            worker -> worker.typeHierarchySubtypes(item, indexSnapshot, sourceDirs),
            List.of())
        .thenApply(
            items -> {
              LOG.fine(
                  () ->
                      "[typeHierarchy:subtypes] %s %dms items=%d"
                          .formatted(data.binaryName(), t.elapsedMs(), items.size()));
              return items;
            });
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
              final var indexSnapshot = typeIndex;
              final List<Path> sourceDirs = typeSourceDirs();
              return routeFeature(
                  data.routingUri(),
                  worker ->
                      worker.typeHierarchyExplore(
                          self, indexSnapshot, sourceDirs, TYPE_HIERARCHY_NODE_CAP),
                  TypeHierarchyExplorerResult.empty());
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
    final var indexSnapshot = typeIndex;
    final var route = routeCompiler(uri);
    final LearnedStaticImports staticImports = learnedStaticImports(route);
    return openDocFeature(
        uri,
        CompletionOutcome.of(List.of()),
        (worker, doc) ->
            worker
                .complete(
                    uri,
                    doc.content(),
                    doc.version(),
                    pos,
                    context,
                    indexSnapshot,
                    completionModuleNames(route, uri),
                    staticImports)
                .exceptionally(
                    ex ->
                        logAndReturn(
                            ex,
                            "[completion] failed for %s".formatted(uri),
                            CompletionOutcome.of(List.of()))));
  }

  CompletableFuture<List<Either<Command, CodeAction>>> codeActionFuture(
      final String uri, final Range range, final CodeActionContext context) {
    final OpenDocument openFile = docs.get(uri);
    if (openFile == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    if (routeCompiler(uri) instanceof CompilerRoute.External) {
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
    final var baseIndex = typeIndex;
    final LearnedStaticImports staticImports = learnedStaticImports(routeCompiler(uri));
    return routeFeature(
        uri,
        moduleWorker ->
            moduleWorker
                .cachedTypeEntries(neededTypeNames)
                .thenCompose(
                    openEntries -> {
                      final var enriched = buildEnrichedIndex(baseIndex, openEntries);
                      return moduleWorker.codeAction(
                          uri,
                          openFile.content(),
                          openFile.version(),
                          range,
                          requests,
                          enriched,
                          staticImports);
                    })
                .exceptionally(
                    ex -> logAndReturn(ex, "[codeAction] failed for %s".formatted(uri), List.of())),
        List.of());
  }

  CompletableFuture<MissingImportsResult> missingImportsFuture(final String uri) {
    final OpenDocument openFile = docs.get(uri);
    if (openFile == null || routeCompiler(uri) instanceof CompilerRoute.External) {
      return CompletableFuture.completedFuture(MissingImportsResult.empty());
    }

    final var indexSnapshot = typeIndex;
    final LearnedStaticImports staticImports = learnedStaticImports(routeCompiler(uri));
    return routeFeature(
        uri,
        moduleWorker ->
            moduleWorker
                .missingImports(
                    uri, openFile.content(), openFile.version(), indexSnapshot, staticImports)
                .exceptionally(
                    ex ->
                        logAndReturn(
                            ex,
                            "[missingImports] failed for %s".formatted(uri),
                            MissingImportsResult.empty())),
        MissingImportsResult.empty());
  }

  private WorkspaceTypeIndex buildEnrichedIndex(
      final WorkspaceTypeIndex base, final List<TypeIndexEntry> openFileEntries) {
    if (openFileEntries.isEmpty()) {
      return base;
    }

    final List<List<TypeIndexEntry>> allReactor =
        Stream.concat(reactorShards.values().stream(), Stream.of(openFileEntries)).toList();
    return base.withReactorEntries(allReactor);
  }

  private static CodeActionRequest toCodeActionRequest(final String uri, final Diagnostic diag) {
    final DiagnosticPayload payload = DiagnosticPayloadCodec.extractPayload(diag.getData());
    return payload != null ? new CodeActionRequest(uri, diag, payload) : null;
  }

  CompletableFuture<SemanticTokens> semanticTokensFuture(final String uri) {
    return openDocFeature(
        uri,
        null,
        (worker, doc) ->
            worker
                .semanticTokens(uri, doc.content(), doc.version())
                .thenApply(WorkspaceSession::encodeTokensOrNull)
                .exceptionally(
                    ex -> logAndReturn(ex, "[semanticTokens] failed for %s".formatted(uri), null)));
  }

  CompletableFuture<List<DocumentSymbol>> documentSymbolFuture(final String uri) {
    return openDocFeature(
        uri,
        List.of(),
        (worker, doc) ->
            worker
                .documentSymbol(uri, doc.content())
                .exceptionally(
                    ex ->
                        logAndReturn(
                            ex, "[documentSymbol] failed for %s".formatted(uri), List.of())));
  }

  CompletableFuture<List<FoldingRange>> foldingRangeFuture(final String uri) {
    final OpenDocument doc = docs.get(uri);
    if (doc == null) {
      LOG.warning(() -> "[foldingRange] %s no folds document not open".formatted(uri));
      return CompletableFuture.completedFuture(List.of());
    }

    final Optional<CompilationWorker> worker = routeCompiler(uri).analysisWorker();
    if (worker.isEmpty()) {
      LOG.fine(() -> "[foldingRange] %s skipped route=missing ranges=0".formatted(uri));
      return CompletableFuture.completedFuture(List.of());
    }

    return foldWith(worker.get(), uri, doc);
  }

  private CompletableFuture<List<FoldingRange>> foldWith(
      final CompilationWorker worker, final String uri, final OpenDocument doc) {
    touchAnalysisCache(uri);
    return worker
        .foldingRange(uri, doc.content())
        .exceptionally(
            ex -> logAndReturn(ex, "[foldingRange] failed for %s".formatted(uri), List.of()));
  }

  List<? extends TextEdit> format(final String uri, final FormatEngine engine) {
    return formatDocument(
        uri, content -> JavaFormatter.format(engine, content, LatheUri.toPath(uri)));
  }

  List<? extends TextEdit> formatRanges(
      final String uri, final List<Range> ranges, final FormatEngine engine) {
    return formatDocument(uri, content -> JavaFormatter.formatRanges(engine, content, ranges));
  }

  // For the agent, which must tell a failure from an already formatted file: a refusal that
  // carries the formatter's reason.
  List<TextEdit> formatOrRefuse(final String uri, final FormatEngine engine) {
    try {
      return formatted(uri, content -> JavaFormatter.format(engine, content, LatheUri.toPath(uri)));
    } catch (final Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }

      throw new ResponseErrorException(
          new ResponseError(
              ResponseErrorCode.RequestFailed,
              "Could not format %s: %s"
                  .formatted(
                      LatheUri.toPath(uri).getFileName(), ExceptionUtil.rootCause(e).getMessage()),
              null));
    }
  }

  // Formats the open document's text, reporting the outcome once and swallowing failures.
  private List<? extends TextEdit> formatDocument(final String uri, final DocumentFormat format) {
    final var t = Stopwatch.start();
    try {
      final List<TextEdit> result = formatted(uri, format);
      // Formatting is a synchronous request (format-on-save blocks the editor), so a progress
      // spinner would only paint after the edit; report the outcome once it is done instead. Stay
      // quiet when nothing changed, so saving an already-formatted file does not nag.
      if (!result.isEmpty()) {
        client.showMessage(
            new MessageParams(
                MessageType.Info, "Lathe: formatted in %dms".formatted(t.elapsedMs())));
      }

      return result;
    } catch (final Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }

      LOG.log(Level.SEVERE, e, () -> "[format] %s failed %dms".formatted(uri, t.elapsedMs()));
      // Format-on-save would repeat the same failure (a formatter that cannot run on this JDK, a
      // file that does not parse) on every save; say why once, then only log.
      if (formatFailureReported.compareAndSet(false, true)) {
        client.showMessage(
            new MessageParams(
                MessageType.Warning,
                "Lathe: formatting failed: %s (further failures are only logged)"
                    .formatted(ExceptionUtil.rootCause(e).getMessage())));
      }

      return List.of();
    }
  }

  private List<TextEdit> formatted(final String uri, final DocumentFormat format) throws Exception {
    final var t = Stopwatch.start();
    final OpenDocument openFile = docs.get(uri);
    final String content = openFile != null ? openFile.content() : null;
    final List<TextEdit> result = format.apply(content);
    LOG.info(() -> "[format] %s %dms edits=%d".formatted(uri, t.elapsedMs(), result.size()));
    if (!result.isEmpty() && renameTransaction.isMember(uri)) {
      renameTransaction.onFormat(uri, SourceLocator.applyEdits(content, result));
    }

    return result;
  }

  @FunctionalInterface
  private interface DocumentFormat {
    List<TextEdit> apply(String content) throws Exception;
  }

  List<SymbolInformation> workspaceSymbol(final String query) {
    final var t = Stopwatch.start();
    final List<Path> sourceDirs = typeSourceDirs();
    final List<SymbolInformation> results =
        WorkspaceSymbolResolver.resolve(query, typeIndex, sourceDirs);
    LOG.info(
        () -> "[symbol] query=%s hits=%d %dms".formatted(query, results.size(), t.elapsedMs()));
    return results;
  }

  private List<Path> typeSourceDirs() {
    return Stream.of(
            workspace.allSourceRoots().stream(),
            manifest.jdkModuleSourceDirs().stream(),
            manifest.depSourceDirs().stream())
        .flatMap(stream -> stream)
        .toList();
  }

  private void scanReactorShards() {
    final var counts = new HashMap<String, Integer>();
    for (final var config : workspace.allConfigs()) {
      final ClassFileTypeScanner.ReactorScan scan = scanReactorDir(config);
      reactorBaseShards.put(config, scan.entries());
      reactorShards.put(config, withSourceDelta(config, scan.entries()));
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
            Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (left, right) -> Stream.concat(left.stream(), right.stream()).toList()));
  }

  private void refreshReactorShard(final ModuleSourceConfig config) {
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
    reactorShards.put(config, withSourceDelta(config, base));
    reactorIndexStale = true;
  }

  // Built off the session thread, as a rebuild takes 100-200ms on a large reactor and would hold
  // back the publishes queued behind it; applied only if the index is still the one it was built
  // from and no newer build started.
  private void rebuildReactorIndex() {
    reactorIndexStale = false;
    reactorLambdaImplementors = mergeLambdaShards();
    final WorkspaceTypeIndex base = typeIndex;
    final List<List<TypeIndexEntry>> shards = List.copyOf(reactorShards.values());
    final long build = ++reactorIndexBuilds;
    final var rebuilt = new CompletableFuture<Void>();
    reactorIndexRebuilt = rebuilt;
    CompletableFuture.supplyAsync(() -> base.withReactorEntries(shards))
        .whenComplete(
            (index, error) ->
                worker.execute(
                    () -> {
                      if (error != null) {
                        LOG.log(Level.SEVERE, error, () -> "[type-index] reactor rebuild failed");
                      } else if (typeIndex == base && build == reactorIndexBuilds) {
                        typeIndex = index;
                      }

                      rebuilt.complete(null);
                    }));
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
            .map(file -> SourceTypeScanner.deriveEntry(sourceRootFor(config, file), file))
            .flatMap(Optional::stream)
            .filter(entry -> !known.contains(entry.binaryName()))
            .toList();
    return delta.isEmpty() ? base : Stream.concat(base.stream(), delta.stream()).toList();
  }

  // Files the bytecode base may not cover: on-disk sources stale vs their compile stamp, plus open
  // buffers, restricted to this module's source roots.
  private List<Path> deltaSourceFiles(final ModuleSourceConfig config) {
    return Stream.concat(TreeState.read(config).stale().stream(), openSourcePaths().stream())
        .filter(path -> sourceRootFor(config, path) != null)
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

  // Test/tool seam: request a pass and complete once its writes finish. Must be invoked on the
  // worker thread. eager mirrors the startup pass (no two-tick wait).
  CompletableFuture<Void> reconcileNow(final boolean eager) {
    return requestPass(eager).thenAccept(stale -> {});
  }

  // Asks for a write pass; completes with the stale sources it considered once its writes finish.
  // eager treats every stale source as ready (startup, an MCP query) instead of waiting a tick for
  // a file that may still be being written. Runs on the worker, which owns all pass state; the pass
  // is registered as running before any of its work, so one that finishes at once still ends
  // cleanly.
  private CompletableFuture<List<Path>> requestPass(final boolean eager) {
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
    try {
      runPass(eager, pass);
    } catch (final RuntimeException e) {
      LOG.log(Level.SEVERE, e, () -> "[reconcile] pass failed");
      pass.completeExceptionally(e); // never leave a pass running, or every later request waits
    }

    return pass;
  }

  // One pass: drop the classes of deleted sources, claim the stale sources that are ready, copy
  // changed resources, then write the claimed sources level by level, upstream first. While a
  // build holds the reactor lock only reported files are written; external catch-up waits for it.
  // The open files in and downstream of what it wrote are re-analyzed once it ends -- or after the
  // next pass, if one was requested meanwhile.
  private void runPass(final boolean eager, final CompletableFuture<List<Path>> done) {
    final boolean building = reactorBuildInProgress();
    final var scanned = Stopwatch.start();
    final List<TreeState> trees = workspace.allConfigs().stream().map(TreeState::read).toList();
    final Map<Path, Long> sourceMtimes =
        trees.stream()
            .flatMap(tree -> tree.sourcesByRoot().values().stream())
            .flatMap(sources -> sources.entrySet().stream())
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, Map.Entry::getValue, (first, ignored) -> first));
    trees.forEach(this::reconcileDeletedSourcesIn);
    // Not under a build: it rewrites the stamps itself, and a touched source is current meanwhile.
    if (!building) {
      trees.forEach(tree -> recordStamps(tree.config(), tree.touched()));
    }

    final StaleScan scan = withReported(staleModules(trees));
    final long sourcesMs = scanned.elapsedMs();
    // By mtime, not stamp: the MCP engine stamps the files it writes, so an editor's server on the
    // same workspace never finds them stale.
    final List<Path> reread =
        candidateIndex.syncFromDisk(
            sourceMtimes, uri -> !documentsFollowDisk && docs.get(uri) != null);
    if (documentsFollowDisk) {
      reread.forEach(this::reloadFromDisk);
    }
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
                    workspace
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
      refreshOpenFiles(scope);
    }

    if (reactorIndexStale) {
      rebuildReactorIndex();
    }
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
    final Set<Path> held = renameTransaction.memberPaths();
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
          final String original = open != null ? open.content() : readSource(LatheUri.toPath(uri));
          if (original != null) {
            tx.add(
                moves.getOrDefault(uri, uri), original, SourceLocator.applyEdits(original, edits));
          }
        });
    moves.forEach(tx::addMovedAway);
    LOG.fine(() -> "[rename-tx] begin members=%d".formatted(tx.size()));
    worker.schedule(RENAME_UNAPPLIED_KEY, RENAME_UNAPPLIED_MS, this::dropUnappliedRename);
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
      worker.schedule(RENAME_COMMIT_KEY, RENAME_IDLE_MS, this::commitRenameTransaction);
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
    worker.cancel(RENAME_COMMIT_KEY);
    worker.cancel(RENAME_UNAPPLIED_KEY);
    final Set<Path> saved = tx.savedPaths();
    final Set<String> unsaved = tx.unsavedUris();
    LOG.info(
        () -> "[rename-tx] commit saved=%d unsaved=%d".formatted(saved.size(), unsaved.size()));
    writeReported(saved);
    // The commit's dependents refresh recompiles open files downstream of what it compiled.
    final Set<Path> refreshed =
        saved.stream()
            .flatMap(path -> workspace.moduleSourceFor(path).stream())
            .flatMap(config -> moduleGraph.downstreamModuleDirs(config.moduleDir()).stream())
            .collect(Collectors.toUnmodifiableSet());
    unsaved.stream()
        .filter(uri -> docs.get(uri) != null && !inDownstreamScope(uri, refreshed))
        .forEach(this::scheduleOpenFile);
  }

  // Files the editor saved or the MCP engine wrote: written by a pass, the next one if a pass is
  // running. The engine calls this holding the reactor lock, which defers everything but these.
  CompletableFuture<Void> writeReported(final Set<Path> changed) {
    changed.forEach(source -> reported.put(source, ++reportCount));
    writtenSinceVerify.addAll(changed);
    return requestPass(false).thenAccept(stale -> {});
  }

  // The engine's freshen step, before every MCP query and verify_change: the watcher's check, so
  // a build file the agent just edited is seen now rather than on the next tick, then an eager
  // pass. If its result can't be trusted (a build-file sync pending, or a running build) it
  // carries a DeferReason so verify_change falls back to the build. The outcome lists every stale
  // source, including one that failed unchanged since and so was not recompiled. Runs on the
  // worker.
  CompletableFuture<ReconcileOutcome> reconcileForVerify() {
    checkForChanges();
    if (reactorBuildInProgress()) {
      return CompletableFuture.completedFuture(
          new ReconcileOutcome(List.of(), ReconcileOutcome.DeferReason.BUILD_IN_PROGRESS));
    }

    final ReconcileOutcome.DeferReason deferral =
        buildFilesNotificationPending
            ? ReconcileOutcome.DeferReason.BUILD_FILES_PENDING
            : ReconcileOutcome.DeferReason.NONE;
    // On the worker after the pass's own completion chain, which starts the index rebuild.
    return requestPass(true)
        .thenComposeAsync(
            stale ->
                reactorIndexRebuilt.thenApply(rebuilt -> new ReconcileOutcome(stale, deferral)),
            worker::execute);
  }

  List<Path> takeWrittenSinceVerify() {
    final List<Path> taken = List.copyOf(writtenSinceVerify);
    writtenSinceVerify.clear();
    return taken;
  }

  // The changed files whose content is not what the last build compiled: a change put back is no
  // change, so verify_change neither checks it nor asks for a sync. A deleted file stays, as its
  // callers break. Each module's stamps are read once.
  List<Path> differingFromBuild(final List<Path> changed) {
    final Map<ModuleSourceConfig, Map<Path, ContentFingerprint>> builtByTree = new HashMap<>();
    final List<Path> differing = new ArrayList<>();
    for (final Path file : changed) {
      if (!backToBuilt(file, builtByTree)) {
        differing.add(file);
      }
    }

    return List.copyOf(differing);
  }

  // A deleted file is back to the build only if the build never compiled it either (one created and
  // deleted since); a tree with no built record (OpenJDK, or stamps from before) cannot tell.
  private boolean backToBuilt(
      final Path file, final Map<ModuleSourceConfig, Map<Path, ContentFingerprint>> builtByTree) {
    final Optional<ModuleSourceConfig> config = workspace.moduleSourceFor(file);
    final Path root = config.map(tree -> sourceRootFor(tree, file)).orElse(null);
    if (root == null) {
      return false;
    }

    final Map<Path, ContentFingerprint> built =
        builtByTree.computeIfAbsent(
            config.get(), tree -> CompiledStamps.loadBuilt(tree.moduleDir(), tree.sourceTree()));
    if (built.isEmpty()) {
      return false;
    }

    final ContentFingerprint compiled = built.get(root.relativize(file));
    return Files.notExists(file) ? compiled == null : FileUtil.hasContent(file, compiled);
  }

  // Files to diagnose for verify_change, by module: each changed file, then the files that mention
  // its type in its module or one downstream of it, the changed files' modules first.
  Map<String, List<Path>> verifyTargetsByModule(final List<Path> changed) {
    final Map<String, Set<Path>> byModule = new LinkedHashMap<>();
    changed.forEach(file -> addVerifyTarget(byModule, file));
    changed.forEach(file -> referrers(file).forEach(path -> addVerifyTarget(byModule, path)));
    return Collections.unmodifiableMap(
        byModule.entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> List.copyOf(entry.getValue()),
                    (first, ignored) -> first,
                    LinkedHashMap::new)));
  }

  // A deleted file has nothing to diagnose, but its callers still do.
  private void addVerifyTarget(final Map<String, Set<Path>> byModule, final Path file) {
    if (Files.notExists(file)) {
      return;
    }

    moduleDirOf(file)
        .ifPresent(
            dir ->
                byModule
                    .computeIfAbsent(moduleRelForDir(dir), k -> new LinkedHashSet<>())
                    .add(file));
  }

  // Matched by type-name token: may over-select (a file that only mentions the name) but never
  // misses a direct caller; an extra candidate just compiles clean.
  private List<Path> referrers(final Path file) {
    final Set<Path> reach =
        moduleDirOf(file).map(moduleGraph::downstreamModuleDirs).orElse(Set.of());
    return candidateIndex.candidateUris(FileUtil.javaTypeName(file)).stream()
        .map(LatheUri::toPath)
        .filter(path -> !path.equals(file))
        .filter(path -> moduleDirOf(path).filter(reach::contains).isPresent())
        .toList();
  }

  private Optional<Path> moduleDirOf(final Path file) {
    return workspace.moduleSourceFor(file).map(ModuleSourceConfig::moduleDir);
  }

  // Placement (module rel + test/production) for each path that maps to a reactor module. Lets
  // analyze_change split references by module and into production vs test.
  Map<Path, ModulePlacement> classifyPaths(final List<Path> paths) {
    final Map<Path, ModulePlacement> placements = new LinkedHashMap<>();
    for (final Path path : paths) {
      workspace
          .moduleSourceFor(path)
          .ifPresent(
              config ->
                  placements.put(
                      path,
                      new ModulePlacement(
                          moduleRelForDir(config.moduleDir()), config.isTestTree())));
    }

    return Map.copyOf(placements);
  }

  // The reactor remainder a cross-module change may break: modules that transitively depend on the
  // changed ones. verify_change hands these to `mvn`, not recompile (codegen/AP are Maven's job).
  int reactorModuleCount() {
    return (int)
        workspace.allConfigs().stream().map(ModuleSourceConfig::moduleDir).distinct().count();
  }

  List<LatheModuleSummary> moduleSummaries() {
    return moduleSummaries(workspace.allConfigs(), workspaceRoot, moduleGraph);
  }

  static List<LatheModuleSummary> moduleSummaries(
      final List<ModuleSourceConfig> configs,
      final Path workspaceRoot,
      final WorkspaceModuleGraph graph) {
    final var launches = new LaunchReader(workspaceRoot);
    return configs.stream()
        .collect(Collectors.groupingBy(ModuleSourceConfig::moduleDir))
        .entrySet()
        .stream()
        .map(
            module -> {
              final String rel = moduleRel(workspaceRoot, module.getKey());
              return new LatheModuleSummary(
                  rel,
                  graph.upstreamModuleDirs(module.getKey()).stream()
                      .map(dir -> moduleRel(workspaceRoot, dir))
                      .sorted()
                      .toList(),
                  module.getValue().stream().anyMatch(ModuleSourceConfig::isTestTree),
                  launches.testCaptured(rel));
            })
        .sorted(Comparator.comparing(LatheModuleSummary::module))
        .toList();
  }

  List<String> downstreamModuleRels(final List<Path> changed) {
    final Set<Path> changedModuleDirs =
        changed.stream()
            .map(this::moduleDirOf)
            .flatMap(Optional::stream)
            .collect(Collectors.toUnmodifiableSet());
    return changedModuleDirs.stream()
        .flatMap(dir -> moduleGraph.downstreamModuleDirs(dir).stream())
        .filter(dir -> !changedModuleDirs.contains(dir))
        .distinct()
        .map(this::moduleRelForDir)
        .sorted()
        .toList();
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
    refreshReactorShard(config);
    refreshScope.addAll(moduleGraph.downstreamModuleDirs(config.moduleDir()));
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
                source -> source, WorkspaceSession::mtimeMillis, (first, ignored) -> first));
  }

  private static boolean failedUnchanged(final FailedWrite failed, final long mtime) {
    return failed != null && failed.mtime() == mtime;
  }

  // "line: message" of the source's first error in the write, for the MCP "Not compiled" note.
  private static String firstError(final BatchWrite written, final Path source) {
    return written.diagnosticsByUri().getOrDefault(source.toUri().toString(), List.of()).stream()
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

  // What an MCP answer cannot vouch for after its pass: files whose write failed and that are
  // unchanged since (fixed in code, retried on their next edit), and what only a sync can fix.
  LatheNotes notes(final List<Path> files) {
    final List<String> notCompiled =
        failedCompiles.entrySet().stream()
            .filter(entry -> failedUnchanged(entry.getValue(), mtimeMillis(entry.getKey())))
            .map(
                entry ->
                    "%s:%s"
                        .formatted(
                            workspaceRoot.relativize(entry.getKey()), entry.getValue().error()))
            .sorted()
            .toList();
    final Stream<String> pendingBuildFiles =
        buildFilesNotificationPending
            ? Stream.of("build files changed — run `%s`".formatted(LatheLayout.SYNC_SCRIPT_COMMAND))
            : Stream.empty();
    final List<String> notChecked =
        files.stream().filter(this::uncheckable).map(this::notCheckedReason).toList();
    return new LatheNotes(
        notCompiled,
        Stream.concat(pendingBuildFiles, manifest.loadProblem().stream()).toList(),
        notChecked,
        reactorBuildInProgress());
  }

  // No module compiles it, or it names a file the build never had: a mistyped path, not a deletion.
  private boolean uncheckable(final Path file) {
    return routeCompiler(file.toUri().toString()) instanceof CompilerRoute.Missing
        || (Files.notExists(file) && backToBuilt(file, new HashMap<>()));
  }

  // A sync gives a module only to a file of the modules the build compiles.
  private String notCheckedReason(final Path file) {
    final Path relative = workspaceRoot.relativize(file);
    if (Files.notExists(file)) {
      return "%s does not exist, and the last build did not compile it".formatted(relative);
    }

    return manifest.inBuiltModule(file)
        ? "%s is in no synced module — run `%s`"
            .formatted(relative, LatheLayout.SYNC_SCRIPT_COMMAND)
        : "%s is outside the modules the last build compiled, so Lathe cannot check it"
            .formatted(relative);
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
  // stays stale for the next pass. Completes on the session thread.
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
    return moduleGraph.upstreamFirst(byModule.keySet()).stream()
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
      final String content = readSource(source);
      if (content == null) {
        reported.remove(source);
        continue;
      }

      transientSources.add(new TransientSource(source.toUri().toString(), content));
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
                  moduleRelForDir(config.moduleDir()),
                  names,
                  stamps.size() > 20 ? " +%d more".formatted(stamps.size() - 20) : "");
        });

    // Reported again after this read, a file stays reported for the next pass.
    final Map<Path, Long> reports =
        stamps.keySet().stream()
            .filter(reported::containsKey)
            .collect(Collectors.toUnmodifiableMap(source -> source, reported::get));
    return workspace
        .workerFor(config)
        .compileBatch(List.copyOf(transientSources))
        .handleAsync(
            (written, error) -> {
              reports.forEach(reported::remove);
              if (written != null) {
                keepWriteDiagnostics(transientSources, written);
              }

              return afterChangedBatch(config, Map.copyOf(stamps), written, error, failedFirst);
            },
            worker::execute)
        .thenCompose(next -> next);
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
          () ->
              "[react] batch compile failed in %s".formatted(moduleRelForDir(config.moduleDir())));
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
                      source -> written.failedUris().contains(source.toUri().toString())));
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
                      byFailure.get(true).size(),
                      stamps.size(),
                      moduleRelForDir(config.moduleDir())));
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
    refreshScope.addAll(moduleGraph.downstreamModuleDirs(config.moduleDir()));
    refreshReactorShard(config);
    LOG.fine(
        () ->
            "[react] recompiled %d file(s) in %s"
                .formatted(stamps.size(), moduleRelForDir(config.moduleDir())));
    return CompletableFuture.completedFuture(null);
  }

  // A write that produced classes may supply what earlier failures here or downstream were missing,
  // so they get another try; the files that failed in this very write (failedFirst) keep their
  // record. A retry that failed too wrote nothing, so it must not reopen them -- or every pass
  // would retry them and fail again.
  private void retryFailuresDownstream(
      final ModuleSourceConfig config, final Set<Path> failedFirst) {
    final Set<Path> downstream = moduleGraph.downstreamModuleDirs(config.moduleDir());
    failedCompiles
        .keySet()
        .removeIf(
            failed ->
                !failedFirst.contains(failed)
                    && workspace
                        .moduleSourceFor(failed)
                        .map(failedConfig -> downstream.contains(failedConfig.moduleDir()))
                        .orElse(true));
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
    final var root = sourceRootFor(config, source);
    // No type to key on (package-info/module-info or no source root) -- do not block.
    if (root == null || SourceTypeScanner.deriveEntry(root, source).isEmpty()) {
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
    final var sourceRoot = sourceRootFor(config, source);
    if (sourceRoot == null) {
      return null;
    }

    final var packageRel = sourceRoot.relativize(source).getParent();
    return packageRel != null
        ? config.latheClassesDir().resolve(packageRel)
        : config.latheClassesDir();
  }

  private static String readSource(final Path source) {
    try {
      return Files.readString(source);
    } catch (final IOException e) {
      return null;
    }
  }

  // Copy any resource whose .lathe/ destination is missing or older than the source, so an external
  // resource edit is picked up without Maven; the copy bumps the destination mtime, so the next
  // pass sees it as fresh. Remove the copy of a resource deleted since the last pass. Returns how
  // many resources were checked.
  private int reconcileResources() {
    final Map<Path, Long> resources =
        manifest.resourceSourceDirs().stream()
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
        file -> manifest.resourceDestination(file).ifPresent(dest -> deleteResource(file, dest)));
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
    final Optional<Path> dest = manifest.resourceDestination(file);
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
  private boolean reactorBuildInProgress() {
    return LatheLock.isBuilding(
        LatheLayout.reactorLockFile(workspaceRoot.resolve(LatheLayout.LATHE_DIR)));
  }

  private Set<Path> openSourcePaths() {
    return docs.uris().stream()
        .filter(LatheUri::isFileUri)
        .map(LatheUri::toPath)
        .collect(Collectors.toUnmodifiableSet());
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

  static StaleScan staleModules(final List<TreeState> trees) {
    return new StaleScan(
        trees.stream()
            .map(tree -> Map.entry(tree.config(), tree.stale()))
            .filter(entry -> !entry.getValue().isEmpty())
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)));
  }

  // A source tree as one pass sees it: each hand-written source with its mtime, from one walk per
  // root, and the tree's stamps -- so its stale and orphaned sources follow without a further
  // file-system call (a scan repeated every few seconds; such calls are costly on macOS). Only a
  // source whose mtime moved is read, to tell a touch from a change.
  record TreeState(
      ModuleSourceConfig config,
      Map<Path, Map<Path, Long>> sourcesByRoot,
      Map<Path, SourceStamp> stamps,
      Map<Path, SourceStamp> touched) {
    TreeState {
      ValidCheck.check()
          .notNull(config, "config")
          .notNull(sourcesByRoot, "sourcesByRoot")
          .notNull(stamps, "stamps")
          .notNull(touched, "touched")
          .validate();
      sourcesByRoot = Map.copyOf(sourcesByRoot);
      stamps = Map.copyOf(stamps);
      touched = Map.copyOf(touched);
    }

    static TreeState read(final ModuleSourceConfig config) {
      final Map<Path, Map<Path, Long>> sourcesByRoot =
          config.sourceRoots().stream()
              .collect(
                  Collectors.toMap(
                      root -> root, WorkspaceSession::javaSourceMtimes, (first, ignored) -> first));
      final Map<Path, SourceStamp> stamps =
          CompiledStamps.load(config.moduleDir(), config.sourceTree());
      return new TreeState(config, sourcesByRoot, stamps, touched(sourcesByRoot, stamps));
    }

    // No stamp (never built) or an mtime other than the one compiled -- older too, as a restore
    // that keeps file times (rsync -a, cp -p, tar) brings back older content -- unless only
    // touched. Open files count like any other: an unsaved buffer is not on disk.
    List<Path> stale() {
      return moved(sourcesByRoot, stamps)
          .filter(source -> !touched.containsKey(source.rel()))
          .map(ScannedSource::file)
          .toList();
    }

    // Moved sources whose content is still what their class was compiled from (a touch, a branch
    // switch and back, a build that compiled nothing), re-stamped at their new mtime.
    private static Map<Path, SourceStamp> touched(
        final Map<Path, Map<Path, Long>> sourcesByRoot, final Map<Path, SourceStamp> stamps) {
      return moved(sourcesByRoot, stamps)
          .filter(ScannedSource::touched)
          .collect(Collectors.toUnmodifiableMap(ScannedSource::rel, ScannedSource::restamped));
    }

    private static Stream<ScannedSource> moved(
        final Map<Path, Map<Path, Long>> sourcesByRoot, final Map<Path, SourceStamp> stamps) {
      return sourcesByRoot.entrySet().stream()
          .flatMap(
              root ->
                  root.getValue().entrySet().stream()
                      .map(source -> ScannedSource.of(root.getKey(), source, stamps)))
          .filter(ScannedSource::moved);
    }

    // A source on disk with its stamp, null when it was never compiled.
    private record ScannedSource(Path file, Path rel, long mtime, SourceStamp stamp) {
      ScannedSource {
        ValidCheck.check().notNull(file, "file").notNull(rel, "rel").validate();
      }

      static ScannedSource of(
          final Path root,
          final Map.Entry<Path, Long> source,
          final Map<Path, SourceStamp> stamps) {
        final Path rel = root.relativize(source.getKey());
        return new ScannedSource(source.getKey(), rel, source.getValue(), stamps.get(rel));
      }

      boolean moved() {
        return stamp == null || stamp.mtime() != mtime;
      }

      boolean touched() {
        return stamp != null && FileUtil.hasContent(file, stamp.content());
      }

      SourceStamp restamped() {
        return new SourceStamp(mtime, stamp.content());
      }
    }

    // Stamped sources no longer on disk: their classes are left in the mirror.
    Set<Path> orphans() {
      return stamps.keySet().stream()
          .filter(
              rel ->
                  sourcesByRoot.entrySet().stream()
                      .noneMatch(root -> root.getValue().containsKey(root.getKey().resolve(rel))))
          .collect(Collectors.toUnmodifiableSet());
    }
  }

  private static Map<Path, Long> javaSourceMtimes(final Path root) {
    try {
      return FileUtil.fileMtimes(root, FileUtil::isJavaFile);
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[watcher] source scan failed under %s".formatted(root));
      return Map.of();
    }
  }

  private static long mtimeMillis(final Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (final IOException e) {
      return 0L;
    }
  }

  private static Path sourceRootFor(final ModuleSourceConfig config, final Path file) {
    return config.sourceRoots().stream()
        .filter(file::startsWith)
        .max(Comparator.comparingInt(Path::getNameCount))
        .orElse(null);
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

  // Once a rename's first file is saved, this long without another of its files' events commits
  // the saved ones, so an editor that never saves the rest cannot hold the others back.
  private static final long RENAME_IDLE_MS = 10_000;
  private static final String RENAME_COMMIT_KEY = "rename-commit";
  // A rename none of whose files shows the edit this long after the response was never applied.
  private static final long RENAME_UNAPPLIED_MS = 30_000;
  private static final String RENAME_UNAPPLIED_KEY = "rename-unapplied";
  private static final String SYNC_ACTION = "Sync";
  private static final String SYNC_CAPTURE_ACTION = "Sync + capture tests";
  private static final String LATER_ACTION = "Later";

  private void checkForChanges() {
    if (watcher == null) {
      return;
    }

    // Defer every reactive branch (staleness prompt, build-file/workspace reload) while a build
    // runs, so a
    // mid-build workspace.json rewrite cannot re-arm the prompt. The first poll after the lock
    // clears picks up the settled state in one reload.
    if (reactorBuildInProgress()) {
      LOG.fine(() -> "[watcher] skipped — reactor build in progress");
      return;
    }

    switch (watcher.poll()) {
      case WORKSPACE_CHANGED -> {
        reload();
        pendingStale = Map.of();
        LOG.fine(() -> "[reload] pending-stale reset");
      }
      case REACTOR_REFRESH -> {
        refreshReactorTypeIndex();
        pendingStale = Map.of();
      }
      case BUILD_FILES_CHANGED -> {
        promptForSync("Build files changed. Lathe will run a full refresh.", List.of());
        requestPass(false);
      }
      // A prompt already shown stays (answering it still syncs), but MCP stops asking for a sync
      // and the next change prompts again.
      case BUILD_FILES_RESTORED -> {
        buildFilesNotificationPending = false;
        pendingSyncModules = List.of();
        requestPass(false);
      }
      // Every tick: a pass with nothing stale only scans, about 25 ms on a 377-tree reactor.
      case NO_CHANGE -> requestPass(false);
    }
  }

  private void promptForSync(final String message, final List<String> scope) {
    if (buildFilesNotificationPending) {
      LOG.fine(() -> "[sync] prompt suppressed — already pending");
      return;
    }

    pendingSyncModules = scope;
    buildFilesNotificationPending = true;
    final var request =
        new ShowMessageRequestParams(
            List.of(
                new MessageActionItem(SYNC_ACTION),
                new MessageActionItem(SYNC_CAPTURE_ACTION),
                new MessageActionItem(LATER_ACTION)));
    request.setMessage(message);
    request.setType(MessageType.Warning);
    client
        .showMessageRequest(request)
        .thenAccept(action -> worker.execute(() -> onSyncPromptResponse(action)));
  }

  // Sync / Sync + capture ask the client to run Maven (the server never does); Later just
  // dismisses. The watcher reports each build-file change once, so either way it stays quiet until
  // the files change again.
  private void onSyncPromptResponse(final MessageActionItem action) {
    buildFilesNotificationPending = false;
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
        .sync(new LatheSyncParams(workspaceRoot.toString(), captureTests, pendingSyncModules));
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
    final var newManifest = WorkspaceManifest.load(workspaceRoot);
    if (watcher != null) {
      watcher.updateBuildFiles(newManifest.buildFiles(), newManifest.buildFileFingerprints());
      buildFilesNotificationPending = false;
    }

    final var newWorkspace = WorkspaceModuleRegistry.scan(workspaceRoot, newManifest);
    final var old = workspace;
    passGeneration++; // the configs and workers a running pass writes through are being replaced
    workspace = newWorkspace;
    manifest = newManifest;
    moduleGraph = WorkspaceModuleGraph.build(workspace.allConfigs());
    candidateIndex = ReferenceCandidateIndex.build(workspace.allConfigs());
    refreshReactorTypeIndex();
    refreshOpenDocuments();
    old.close();
    scheduleAllOpenFiles();
    showWorkspaceStatus("Lathe: workspace reloaded.");
  }

  // A workspace.json from another schema, or one that cannot be read, loads as empty, so say why
  // instead of "ready".
  private void showWorkspaceStatus(final String okText) {
    client.showMessage(
        manifest
            .loadProblem()
            .map(text -> new MessageParams(MessageType.Warning, text))
            .orElseGet(() -> new MessageParams(MessageType.Info, okText)));
  }

  // Re-scans the reactor mirror and rebuilds the type index without the full-reload work (no
  // candidate-index rebuild, no open-document refresh, no reload message).
  private void refreshReactorTypeIndex() {
    final var t = Stopwatch.start();
    reactorShards.clear();
    reactorBaseShards.clear();
    scanReactorShards();
    typeIndex =
        WorkspaceTypeIndex.build(manifest.typeIndexShardPaths(), reactorShards.values())
            .withUsageCounts(reactorUsageCounts);
    LOG.info(() -> "[refresh] reactor type index %dms".formatted(t.elapsedMs()));
  }

  private void refreshOpenDocuments() {
    for (final var uri : List.copyOf(docs.uris())) {
      final OpenDocument f = docs.get(uri);
      docs.put(uri, f.content(), f.version());
    }
  }

  private void compileAndPublish(final OpenDocument snapshot, final CompileMode mode) {
    final var route = routeCompiler(snapshot.uri());
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

  private void submitCompile(final OpenDocument snapshot, final AfterCompile afterCompile) {
    submitCompile(routeCompiler(snapshot.uri()), snapshot, CompileMode.FAST, afterCompile);
  }

  private void submitCompile(
      final CompilerRoute route,
      final OpenDocument snapshot,
      final CompileMode mode,
      final AfterCompile afterCompile) {
    if (route instanceof final CompilerRoute.Missing missing) {
      publisher.publishMissing(missing.uri(), missing.message());
      return;
    }

    submitTo(route, route.analysisWorker().orElseThrow(), snapshot, mode, afterCompile);
  }

  private void submitTo(
      final CompilerRoute route,
      final CompilationWorker moduleWorker,
      final OpenDocument snapshot,
      final CompileMode mode,
      final AfterCompile afterCompile) {
    touchAnalysisCache(snapshot.uri());
    final List<CompileRequest> siblings =
        route instanceof final CompilerRoute.Module module
            ? unsavedSiblings(module.config(), snapshot)
            : List.of();
    moduleWorker
        .compile(requestFor(snapshot, mode), siblings)
        .thenAccept(result -> worker.execute(() -> afterCompile.accept(snapshot, result)))
        .exceptionally(
            ex -> {
              worker.execute(() -> publisher.publishError(snapshot, mode, ex));
              return null;
            });
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
    return workspace.moduleSourceFor(LatheUri.toPath(uri)).map(config::equals).orElse(false);
  }

  private CompilerRoute routeCompiler(final String uri) {
    final var path = LatheUri.toPath(uri);
    return workspace
        .moduleSourceFor(path)
        .<CompilerRoute>map(module -> new CompilerRoute.Module(workspace.workerFor(module), module))
        .orElseGet(
            () -> {
              if (manifest.containsFile(path)) {
                return new CompilerRoute.External(workspace.externalWorker());
              }

              return new CompilerRoute.Missing(
                  uri,
                  "This file is not under any Lathe module source root, so Lathe cannot analyze it.");
            });
  }

  // The static imports the file's own scope declares: test files learn from test files, main files
  // from main files, and a file outside any module from nothing.
  private LearnedStaticImports learnedStaticImports(final CompilerRoute route) {
    return route instanceof final CompilerRoute.Module module
        ? candidateIndex.staticImports(module.config().isTestTree())
        : LearnedStaticImports.empty();
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

  private <T> CompletableFuture<T> openDocFeature(
      final String uri,
      final T fallback,
      final BiFunction<CompilationWorker, OpenDocument, CompletableFuture<T>> op) {
    final OpenDocument doc = docs.get(uri);
    if (doc == null) {
      return CompletableFuture.completedFuture(fallback);
    }

    return routeFeature(uri, worker -> op.apply(worker, doc), fallback);
  }

  private void touchAnalysisCache(final String uri) {
    analysisLru
        .touch(uri)
        .ifPresent(
            evictedUri -> {
              LOG.fine(
                  () -> "[evict] %s selected open=%d".formatted(evictedUri, analysisLru.size()));
              workspace.dropFromAllCaches(evictedUri);
            });
  }

  private <T> CompletableFuture<T> routeFeature(
      final String uri,
      final Function<CompilationWorker, CompletableFuture<T>> operation,
      final T missingFallback) {
    final Optional<CompilationWorker> worker = routeCompiler(uri).analysisWorker();
    if (worker.isEmpty()) {
      return CompletableFuture.completedFuture(missingFallback);
    }

    touchAnalysisCache(uri);
    return operation.apply(worker.get());
  }

  private static void recordCompileStamp(
      final ModuleSourceConfig config, final Path savedSource, final SourceStamp stamp) {
    final var root = sourceRootFor(config, savedSource);
    if (root != null) {
      recordStamps(config, Map.of(root.relativize(savedSource), stamp));
    }
  }

  private static void recordStamps(
      final ModuleSourceConfig config, final Map<Path, SourceStamp> stamps) {
    if (stamps.isEmpty()) {
      return;
    }

    try {
      CompiledStamps.record(config.moduleDir(), config.sourceTree(), stamps);
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
      if (routeCompiler(uri) instanceof final CompilerRoute.Module module) {
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
      worker.cancel(snapshot.uri());
      touchAnalysisCache(snapshot.uri());
      requests.add(requestFor(snapshot, CompileMode.OPEN));
    }

    LOG.fine(
        () ->
            "[dependents] batch %d file(s) in %s"
                .formatted(snapshots.size(), moduleRelForDir(config.moduleDir())));
    workspace
        .workerFor(config)
        .compileOpenBatch(requests)
        .thenAccept(responses -> worker.execute(() -> publishBatch(snapshots, responses)))
        .exceptionally(
            ex -> {
              LOG.log(Level.FINE, ex, () -> "[dependents] batch failed, compiling one by one");
              worker.execute(() -> snapshots.forEach(s -> scheduleDependent(s.uri())));
              return null;
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
    return workspace
        .moduleSourceFor(LatheUri.toPath(uri))
        .map(m -> scope.contains(m.moduleDir()))
        .orElse(false);
  }

  private void scheduleAllOpenFiles() {
    docs.all().stream().map(OpenDocument::uri).toList().forEach(this::scheduleOpenFile);
  }

  private void scheduleOpenFile(final String uri) {
    worker.schedule(
        uri,
        0L,
        () -> {
          final OpenDocument openFile = docs.get(uri);
          if (openFile != null) {
            compileAndPublish(openFile, CompileMode.OPEN);
          }
        });
  }

  private void scheduleAstRefresh(final String uri) {
    worker.schedule(
        uri,
        0L,
        () -> {
          final OpenDocument openFile = docs.get(uri);
          if (openFile != null) {
            submitCompile(openFile, publisher::refreshTokensIfCurrent);
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

  private static <T> T logAndReturn(final Throwable ex, final String msg, final T fallback) {
    LOG.log(SEVERE, ex, () -> msg);
    return fallback;
  }

  private static Either<List<? extends Location>, List<? extends LocationLink>> definitionResult(
      final List<? extends Location> locations) {
    return Either.forLeft(locations);
  }

  private static SemanticTokens encodeTokensOrNull(final List<SemanticToken> tokens) {
    if (tokens == null) {
      return null;
    }

    final int[] encoded = TokenScanner.encode(tokens);
    return new SemanticTokens(IntStream.of(encoded).boxed().toList());
  }

  private sealed interface CompilerRoute {
    // The worker that analyzes the file; abstract, so a new kind of route must say whether it has
    // one.
    Optional<CompilationWorker> analysisWorker();

    record Module(CompilationWorker worker, ModuleSourceConfig config) implements CompilerRoute {
      @Override
      public Optional<CompilationWorker> analysisWorker() {
        return Optional.of(worker);
      }
    }

    record External(CompilationWorker worker) implements CompilerRoute {
      @Override
      public Optional<CompilationWorker> analysisWorker() {
        return Optional.of(worker);
      }
    }

    record Missing(String uri, String message) implements CompilerRoute {
      @Override
      public Optional<CompilationWorker> analysisWorker() {
        return Optional.empty();
      }
    }
  }

  @FunctionalInterface
  private interface AfterCompile {
    void accept(OpenDocument snapshot, CompileResponse result);
  }
}
