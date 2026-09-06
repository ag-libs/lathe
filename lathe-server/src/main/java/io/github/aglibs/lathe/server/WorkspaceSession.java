package io.github.aglibs.lathe.server;

import static java.util.logging.Level.SEVERE;

import io.github.aglibs.lathe.core.CollectionUtil;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.PortUtil;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.launch.JdwpOptions;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.RunKind;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import io.github.aglibs.lathe.core.typeindex.ClassFileTypeScanner;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.analysis.CallHierarchyItemData;
import io.github.aglibs.lathe.server.analysis.CallHierarchyItemDataCodec;
import io.github.aglibs.lathe.server.analysis.CodeActionRequest;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.DiagnosticPayload;
import io.github.aglibs.lathe.server.analysis.ReferenceMatch;
import io.github.aglibs.lathe.server.analysis.ReferenceTarget;
import io.github.aglibs.lathe.server.analysis.SemanticToken;
import io.github.aglibs.lathe.server.analysis.SourceFeatureRequest;
import io.github.aglibs.lathe.server.analysis.TokenScanner;
import io.github.aglibs.lathe.server.analysis.TransientSource;
import io.github.aglibs.lathe.server.analysis.TypeHierarchyItemDataCodec;
import io.github.aglibs.lathe.server.analysis.TypeSourceLocator;
import io.github.aglibs.lathe.server.analysis.WorkspaceSymbolResolver;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.server.analysis.completion.CompletionOutcome;
import io.github.aglibs.lathe.server.debug.DapHost;
import io.github.aglibs.lathe.server.debug.DebugStartResult;
import io.github.aglibs.lathe.server.debug.LatheProviderContext;
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
import io.github.aglibs.lathe.server.run.LaunchSession;
import io.github.aglibs.lathe.server.run.LaunchTemplateReader;
import io.github.aglibs.lathe.server.run.Launcher;
import io.github.aglibs.lathe.server.run.MainClassScope;
import io.github.aglibs.lathe.server.run.MainLaunchReader;
import io.github.aglibs.lathe.server.run.ResolvedLaunch;
import io.github.aglibs.lathe.server.run.RunConfigReader;
import io.github.aglibs.lathe.server.run.RunItem;
import io.github.aglibs.lathe.server.run.RunOverlay;
import io.github.aglibs.lathe.server.run.RunTarget;
import io.github.aglibs.lathe.server.run.TestEventParams;
import io.github.aglibs.lathe.server.run.TestFinishedParams;
import io.github.aglibs.lathe.server.run.TestOutputParams;
import io.github.aglibs.lathe.server.run.TestResult;
import io.github.aglibs.lathe.server.run.TranscriptLine;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
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
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

/** Not thread-safe. All methods must be called from the {@link ServerEventLoop} thread. */
final class WorkspaceSession {

  private static final Logger LOG = Logger.getLogger(WorkspaceSession.class.getName());

  // Small: attributing disk candidates in one javac task amortizes its fixed per-invocation cost,
  // while a small batch bounds peak memory, cancellation latency, and analyze()-crash blast radius.
  private static final int REFERENCE_BATCH_SIZE = 8;
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
  private WorkspaceWatcher watcher;
  private boolean pomNotificationPending;
  // Newest mtime among stale sources already acknowledged, so a dismissed "sync needed" stays quiet
  // until an even-newer external change appears (mirrors the POM acknowledge baseline). Reset to 0
  // on
  // any resync (a workspace.json bump), where the mirror is fresh again.
  private long acknowledgedSourceMtime;
  // The -pl module selectors carried from the pending sync prompt to the sync request (empty = full
  // reactor). Set at prompt time because the request fires later, on the user's response.
  private List<String> pendingSyncModules = List.of();
  private final DocumentRegistry docs = new DocumentRegistry();
  private final AnalysisLru analysisLru = new AnalysisLru();
  private final DiagnosticPublisher publisher;

  // In-flight replays keyed by run token, so a cancel can reach the right LaunchSession. A plain
  // map, not a concurrent one: it is mutated only on the worker thread. The run and exit threads
  // marshal put/remove via worker.execute (see runTestFuture) rather than touching it directly,
  // keeping the single-threaded discipline of every other field here.
  private final Map<String, LaunchSession> activeRuns = new HashMap<>();
  private final Map<String, DapHost> activeDebugHosts = new HashMap<>();

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
    typeIndex = WorkspaceTypeIndex.build(manifest.typeIndexShardPaths(), reactorShards.values());
    watcher = new WorkspaceWatcher(root);
    watcher.updatePomPaths(manifest.pomPaths());
    worker.scheduleAtFixedRate(2_000L, this::checkForChanges);
    if (configured) {
      client.showMessage(new MessageParams(MessageType.Info, "Lathe: workspace ready."));
    } else {
      client.showMessage(
          new MessageParams(
              MessageType.Warning,
              "Lathe: not configured — run `mvn process-test-classes` to set up this project."));
    }

    // Startup reconciliation for the cold-start delta: sources/resources changed while Lathe was
    // down
    // (stale-or-missing .class, or a resource newer than its .lathe/ copy). Runs now rather than
    // waiting for the first 2s tick.
    reconcileIfIdle();
  }

  void close() {
    workspace.close();
  }

  CompletableFuture<LaunchOutcome> runTestFuture(
      final String moduleRel, final List<TestSelection> selections, final String token) {
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
                    runnerClasspath,
                    moduleRel,
                    selections,
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
    final List<Path> runnerClasspath = manifest.runnerClasspath();
    if (runnerClasspath.isEmpty()) {
      throw new IllegalStateException("no lathe-test-runner jar recorded — run a build first");
    }

    try {
      final TestLaunchData template =
          new LaunchTemplateReader(workspaceRoot)
              .read(moduleRel)
              .orElseThrow(
                  () -> new IllegalStateException("no captured test-launch.json for " + moduleRel));
      assertLaunchComplete(CompletenessGate.verify(template, workspaceRoot));

      final int jdwpPort = PortUtil.free();
      final var jdwp = new JdwpOptions(jdwpPort);
      final var jdwpReady = new CompletableFuture<Void>();
      final Path resultsSink = Files.createTempFile("lathe-results-", ".ndjson");
      final RunItem overlay =
          new RunConfigReader(workspaceRoot).read().defaultFor(moduleRel, RunKind.TEST);
      final ResolvedLaunch resolved =
          RunOverlay.applyToTest(
              template, workspaceRoot, runnerClasspath, selections, resultsSink, overlay, jdwp);
      final var session =
          Launcher.launch(
              resolved.argv(),
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
    try {
      final int jdwpPort = PortUtil.free();
      final var jdwp = new JdwpOptions(jdwpPort);
      final MainLaunchPlan plan = resolveMainLaunch(workspaceRoot, moduleRel, mainClass, jdwp);
      if (plan.blocked()) {
        throw new IllegalStateException("debug launch incomplete: " + plan.blockedReasons());
      }

      final var jdwpReady = new CompletableFuture<Void>();
      final ResolvedLaunch resolved = plan.launch();
      final var session =
          Launcher.launch(
              resolved.argv(),
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
      final String moduleRel,
      final String mainClass,
      final JdwpOptions jdwp)
      throws IOException {
    final RunItem overlay =
        new RunConfigReader(workspaceRoot).read().defaultFor(moduleRel, RunKind.MAIN);
    if (MainClassScope.isTestScope(workspaceRoot, moduleRel, mainClass)) {
      final Optional<TestLaunchData> template =
          new LaunchTemplateReader(workspaceRoot).read(moduleRel);
      if (template.isEmpty()) {
        return MainLaunchPlan.blocked(
            List.of(
                "no captured test launch for %s; run `mvn test` to capture it"
                    .formatted(moduleRel)));
      }

      final CompletenessResult gate = CompletenessGate.verify(template.get(), workspaceRoot);
      if (!gate.complete()) {
        return MainLaunchPlan.blocked(gate.reasons());
      }

      return MainLaunchPlan.ready(
          RunOverlay.applyToTestMain(template.get(), workspaceRoot, mainClass, overlay, jdwp));
    }

    final Optional<MainLaunchData> template = new MainLaunchReader(workspaceRoot).read(moduleRel);
    if (template.isEmpty()) {
      return MainLaunchPlan.blocked(List.of("no derived main-launch.json for " + moduleRel));
    }

    final CompletenessResult gate = CompletenessGate.verify(template.get(), workspaceRoot);
    if (!gate.complete()) {
      return MainLaunchPlan.blocked(gate.reasons());
    }

    return MainLaunchPlan.ready(
        RunOverlay.applyToMain(template.get(), workspaceRoot, mainClass, overlay, jdwp));
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

    final List<Path> sourceRoots =
        configsFor(moduleRel).stream().flatMap(c -> c.sourceRoots().stream()).distinct().toList();
    // The debuggee is a replay Lathe owns, so a client disconnect terminates it (launch semantics)
    // rather than leaving it running as a plain attach would -- otherwise a long-running debuggee
    // would be orphaned when the user stops debugging.
    final var host =
        DapHost.start(
            new LatheProviderContext(workspace, sourceRoots, typeIndex),
            () -> worker.execute(() -> cancelRun(token)));
    activeDebugHosts.put(token, host);
    LOG.info(
        () -> "[debug] %s %s dap=%d jdwp=%d".formatted(moduleRel, label, host.port(), jdwpPort));
    return new DebugStartResult(host.port(), jdwpPort);
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

  private static void launchTest(
      final Path workspaceRoot,
      final List<Path> runnerClasspath,
      final String moduleRel,
      final List<TestSelection> selections,
      final Consumer<TranscriptLine> onLine,
      final Consumer<TestResult> onResult,
      final Consumer<LaunchSession> onStart,
      final Stopwatch t,
      final CompletableFuture<LaunchOutcome> result) {
    try {
      final var template = new LaunchTemplateReader(workspaceRoot).read(moduleRel);
      if (template.isEmpty()) {
        LOG.warning(
            () ->
                "[launch] %s %s blocked no captured test-launch.json"
                    .formatted(moduleRel, selectionLabel(selections)));
        result.complete(
            LaunchOutcome.blocked(List.of("no captured test-launch.json for " + moduleRel)));
        return;
      }

      final var gate = CompletenessGate.verify(template.get(), workspaceRoot);
      if (!gate.complete()) {
        LOG.warning(
            () ->
                "[launch] %s %s blocked reasons=%s"
                    .formatted(moduleRel, selectionLabel(selections), gate.reasons()));
        result.complete(LaunchOutcome.blocked(gate.reasons()));
        return;
      }

      final Path resultsSink = Files.createTempFile("lathe-results-", ".ndjson");
      final RunItem overlay =
          new RunConfigReader(workspaceRoot).read().defaultFor(moduleRel, RunKind.TEST);
      final ResolvedLaunch resolved =
          RunOverlay.applyToTest(
              template.get(),
              workspaceRoot,
              runnerClasspath,
              selections,
              resultsSink,
              overlay,
              JdwpOptions.NONE);
      final var session =
          Launcher.launch(
              resolved.argv(), resultsSink, onLine, onResult, resolved.env(), resolved.cwd());
      onStart.accept(session);
      final String label = selectionLabel(selections);
      session
          .onExit()
          .whenComplete(
              (outcome, error) -> completeRun(moduleRel, label, t, result, outcome, error));
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
            "[launch] %s %s exit=%d %dms"
                .formatted(moduleRel, label, outcome.exitCode(), t.elapsedMs()));
    result.complete(outcome);
  }

  CompletableFuture<LaunchOutcome> runMainFuture(
      final String moduleRel, final String mainClass, final String token) {
    final Consumer<TranscriptLine> onLine = streamConsumer(token);
    final Path root = workspaceRoot;
    final var t = Stopwatch.start();
    final var result = new CompletableFuture<LaunchOutcome>();
    result.whenComplete((outcome, error) -> worker.execute(() -> activeRuns.remove(token)));
    final Consumer<LaunchSession> onStart =
        session -> worker.execute(() -> activeRuns.put(token, session));

    final var thread =
        new Thread(
            () -> launchMain(root, moduleRel, mainClass, onLine, onStart, t, result),
            "lathe-launch-" + moduleRel);
    thread.setDaemon(true);
    thread.start();
    return result;
  }

  private static void launchMain(
      final Path workspaceRoot,
      final String moduleRel,
      final String mainClass,
      final Consumer<TranscriptLine> onLine,
      final Consumer<LaunchSession> onStart,
      final Stopwatch t,
      final CompletableFuture<LaunchOutcome> result) {
    try {
      final MainLaunchPlan plan =
          resolveMainLaunch(workspaceRoot, moduleRel, mainClass, JdwpOptions.NONE);
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
              resolved.argv(), null, onLine, ignored -> {}, resolved.env(), resolved.cwd());
      onStart.accept(session);
      session
          .onExit()
          .whenComplete(
              (outcome, error) -> completeRun(moduleRel, mainClass, t, result, outcome, error));
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
    return switch (routeCompiler(uri)) {
      case CompilerRoute.Module module -> {
        touchAnalysisCache(uri);
        yield module
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
      case CompilerRoute.External ignored -> CompletableFuture.completedFuture(List.of());
      case CompilerRoute.Missing ignored -> CompletableFuture.completedFuture(List.of());
    };
  }

  private String moduleRel(final ModuleSourceConfig config) {
    return workspaceRoot.resolve(LatheLayout.LATHE_DIR).relativize(config.moduleDir()).toString();
  }

  void onOpen(final String uri, final String content, final int version) {
    final var snapshot = docs.put(uri, content, version);
    LOG.info(() -> "[open] %s".formatted(uri));
    candidateIndex.update(uri, content);
    compileAndPublish(snapshot, CompileMode.OPEN);
  }

  void onChange(final String uri, final String content, final int version) {
    docs.put(uri, content, version);
    LOG.fine(() -> "[change] %s".formatted(uri));
    candidateIndex.update(uri, content);
    publisher.publishEmpty(uri);
    worker.cancel(uri);
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
    LOG.info(() -> "[close] %s".formatted(uri));
    worker.cancel(uri);
    analysisLru.remove(uri);
    workspace.dropFromAllCaches(uri);
    publisher.publishEmpty(uri);
    reindexFromDisk(uri);
  }

  void onSave(final String uri, final String savedContent) {
    LOG.info(() -> "[save] %s".formatted(uri));
    worker.cancel(uri);

    final var snapshot = snapshotForSave(uri, savedContent);
    if (snapshot == null) {
      return;
    }

    final var route = routeCompiler(uri);
    final AfterCompile afterCompile =
        switch (route) {
          case CompilerRoute.Module module ->
              publishThen(
                  route, result -> afterModuleSave(result, module.config(), LatheUri.toPath(uri)));
          case CompilerRoute.External ignored -> publishThen(route, () -> scheduleAstRefresh(uri));
          case CompilerRoute.Missing ignored -> publisher::publishIfCurrent;
        };
    submitCompile(route, snapshot, CompileMode.FULL, afterCompile);
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

    final var request =
        new SourceFeatureRequest(
            openFile.uri(),
            openFile.content(),
            openFile.version(),
            pos,
            workspace.allSourceRoots(),
            manifest);

    final var cursorWorker =
        switch (routeCompiler(uri)) {
          case CompilerRoute.Module m -> m.worker();
          case CompilerRoute.External e -> e.worker();
          case CompilerRoute.Missing ignored -> null;
        };
    if (cursorWorker == null) {
      return CompletableFuture.completedFuture(List.of());
    }

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
            locations -> {
              cancelChecker.checkCanceled();
              final var name = targetName.get();
              if (name != null) {
                LOG.info(
                    () ->
                        "[references] %s %dms target=%s hits=%d"
                            .formatted(uri, t.elapsedMs(), name, locations.size()));
              }
              return locations;
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

  CompletableFuture<List<Location>> instantiationsFuture(
      final String uri, final Position pos, final CancelChecker cancelChecker) {
    cancelChecker.checkCanceled();
    final OpenDocument openFile = docs.get(uri);
    if (openFile == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    final var request =
        new SourceFeatureRequest(
            openFile.uri(),
            openFile.content(),
            openFile.version(),
            pos,
            workspace.allSourceRoots(),
            manifest);

    final var cursorWorker =
        switch (routeCompiler(uri)) {
          case CompilerRoute.Module m -> m.worker();
          case CompilerRoute.External e -> e.worker();
          case CompilerRoute.Missing ignored -> null;
        };
    if (cursorWorker == null) {
      return CompletableFuture.completedFuture(List.of());
    }

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

              // One reference search per constructor (overload), unioned. Candidate discovery keys
              // on
              // the type's simple name and ReferenceLocator.visitNewClass emits the match at the
              // `new XXX` identifier, so this yields exactly the instantiation sites.
              final List<CompletableFuture<List<Location>>> searches =
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
                                  progressReporter.open(null, new CompletableFuture<>()),
                                  "Finding instantiation sites"))
                      .toList();
              return joinCandidateResults(searches, cancelChecker);
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

  private CompletableFuture<List<Location>> searchReferencesForTarget(
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
          .thenApply(WorkspaceSession::toLocations)
          .whenComplete(
              (locations, failure) -> {
                if (failure == null) {
                  progress.advance(false, locations.size());
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

    final List<CompletableFuture<List<Location>>> searches =
        searchInputs.stream()
            .flatMap(
                inputs ->
                    searchFutures(inputs, target, includeDeclaration, cancelChecker, progress))
            .toList();
    return joinCandidateResults(searches, cancelChecker);
  }

  private Stream<CompletableFuture<List<Location>>> searchFutures(
      final ModuleSearchInputs searchInputs,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    final CompilationWorker worker = searchInputs.worker();
    final WorkspaceSearchInputs inputs = searchInputs.inputs();
    final Stream<CompletableFuture<List<Location>>> openSearches =
        inputs.openDocuments().stream()
            .map(
                doc ->
                    searchOpenDocument(
                        worker, doc, target, includeDeclaration, cancelChecker, progress));
    final Stream<CompletableFuture<List<Location>>> diskSearches =
        CollectionUtil.partition(inputs.diskCandidates(), REFERENCE_BATCH_SIZE).stream()
            .map(
                chunk ->
                    searchDiskChunk(
                        worker, chunk, target, includeDeclaration, cancelChecker, progress));
    return Stream.concat(openSearches, diskSearches);
  }

  private CompletableFuture<List<Location>> searchOpenDocument(
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
        .thenApply(WorkspaceSession::toLocations)
        .whenComplete(
            (locations, failure) -> {
              if (failure == null) {
                progress.advance(false, locations.size());
              }
            });
  }

  private CompletableFuture<List<Location>> searchDiskChunk(
      final CompilationWorker worker,
      final List<DiskCandidate> chunk,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    final List<TransientSource> sources =
        chunk.stream().map(d -> new TransientSource(d.uri(), d.content())).toList();
    return worker
        .searchReferencesTransient(
            sources,
            target,
            includeDeclaration,
            hits -> progress.advance(true, hits),
            cancelChecker)
        .thenApply(WorkspaceSession::toLocations);
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

  private void reindexFromDisk(final String uri) {
    final var path = LatheUri.toPath(uri);
    if (Files.exists(path)) {
      try {
        candidateIndex.update(uri, Files.readString(path));
      } catch (final IOException e) {
        LOG.log(Level.WARNING, e, () -> "[candidate-index] re-index failed for %s".formatted(uri));
        candidateIndex.remove(uri);
      }
    } else {
      candidateIndex.remove(uri);
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
    final List<Path> searchRoots = ReferenceCandidatePlanner.packageSearchRoots(config);
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
          final var request =
              new SourceFeatureRequest(
                  doc.uri(),
                  doc.content(),
                  doc.version(),
                  pos,
                  workspace.allSourceRoots(),
                  manifest);
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
          final var request =
              new SourceFeatureRequest(
                  doc.uri(),
                  doc.content(),
                  doc.version(),
                  pos,
                  workspace.allSourceRoots(),
                  manifest);
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
          final var request =
              new SourceFeatureRequest(
                  doc.uri(),
                  doc.content(),
                  doc.version(),
                  pos,
                  workspace.allSourceRoots(),
                  manifest);
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
          final var request =
              new SourceFeatureRequest(
                  doc.uri(),
                  doc.content(),
                  doc.version(),
                  pos,
                  workspace.allSourceRoots(),
                  manifest);
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

    final var request =
        new SourceFeatureRequest(
            openFile.uri(),
            openFile.content(),
            openFile.version(),
            pos,
            workspace.allSourceRoots(),
            manifest);
    final var indexSnapshot = typeIndex;
    final var cursorWorker =
        switch (routeCompiler(uri)) {
          case CompilerRoute.Module module -> module.worker();
          case CompilerRoute.External external -> external.worker();
          case CompilerRoute.Missing ignored -> null;
        };
    if (cursorWorker == null) {
      return CompletableFuture.completedFuture(Either.forLeft(List.of()));
    }

    touchAnalysisCache(openFile.uri());

    final var t = Stopwatch.start();
    return cursorWorker
        .resolveTarget(request)
        .thenCompose(
            target ->
                worker
                    .submit(
                        () -> implementationForTarget(target, request, cursorWorker, indexSnapshot))
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
      final WorkspaceTypeIndex indexSnapshot) {
    if (target == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    if (target.kind().isClass() || target.kind().isInterface()) {
      return cursorWorker.typeImplementations(request, indexSnapshot);
    }

    if (target.kind() != ElementKind.METHOD) {
      return CompletableFuture.completedFuture(List.of());
    }

    final Map<Path, Set<String>> candidatesByFile =
        indexSnapshot.transitiveSubtypes(target.qualifiedName()).stream()
            .filter(TypeSourceLocator::isNamedDeclaration)
            .flatMap(
                entry ->
                    TypeSourceLocator.findSourceFile(entry, workspace.allSourceRoots()).stream()
                        .map(path -> Map.entry(path, entry.binaryName())))
            .collect(
                Collectors.groupingBy(
                    Map.Entry::getKey,
                    Collectors.mapping(Map.Entry::getValue, Collectors.toUnmodifiableSet())));
    return candidatesByFile.entrySet().stream()
        .map(entry -> methodImplementationFuture(entry.getKey(), entry.getValue(), target))
        .reduce(
            CompletableFuture.completedFuture(List.of()),
            (left, right) ->
                left.thenCombine(
                    right,
                    (first, second) -> Stream.concat(first.stream(), second.stream()).toList()));
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
            false);
    final var progressTitle = "Finding callers of %s".formatted(target.simpleName());

    final var declaringPath = LatheUri.toPath(data.routingUri());
    final var declaringConfig = workspace.moduleSourceFor(declaringPath);

    if (target.scope() == ReferenceTarget.SearchScope.DECLARING_FILE) {
      final var worker =
          switch (routeCompiler(data.routingUri())) {
            case CompilerRoute.Module m -> m.worker();
            case CompilerRoute.External e -> e.worker();
            case CompilerRoute.Missing ignored -> null;
          };
      if (worker == null) {
        return CompletableFuture.completedFuture(List.of());
      }
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
              final var request =
                  new SourceFeatureRequest(
                      doc.uri(),
                      doc.content(),
                      doc.version(),
                      pos,
                      workspace.allSourceRoots(),
                      manifest);
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
              final var request =
                  new SourceFeatureRequest(
                      doc.uri(),
                      doc.content(),
                      doc.version(),
                      pos,
                      workspace.allSourceRoots(),
                      manifest);
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

  CompletableFuture<CompletionOutcome> completionFuture(
      final String uri, final Position pos, final CompletionContext context) {
    final var indexSnapshot = typeIndex;
    final var route = routeCompiler(uri);
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
                    completionModuleNames(route, uri))
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
            .filter(r -> r.payload().kind() == DiagnosticPayload.Kind.TYPE_REF)
            .map(r -> r.payload().name())
            .collect(Collectors.toUnmodifiableSet());
    final var baseIndex = typeIndex;
    return routeFeature(
        uri,
        moduleWorker ->
            moduleWorker
                .cachedTypeEntries(neededTypeNames)
                .thenCompose(
                    openEntries -> {
                      final var enriched = buildEnrichedIndex(baseIndex, openEntries);
                      return moduleWorker.codeAction(
                          uri, openFile.content(), openFile.version(), range, requests, enriched);
                    })
                .exceptionally(
                    ex -> logAndReturn(ex, "[codeAction] failed for %s".formatted(uri), List.of())),
        List.of());
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

    return switch (routeCompiler(uri)) {
      case CompilerRoute.Module module -> foldWith(module.worker(), uri, doc);
      case CompilerRoute.External external -> foldWith(external.worker(), uri, doc);
      case CompilerRoute.Missing ignored -> {
        LOG.fine(() -> "[foldingRange] %s skipped route=missing ranges=0".formatted(uri));
        yield CompletableFuture.completedFuture(List.of());
      }
    };
  }

  private CompletableFuture<List<FoldingRange>> foldWith(
      final CompilationWorker worker, final String uri, final OpenDocument doc) {
    touchAnalysisCache(uri);
    return worker
        .foldingRange(uri, doc.content())
        .exceptionally(
            ex -> logAndReturn(ex, "[foldingRange] failed for %s".formatted(uri), List.of()));
  }

  List<? extends TextEdit> format(final String uri) {
    final var t = Stopwatch.start();
    final OpenDocument openFile = docs.get(uri);
    final List<TextEdit> result =
        JavaFormatter.format(openFile != null ? openFile.content() : null);
    LOG.info(() -> "[%s] %s %dms edits=%d".formatted("format", uri, t.elapsedMs(), result.size()));
    return result;
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
    for (final var config : workspace.allConfigs()) {
      reactorShards.put(config, scanReactorDir(config));
    }
  }

  private void refreshReactorShard(final ModuleSourceConfig config) {
    reactorShards.put(config, scanReactorDir(config));
    typeIndex = typeIndex.withReactorEntries(reactorShards.values());
  }

  private static List<TypeIndexEntry> scanReactorDir(final ModuleSourceConfig config) {
    try {
      return ClassFileTypeScanner.scanDirectory(config.latheClassesDir());
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING,
          e,
          () -> "[type-index] reactor scan failed: %s".formatted(config.latheClassesDir()));
      return List.of();
    }
  }

  static int deleteClassOutputs(final ModuleSourceConfig config, final Path deletedSource) {
    if (!FileUtil.isJavaFile(deletedSource)) {
      return 0;
    }

    final var sourceRoot = sourceRootFor(config, deletedSource);
    if (sourceRoot == null) {
      return 0;
    }

    final var rel = sourceRoot.relativize(deletedSource);
    final var packageRel = rel.getParent();
    final var classDir =
        packageRel != null
            ? config.latheClassesDir().resolve(packageRel)
            : config.latheClassesDir();
    if (!Files.isDirectory(classDir)) {
      return 0;
    }

    final var typeName = typeNameFrom(deletedSource);
    try (final var stream = Files.list(classDir)) {
      final var matchingClassFiles =
          stream.filter(path -> deletedClassFile(typeName, path)).toList();
      for (final var classFile : matchingClassFiles) {
        Files.deleteIfExists(classFile);
      }
      return matchingClassFiles.size();
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING, e, () -> "[delete] class cleanup failed for %s".formatted(deletedSource));
      return 0;
    }
  }

  static int deleteStaleClassOutputs(
      final ModuleSourceConfig config,
      final Path savedSource,
      final Set<String> writtenBinaryNames) {
    if (!FileUtil.isJavaFile(savedSource)) {
      return 0;
    }

    final var sourceRoot = sourceRootFor(config, savedSource);
    if (sourceRoot == null) {
      return 0;
    }

    final var rel = sourceRoot.relativize(savedSource);
    final var packageRel = rel.getParent();
    final var classDir =
        packageRel != null
            ? config.latheClassesDir().resolve(packageRel)
            : config.latheClassesDir();
    if (!Files.isDirectory(classDir)) {
      return 0;
    }

    final var typeName = typeNameFrom(savedSource);
    try (final var stream = Files.list(classDir)) {
      final var staleClassFiles =
          stream
              .filter(
                  path -> {
                    final var n = path.getFileName().toString();
                    return n.startsWith(typeName + "$") && n.endsWith(".class");
                  })
              .filter(path -> !writtenBinaryNames.contains(toBinaryName(config, path)))
              .toList();
      for (final var classFile : staleClassFiles) {
        Files.deleteIfExists(classFile);
      }

      if (!staleClassFiles.isEmpty()) {
        LOG.fine(
            () ->
                "[delete] %s stale=%d"
                    .formatted(savedSource.getFileName(), staleClassFiles.size()));
      }

      return staleClassFiles.size();
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING,
          e,
          () -> "[delete] stale class cleanup failed for %s".formatted(savedSource));
      return 0;
    }
  }

  // Idle-tick and startup reconciliation: detect stale sources (→ sync prompt) and copy stale
  // resources into .lathe/. Suppressed while any module is mid-build, so a partially written
  // .lathe/ is never read.
  private void reconcileIfIdle() {
    if (anyModuleLockHeld()) {
      return;
    }

    checkSourceStaleness();
    reconcileResources();
  }

  private StaleScan scanStaleModules() {
    return staleModules(workspace.allConfigs(), openSourcePaths());
  }

  private void checkSourceStaleness() {
    if (pomNotificationPending) {
      return;
    }

    final StaleScan scan = scanStaleModules();
    if (scan.newestMtime() > acknowledgedSourceMtime) {
      final List<String> moduleRels = moduleRels(scan.modules());
      LOG.info(() -> "[watcher] source changed in %s — sync needed".formatted(moduleRels));
      promptForSync(moduleRels);
    }
  }

  // The reactor-relative module paths of the stale modules, for the client's -pl selector. Derived
  // from the .lathe mirror (moduleDir = latheDir/moduleRel); the classes/test-classes configs of
  // one
  // module share a moduleDir, so distinct dedupes them.
  private List<String> moduleRels(final Set<ModuleSourceConfig> modules) {
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    return modules.stream()
        .map(config -> latheDir.relativize(config.moduleDir()).toString())
        .distinct()
        .sorted()
        .toList();
  }

  // Targeted when a small number of modules changed; a broader change goes full-reactor (empty),
  // which a -pl build could not do anyway since it does not regenerate workspace.json.
  static List<String> syncScope(final List<String> moduleRels) {
    return moduleRels.size() <= TARGETED_MODULE_CAP ? moduleRels : List.of();
  }

  // Names the changed modules in the prompt; empty means a structural/POM change (full reactor), so
  // no module is named. A long list is truncated to keep the prompt readable.
  static String syncPromptMessage(final List<String> modules) {
    if (modules.isEmpty()) {
      return "Maven project changed. Run Maven to refresh Lathe.";
    }

    final String named =
        modules.size() <= MODULE_DISPLAY_LIMIT
            ? String.join(", ", modules)
            : "%s (+%d more)"
                .formatted(
                    String.join(", ", modules.subList(0, MODULE_DISPLAY_LIMIT)),
                    modules.size() - MODULE_DISPLAY_LIMIT);
    return "Sources changed in %s. Run Maven to refresh Lathe.".formatted(named);
  }

  // Copy any resource whose .lathe/ destination is missing or older than the source, so an external
  // resource edit is picked up without Maven. The copy bumps the destination mtime, so the next
  // pass
  // sees it as fresh — no dedupe state needed.
  private void reconcileResources() {
    manifest.resourceSourceDirs().stream()
        .filter(Files::isDirectory)
        .forEach(this::copyStaleResourcesUnder);
  }

  private void copyStaleResourcesUnder(final Path sourceDir) {
    try (final var walk = Files.walk(sourceDir)) {
      walk.filter(Files::isRegularFile).forEach(this::copyIfStale);
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[resource] scan failed under %s".formatted(sourceDir));
    }
  }

  private void copyIfStale(final Path file) {
    final Optional<Path> dest = manifest.resourceDestination(file);
    if (dest.isEmpty()) {
      return;
    }

    if (!Files.exists(dest.get()) || mtimeMillis(file) > mtimeMillis(dest.get())) {
      copyResource(file, dest.get());
    }
  }

  // A build/sync writing .lathe/<module>/ holds that module's lock; reconciliation is suppressed
  // while any is held, so a mid-build snapshot (some modules recompiled, others not) neither
  // prompts
  // spuriously nor copies against a half-written mirror.
  private boolean anyModuleLockHeld() {
    return workspace.allConfigs().stream()
        .map(ModuleSourceConfig::moduleDir)
        .distinct()
        .anyMatch(LatheLock::isBuilding);
  }

  private Set<Path> openSourcePaths() {
    return docs.uris().stream()
        .filter(LatheUri::isFileUri)
        .map(LatheUri::toPath)
        .collect(Collectors.toUnmodifiableSet());
  }

  // The stale modules and the newest stale-source mtime across them (0 / empty if none). A source
  // is
  // stale when its primary .class is missing (never compiled -- a new file) or older than the
  // source
  // (edited outside the save path, which rewrites the .class). Open files are the editor's
  // responsibility and skipped; the annotation-processor output (originalGenSourcesDir) is not a
  // hand-written root and excluded. newestMtime drives the report-once dedupe; modules drives the
  // targeted -pl sync.
  record StaleScan(long newestMtime, Set<ModuleSourceConfig> modules) {
    StaleScan {
      modules = Set.copyOf(modules);
    }
  }

  static StaleScan staleModules(
      final Collection<ModuleSourceConfig> configs, final Set<Path> openPaths) {
    final List<ModuleStale> stale =
        configs.stream()
            .map(config -> new ModuleStale(config, newestStaleInModule(config, openPaths)))
            .filter(module -> module.mtime() > 0L)
            .toList();
    return new StaleScan(
        stale.stream().mapToLong(ModuleStale::mtime).max().orElse(0L),
        stale.stream().map(ModuleStale::config).collect(Collectors.toUnmodifiableSet()));
  }

  private record ModuleStale(ModuleSourceConfig config, long mtime) {}

  private static long newestStaleInModule(
      final ModuleSourceConfig config, final Set<Path> openPaths) {
    return config.sourceRoots().stream()
        .filter(root -> !root.equals(config.originalGenSourcesDir()))
        .mapToLong(root -> newestStaleUnder(config, root, openPaths))
        .max()
        .orElse(0L);
  }

  private static long newestStaleUnder(
      final ModuleSourceConfig config, final Path root, final Set<Path> openPaths) {
    if (!Files.isDirectory(root)) {
      return 0L;
    }

    try (final var walk = Files.walk(root)) {
      return walk.filter(FileUtil::isJavaFile)
          .filter(source -> !openPaths.contains(source))
          .filter(source -> isStaleSource(config, root, source))
          .mapToLong(WorkspaceSession::mtimeMillis)
          .max()
          .orElse(0L);
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[watcher] source scan failed under %s".formatted(root));
      return 0L;
    }
  }

  private static boolean isStaleSource(
      final ModuleSourceConfig config, final Path root, final Path source) {
    // package-info.java has a class only when it carries package annotations; a javadoc-only one
    // produces none, so the source-vs-class check would flag it stale forever and re-prompt after
    // every sync. It declares no type, so exclude it from the scan.
    if (source.getFileName().toString().equals("package-info.java")) {
      return false;
    }

    final var packageRel = root.relativize(source).getParent();
    final var classDir =
        packageRel != null
            ? config.latheClassesDir().resolve(packageRel)
            : config.latheClassesDir();
    final var classFile = classDir.resolve(typeNameFrom(source) + ".class");
    return !Files.exists(classFile) || mtimeMillis(source) > mtimeMillis(classFile);
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

  private static String typeNameFrom(final Path sourceFile) {
    final var name = sourceFile.getFileName().toString();
    return name.substring(0, name.length() - ".java".length());
  }

  private static boolean deletedClassFile(final String typeName, final Path path) {
    final var name = path.getFileName().toString();
    return name.equals(typeName + ".class")
        || (name.startsWith(typeName + "$") && name.endsWith(".class"));
  }

  private static String toBinaryName(final ModuleSourceConfig config, final Path classFile) {
    final var relative = config.latheClassesDir().relativize(classFile).toString();
    return relative
        .substring(0, relative.length() - ".class".length())
        .replace(classFile.getFileSystem().getSeparator(), ".");
  }

  // Above this many changed modules a targeted -pl sync loses its edge (its -am upstream union
  // approaches a full build) and a broad change is likelier structural, so fall back to full.
  private static final int TARGETED_MODULE_CAP = 2;
  // How many changed modules to name in the sync prompt before summarising the tail as "(+N more)".
  private static final int MODULE_DISPLAY_LIMIT = 3;
  private static final String SYNC_ACTION = "Sync";
  private static final String SYNC_CAPTURE_ACTION = "Sync + capture tests";
  private static final String LATER_ACTION = "Later";

  private void checkForChanges() {
    if (watcher == null) {
      return;
    }

    switch (watcher.poll()) {
      case WORKSPACE_CHANGED -> {
        reload();
        acknowledgedSourceMtime = 0L;
      }
      case REACTOR_REFRESH -> {
        refreshReactorTypeIndex();
        acknowledgedSourceMtime = 0L;
      }
      case POM_CHANGED -> promptForSync(List.of());
      case NO_CHANGE -> reconcileIfIdle();
    }
  }

  private void promptForSync(final List<String> modules) {
    if (pomNotificationPending) {
      return;
    }

    pendingSyncModules = modules;
    pomNotificationPending = true;
    final var request =
        new ShowMessageRequestParams(
            List.of(
                new MessageActionItem(SYNC_ACTION),
                new MessageActionItem(SYNC_CAPTURE_ACTION),
                new MessageActionItem(LATER_ACTION)));
    request.setMessage(syncPromptMessage(modules));
    request.setType(MessageType.Warning);
    client
        .showMessageRequest(request)
        .thenAccept(action -> worker.execute(() -> onSyncPromptResponse(action)));
  }

  // Any response ends the loop: the acknowledged POM baseline stays quiet until the POMs change
  // again, even while the client's Maven job runs. Sync / Sync + capture ask the client to run
  // Maven
  // (the server never does); Later just acknowledges.
  private void onSyncPromptResponse(final MessageActionItem action) {
    pomNotificationPending = false;
    watcher.acknowledgePoms();
    // Acknowledge whatever is currently stale (the prompt may have been POM- or source-triggered),
    // so
    // a dismissed prompt stays quiet until a still-newer external change appears.
    acknowledgedSourceMtime = scanStaleModules().newestMtime();
    final String title = action == null ? null : action.getTitle();
    switch (title) {
      case SYNC_ACTION -> requestSync(false);
      case SYNC_CAPTURE_ACTION -> requestSync(true);
      case null, default -> {}
    }
  }

  private void requestSync(final boolean captureTests) {
    ((LatheLanguageClient) client)
        .sync(
            new LatheSyncParams(
                workspaceRoot.toString(), captureTests, syncScope(pendingSyncModules)));
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
      watcher.updatePomPaths(newManifest.pomPaths());
      pomNotificationPending = false;
    }

    final var newWorkspace = WorkspaceModuleRegistry.scan(workspaceRoot, newManifest);
    final var old = workspace;
    workspace = newWorkspace;
    manifest = newManifest;
    moduleGraph = WorkspaceModuleGraph.build(workspace.allConfigs());
    candidateIndex = ReferenceCandidateIndex.build(workspace.allConfigs());
    refreshReactorTypeIndex();
    refreshOpenDocuments();
    old.close();
    scheduleAllOpenFiles();
    client.showMessage(new MessageParams(MessageType.Info, "Lathe: workspace reloaded."));
  }

  // Re-scans the reactor mirror and rebuilds the type index without the full-reload work (no
  // candidate-index rebuild, no open-document refresh, no reload message).
  private void refreshReactorTypeIndex() {
    final var t = Stopwatch.start();
    reactorShards.clear();
    scanReactorShards();
    typeIndex = WorkspaceTypeIndex.build(manifest.typeIndexShardPaths(), reactorShards.values());
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
        : publisher.publishIfCurrent(snapshot, result);
  }

  private void submitCompile(final OpenDocument snapshot, final AfterCompile afterCompile) {
    submitCompile(routeCompiler(snapshot.uri()), snapshot, CompileMode.FAST, afterCompile);
  }

  private void submitCompile(
      final CompilerRoute route,
      final OpenDocument snapshot,
      final CompileMode mode,
      final AfterCompile afterCompile) {
    switch (route) {
      case CompilerRoute.Module module -> submitTo(module.worker(), snapshot, mode, afterCompile);
      case CompilerRoute.External external ->
          submitTo(external.worker(), snapshot, mode, afterCompile);
      case CompilerRoute.Missing missing ->
          publisher.publishMissing(missing.uri(), missing.message());
    }
  }

  private void submitTo(
      final CompilationWorker moduleWorker,
      final OpenDocument snapshot,
      final CompileMode mode,
      final AfterCompile afterCompile) {
    touchAnalysisCache(snapshot.uri());
    final var request =
        new CompileRequest(
            snapshot.uri(), snapshot.content(), snapshot.version(), snapshot.generation(), mode);
    moduleWorker
        .compile(request)
        .thenAccept(result -> worker.execute(() -> afterCompile.accept(snapshot, result)))
        .exceptionally(
            ex -> {
              worker.execute(() -> publisher.publishError(snapshot, mode, ex));
              return null;
            });
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

  private static List<String> completionModuleNames(final CompilerRoute route, final String uri) {
    if (!LatheLayout.MODULE_INFO_JAVA.equals(LatheUri.toPath(uri).getFileName().toString())) {
      return List.of();
    }

    return switch (route) {
      case CompilerRoute.Module module ->
          ModuleNameDiscovery.observableModuleNames(module.config());
      case CompilerRoute.External ignored -> List.of();
      case CompilerRoute.Missing ignored -> List.of();
    };
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
    return switch (routeCompiler(uri)) {
      case CompilerRoute.Module module -> {
        touchAnalysisCache(uri);
        yield operation.apply(module.worker());
      }
      case CompilerRoute.External external -> {
        touchAnalysisCache(uri);
        yield operation.apply(external.worker());
      }
      case CompilerRoute.Missing ignored -> CompletableFuture.completedFuture(missingFallback);
    };
  }

  private void afterModuleSave(
      final CompileResponse result, final ModuleSourceConfig config, final Path savedSource) {
    deleteStaleClassOutputs(config, savedSource, result.writtenBinaryNames());
    scheduleAstRefresh(result.uri());
    scheduleOpenFilesInModule(result.uri(), config);
    refreshReactorShard(config);
  }

  private AfterCompile publishThen(
      final CompilerRoute route, final Consumer<CompileResponse> followUp) {
    return (snapshot, result) -> {
      if (publishDiagnostics(route, snapshot, result)) {
        LOG.info(() -> "[save] compiled %s".formatted(snapshot.uri()));
        followUp.accept(result);
      }
    };
  }

  private AfterCompile publishThen(final CompilerRoute route, final Runnable followUp) {
    return (snapshot, result) -> {
      if (publishDiagnostics(route, snapshot, result)) {
        LOG.info(() -> "[save] compiled %s".formatted(snapshot.uri()));
        followUp.run();
      }
    };
  }

  private void scheduleOpenFilesInModule(
      final String savedUri, final ModuleSourceConfig savedModule) {
    LOG.fine(
        () ->
            "[save] checking %d open file(s) for dependents of %s"
                .formatted(docs.all().size(), savedUri));
    docs.all().stream()
        .map(OpenDocument::uri)
        .filter(uri -> !uri.equals(savedUri))
        .filter(
            uri ->
                workspace
                    .moduleSourceFor(LatheUri.toPath(uri))
                    .map(m -> m.moduleDir().equals(savedModule.moduleDir()))
                    .orElse(false))
        .forEach(this::scheduleOpenFile);
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
    record Module(CompilationWorker worker, ModuleSourceConfig config) implements CompilerRoute {}

    record External(CompilationWorker worker) implements CompilerRoute {}

    record Missing(String uri, String message) implements CompilerRoute {}
  }

  @FunctionalInterface
  private interface AfterCompile {
    void accept(OpenDocument snapshot, CompileResponse result);
  }
}
