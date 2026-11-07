package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.ExceptionUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.TestFilterData;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import io.github.aglibs.lathe.server.debug.DapHost;
import io.github.aglibs.lathe.server.debug.DebugStartResult;
import io.github.aglibs.lathe.server.debug.FrameSources;
import io.github.aglibs.lathe.server.debug.LatheProviderContext;
import io.github.aglibs.lathe.server.run.CompletenessGate;
import io.github.aglibs.lathe.server.run.CompletenessResult;
import io.github.aglibs.lathe.server.run.DiscoveredTest;
import io.github.aglibs.lathe.server.run.JdwpOptions;
import io.github.aglibs.lathe.server.run.LaunchOutcome;
import io.github.aglibs.lathe.server.run.LaunchReader;
import io.github.aglibs.lathe.server.run.LaunchSession;
import io.github.aglibs.lathe.server.run.Launcher;
import io.github.aglibs.lathe.server.run.MainClassScope;
import io.github.aglibs.lathe.server.run.PortUtil;
import io.github.aglibs.lathe.server.run.ResolvedLaunch;
import io.github.aglibs.lathe.server.run.RunConfigInfo;
import io.github.aglibs.lathe.server.run.RunConfigReader;
import io.github.aglibs.lathe.server.run.RunConfigWriter;
import io.github.aglibs.lathe.server.run.RunItem;
import io.github.aglibs.lathe.server.run.RunKind;
import io.github.aglibs.lathe.server.run.RunOverlay;
import io.github.aglibs.lathe.server.run.RunOverlaySet;
import io.github.aglibs.lathe.server.run.SourceSetScope;
import io.github.aglibs.lathe.server.run.TestDiscovery;
import io.github.aglibs.lathe.server.run.TestDiscoveryStore;
import io.github.aglibs.lathe.server.run.TestEventParams;
import io.github.aglibs.lathe.server.run.TestFinishedParams;
import io.github.aglibs.lathe.server.run.TestOutputParams;
import io.github.aglibs.lathe.server.run.TestResult;
import io.github.aglibs.lathe.server.run.TestSource;
import io.github.aglibs.lathe.server.run.TranscriptLine;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Runs and debugs tests and main classes by replaying their captured launches, discovers a module's
// tests, and keeps the named run configurations. Event-loop state: the live runs and debug hosts,
// the discoveries (TestDiscoveryStore) and their queue; launch and drain threads reach them only
// through the loop.
final class RunCoordinator {

  private static final Logger LOG = Logger.getLogger(RunCoordinator.class.getName());

  private static final long JDWP_READY_TIMEOUT_MS = 15_000;

  private final LatheLanguageClient client;
  private final ServerEventLoop eventLoop;
  private final WorkspaceState state;
  private final ReactorTypeIndex reactorIndex;
  // In-flight replays keyed by run token, from the moment a launch leaves the loop, so a cancel
  // reaches the right one. Loop-only: launch threads hand their JVM back through a rejoin.
  private final Map<String, RunSlot> activeRuns = new HashMap<>();
  private final Map<String, DapHost> activeDebugHosts = new HashMap<>();
  // The modules' test discoveries, and the last one queued: discovery JVMs run one at a time.
  private final TestDiscoveryStore discoveries = new TestDiscoveryStore();
  private CompletableFuture<TestDiscovery> lastDiscovery =
      CompletableFuture.completedFuture(new TestDiscovery("", List.of(), List.of()));

  RunCoordinator(
      final LatheLanguageClient client,
      final ServerEventLoop eventLoop,
      final WorkspaceState state,
      final ReactorTypeIndex reactorIndex) {
    this.client = client;
    this.eventLoop = eventLoop;
    this.state = state;
    this.reactorIndex = reactorIndex;
  }

  CompletableFuture<LaunchOutcome> runTestFuture(
      final String moduleRel, final List<TestSelection> selections, final String token) {
    eventLoop.checkOnLoop();
    return startTestRun(moduleRel, selections, token, baselineOverlay(moduleRel, RunKind.TEST));
  }

  CompletableFuture<LaunchOutcome> runMainFuture(
      final String moduleRel, final String mainClass, final String token) {
    eventLoop.checkOnLoop();
    return startMainRun(moduleRel, mainClass, token, baselineOverlay(moduleRel, RunKind.MAIN));
  }

  /**
   * Launches the selected test suspended under a JDWP agent and opens an in-process DAP host wired
   * to the module's source model, returning the ports the editor's debug client attaches to. The
   * launched JVM is tracked by {@code token} so {@link #cancelRun} kills it, and the DAP host is
   * closed when the debuggee exits.
   */
  CompletableFuture<DebugStartResult> debugTestFuture(
      final String moduleRel, final List<TestSelection> selections, final String token) {
    eventLoop.checkOnLoop();
    return startDebugTest(moduleRel, selections, token, baselineOverlay(moduleRel, RunKind.TEST));
  }

  /**
   * Launches the module's {@code mainClass} suspended under a JDWP agent and opens a DAP host — the
   * main-class twin of {@link #debugTestFuture}.
   */
  CompletableFuture<DebugStartResult> debugMainFuture(
      final String moduleRel, final String mainClass, final String token) {
    eventLoop.checkOnLoop();
    return startDebugMain(moduleRel, mainClass, token, baselineOverlay(moduleRel, RunKind.MAIN));
  }

  void cancelRun(final String token) {
    eventLoop.checkOnLoop();
    final RunSlot slot = activeRuns.get(token);
    if (slot == null) {
      LOG.fine(() -> "[cancel] %s no active run".formatted(token));
      return;
    }

    activeRuns.put(token, slot.cancel());
    if (slot.session() == null) {
      LOG.info(() -> "[cancel] %s before its JVM started".formatted(token));
      return;
    }

    LOG.info(() -> "[cancel] %s pid=%d".formatted(token, slot.session().pid()));
    slot.session().cancel();
  }

  private CompletableFuture<LaunchOutcome> startTestRun(
      final String moduleRel,
      final List<TestSelection> selections,
      final String token,
      final RunItem overlay) {
    final LaunchInputs inputs = launchInputs();
    return run(
        moduleRel,
        selectionLabel(selections),
        token,
        overlay,
        () -> prepareTest(inputs, moduleRel, selections, overlay, JdwpOptions.NONE));
  }

  private CompletableFuture<LaunchOutcome> startMainRun(
      final String moduleRel, final String mainClass, final String token, final RunItem overlay) {
    final LaunchInputs inputs = launchInputs();
    return run(
        moduleRel,
        mainClass,
        token,
        overlay,
        () -> prepareMain(inputs, moduleRel, mainClass, overlay, JdwpOptions.NONE));
  }

  private CompletableFuture<DebugStartResult> startDebugTest(
      final String moduleRel,
      final List<TestSelection> selections,
      final String token,
      final RunItem overlay) {
    final LaunchInputs inputs = launchInputs();
    final var jdwp = new JdwpOptions(IOUtil.unchecked(PortUtil::free));
    return debug(
        moduleRel,
        selectionLabel(selections),
        token,
        overlay,
        jdwp,
        () -> prepareTest(inputs, moduleRel, selections, overlay, jdwp));
  }

  private CompletableFuture<DebugStartResult> startDebugMain(
      final String moduleRel, final String mainClass, final String token, final RunItem overlay) {
    final LaunchInputs inputs = launchInputs();
    final var jdwp = new JdwpOptions(IOUtil.unchecked(PortUtil::free));
    return debug(
        moduleRel,
        mainClass,
        token,
        overlay,
        jdwp,
        () -> prepareMain(inputs, moduleRel, mainClass, overlay, jdwp));
  }

  private LaunchInputs launchInputs() {
    return new LaunchInputs(
        state.root(), state.manifest().reactorRewrite(), state.manifest().runnerClasspath());
  }

  // A run's outcome, with the launch's notes, once its JVM exits.
  private CompletableFuture<LaunchOutcome> run(
      final String moduleRel,
      final String label,
      final String token,
      final RunItem overlay,
      final IOUtil.IOSupplier<Replay> prepare) {
    requireFreeToken(token);
    final var t = Stopwatch.start();
    return spawn(moduleRel, label, token, overlay, streamConsumer(token), prepare)
        .thenCompose(this::outcome)
        .whenComplete(
            (outcome, error) ->
                endRun(moduleRel, label, overlay.configLabel(), token, t, outcome, error));
  }

  // Before the launch's end is chained: that end drops the token's slot, which here is another
  // launch's, and with it the way to cancel that one.
  private void requireFreeToken(final String token) {
    if (activeRuns.containsKey(token)) {
      throw new IllegalArgumentException(
          "a launch under token %s is still running".formatted(token));
    }
  }

  private CompletableFuture<LaunchOutcome> outcome(final Spawned spawned) {
    if (spawned.replay().blocked()) {
      return CompletableFuture.completedFuture(
          LaunchOutcome.blocked(spawned.replay().blockedReasons()));
    }

    return eventLoop
        .rejoin(spawned.session().onExit())
        .thenApply(outcome -> outcome.withNotes(spawned.replay().notes()));
  }

  private void endRun(
      final String moduleRel,
      final String label,
      final String configLabel,
      final String token,
      final Stopwatch t,
      final LaunchOutcome outcome,
      final Throwable error) {
    activeRuns.remove(token);
    if (error != null) {
      LOG.log(
          Level.WARNING,
          ExceptionUtil.completionCause(error),
          () -> "[launch] %s %s failed %dms".formatted(moduleRel, label, t.elapsedMs()));
      return;
    }

    if (outcome.launched()) {
      LOG.info(
          () ->
              "[launch] %s %s config=%s exit=%d %dms"
                  .formatted(moduleRel, label, configLabel, outcome.exitCode(), t.elapsedMs()));
    }
  }

  // A debug: its JVM spawned suspended under a JDWP agent, then a DAP host once the agent listens.
  private CompletableFuture<DebugStartResult> debug(
      final String moduleRel,
      final String label,
      final String token,
      final RunItem overlay,
      final JdwpOptions jdwp,
      final IOUtil.IOSupplier<Replay> prepare) {
    requireFreeToken(token);
    final var jdwpReady = new CompletableFuture<Void>();
    return spawn(
            moduleRel,
            label,
            token,
            overlay,
            jdwpReadyConsumer(jdwp, jdwpReady, streamConsumer(token)),
            prepare)
        .thenCompose(
            spawned -> attachDebugHost(moduleRel, label, token, spawned, jdwp.port(), jdwpReady));
  }

  // Prepares and spawns the launch on a thread of its own, so the loop never reads a launch file,
  // waits on a module lock or starts a process. The token is registered first: a cancel that comes
  // before the JVM exists is kept, and the JVM is cancelled as soon as it is back on the loop. A
  // blank token (test discovery, a caller with no surface listening) is not registered: nothing
  // can cancel it, and two such launches would share one slot.
  private CompletableFuture<Spawned> spawn(
      final String moduleRel,
      final String label,
      final String token,
      final RunItem overlay,
      final Consumer<TranscriptLine> onLine,
      final IOUtil.IOSupplier<Replay> prepare) {
    if (!token.isBlank()) {
      activeRuns.put(token, RunSlot.PENDING);
    }

    final Consumer<TestResult> onResult = resultConsumer(token);
    final CompletableFuture<Spawned> spawned =
        CompletableFuture.supplyAsync(
            () ->
                IOUtil.unchecked(
                    () ->
                        spawnPrepared(
                            moduleRel, label, overlay.configLabel(), onLine, onResult, prepare)),
            task -> launchThread(moduleRel, task).start());
    return eventLoop
        .rejoin(spawned)
        .whenComplete((result, error) -> register(token, result, error));
  }

  private static Thread launchThread(final String moduleRel, final Runnable task) {
    final var thread = new Thread(task, "lathe-launch-" + moduleRel);
    thread.setDaemon(true);
    return thread;
  }

  private static Spawned spawnPrepared(
      final String moduleRel,
      final String label,
      final String configLabel,
      final Consumer<TranscriptLine> onLine,
      final Consumer<TestResult> onResult,
      final IOUtil.IOSupplier<Replay> prepare)
      throws IOException {
    final Replay replay = prepare.get();
    if (replay.blocked()) {
      LOG.warning(
          () ->
              "[launch] %s %s blocked reasons=%s"
                  .formatted(moduleRel, label, replay.blockedReasons()));
      return new Spawned(replay, null);
    }

    replay
        .notes()
        .forEach(note -> onLine.accept(new TranscriptLine(TranscriptLine.Stream.STDERR, note)));
    final ResolvedLaunch resolved = replay.launch();
    return new Spawned(
        replay, Launcher.launch(resolved, configLabel, replay.resultsSink(), onLine, onResult));
  }

  private void register(final String token, final Spawned spawned, final Throwable error) {
    final RunSlot slot = activeRuns.get(token);
    if (error != null || spawned.replay().blocked() || token.isBlank()) {
      activeRuns.remove(token);
      return;
    }

    final boolean cancelled = slot != null && slot.cancelled();
    activeRuns.put(token, new RunSlot(spawned.session(), cancelled));
    if (cancelled) {
      LOG.info(
          () ->
              "[cancel] %s pid=%d cancelled before it started"
                  .formatted(token, spawned.session().pid()));
      spawned.session().cancel();
    }
  }

  // Whether nobody cancelled the launch under the token, nor did it end; a blank-token launch is
  // never registered, and nothing can cancel it.
  private boolean isLive(final String token) {
    final RunSlot slot = activeRuns.get(token);
    return token.isBlank() || slot != null && !slot.cancelled();
  }

  // A launch registered under its token: no session while it is still being prepared, and whether
  // a cancel came for it.
  private record RunSlot(LaunchSession session, boolean cancelled) {

    static final RunSlot PENDING = new RunSlot(null, false);

    // Nothing to check: pending or started, cancelled or not, every combination occurs.
    RunSlot {}

    RunSlot cancel() {
      return new RunSlot(session, true);
    }
  }

  // What a launch reads from the session: read on the loop, handed to the launch thread.
  private record LaunchInputs(
      Path root, ReactorRewrite reactorRewrite, List<Path> runnerClasspath) {

    LaunchInputs {
      ValidCheck.check().notNull(root, "root").validate();
      runnerClasspath = List.copyOf(runnerClasspath);
    }
  }

  // A resolved launch with the completeness gate's notes, or the reasons it is blocked. A main has
  // no results sink: it reports no test results.
  private record Replay(
      ResolvedLaunch launch, Path resultsSink, List<String> notes, List<String> blockedReasons) {

    Replay {
      notes = List.copyOf(notes);
      blockedReasons = List.copyOf(blockedReasons);
      ValidCheck.check()
          .assertTrue((launch == null) != blockedReasons.isEmpty(), "blockedReasons")
          .validate();
    }

    static Replay ready(
        final ResolvedLaunch launch, final Path resultsSink, final List<String> notes) {
      return new Replay(launch, resultsSink, notes, List.of());
    }

    static Replay blocked(final List<String> reasons) {
      return new Replay(null, null, List.of(), reasons);
    }

    boolean blocked() {
      return launch == null;
    }
  }

  // The launched JVM of a replay, or none when the replay is blocked.
  private record Spawned(Replay replay, LaunchSession session) {

    Spawned {
      ValidCheck.check()
          .notNull(replay, "replay")
          .assertTrue(replay.blocked() == (session == null), "session")
          .validate();
    }
  }

  private static Replay prepareTest(
      final LaunchInputs inputs,
      final String moduleRel,
      final List<TestSelection> selections,
      final RunItem overlay,
      final JdwpOptions jdwp)
      throws IOException {
    if (inputs.runnerClasspath().isEmpty()) {
      return Replay.blocked(List.of("no lathe-test-runner jar recorded — run a build first"));
    }

    final List<String> trees = selectionTrees(inputs.root(), moduleRel, selections);
    if (trees.size() > 1) {
      return Replay.blocked(
          List.of(
              "the selected tests are in more than one source set of %s (%s): run each on its own"
                  .formatted(
                      moduleRel,
                      trees.stream()
                          .map(RunCoordinator::sourceSetLabel)
                          .collect(Collectors.joining(", ")))));
    }

    final CheckedTestLaunch captured =
        checkedTestLaunch(
            inputs.root(),
            inputs.reactorRewrite(),
            moduleRel,
            selections,
            trees.isEmpty() ? LatheLayout.TEST_CLASSES_DIR : trees.get(0));
    if (!captured.gate().complete()) {
      return Replay.blocked(captured.gate().reasons());
    }

    final Path resultsSink = Files.createTempFile("lathe-results-", ".ndjson");
    return Replay.ready(
        RunOverlay.applyToTest(
            captured.template(),
            inputs.root(),
            inputs.reactorRewrite(),
            inputs.runnerClasspath(),
            withTestFilter(inputs.root(), moduleRel, selections),
            resultsSink,
            overlay,
            jdwp),
        resultsSink,
        captured.gate().notes());
  }

  /**
   * Prepares a {@code main} run/debug, choosing the template by the class's source scope: a {@code
   * main} in the module's test sources runs against the captured test template (its test-classes
   * patched into the module, the full test-scope graph), with the user's class as the entry point
   * instead of the JUnit runner; a main-scope class uses the derived main template.
   */
  private static Replay prepareMain(
      final LaunchInputs inputs,
      final String moduleRel,
      final String mainClass,
      final RunItem overlay,
      final JdwpOptions jdwp)
      throws IOException {
    final Path root = inputs.root();
    final ReactorRewrite reactorRewrite = inputs.reactorRewrite();
    // A main in test sources, or in a source set's own tree, runs on that tree's test launch.
    final Optional<String> sourceSetTree = SourceSetScope.treeOf(root, moduleRel, mainClass);
    if (sourceSetTree.isPresent() || MainClassScope.isTestScope(root, moduleRel, mainClass)) {
      final CheckedTestLaunch captured =
          checkedTestLaunch(
              root,
              reactorRewrite,
              moduleRel,
              List.of(),
              sourceSetTree.orElse(LatheLayout.TEST_CLASSES_DIR));
      if (!captured.gate().complete()) {
        return Replay.blocked(captured.gate().reasons());
      }

      return Replay.ready(
          RunOverlay.applyToTestMain(
              captured.template(), root, reactorRewrite, mainClass, overlay, jdwp),
          null,
          captured.gate().notes());
    }

    final Optional<MainLaunchData> template = new LaunchReader(root).main(moduleRel);
    if (template.isEmpty()) {
      return Replay.blocked(List.of("no derived main-launch.json for " + moduleRel));
    }

    final CompletenessResult gate = CompletenessGate.verify(template.get(), root, reactorRewrite);
    if (!gate.complete()) {
      return Replay.blocked(gate.reasons());
    }

    return Replay.ready(
        RunOverlay.applyToMain(template.get(), root, reactorRewrite, mainClass, overlay, jdwp),
        null,
        gate.notes());
  }

  /**
   * Shared debug tail: wait for the launched JVM's JDWP agent to start listening, open the DAP host
   * wired to the workspace's source routing, and return the ports. The host is closed when the
   * debuggee exits.
   */
  private CompletableFuture<DebugStartResult> attachDebugHost(
      final String moduleRel,
      final String label,
      final String token,
      final Spawned spawned,
      final int jdwpPort,
      final CompletableFuture<Void> jdwpReady) {
    if (spawned.replay().blocked()) {
      throw new IllegalStateException(
          "debug launch incomplete: " + spawned.replay().blockedReasons());
    }

    // One future: onExit() builds a new one, and reads the results file, on every call.
    final CompletableFuture<LaunchOutcome> exited = spawned.session().onExit();
    eventLoop
        .rejoin(exited)
        .whenComplete(
            (outcome, error) -> {
              publishTestFinished(
                  token,
                  outcome == null ? null : outcome.withNotes(spawned.replay().notes()),
                  error == null ? null : ExceptionUtil.completionCause(error));
              endDebugSession(token);
            });

    // Launcher spawns the JVM and returns before its -agentlib:jdwp socket is listening. Gate on
    // the agent's own "Listening for transport ... at address:" banner (surfaced through the drain
    // and completed onto jdwpReady by jdwpReadyConsumer) rather than probing the port with a
    // throwaway TCP connect, which the agent reports as a failed debugger handshake. The DapHost's
    // java-debug connection is then the only connection the agent ever sees, so the editor's attach
    // (once, no retry) never races a probe. suspend=y guarantees the banner prints shortly, then
    // parks; a JVM that exits first (a bad JVM option, say) ends the wait at once. The wait rejoins
    // the loop instead of blocking it while the JVM starts.
    return eventLoop
        .rejoin(
            CompletableFuture.anyOf(jdwpReady, exited)
                .orTimeout(JDWP_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        .handle(
            (first, failure) -> {
              // A suspended debuggee nobody will attach to would stay parked: it is cancelled,
              // and its exit then publishes the outcome and clears the token.
              if (!jdwpReady.isDone() && !exited.isDone()) {
                spawned.session().cancel();
                throw new IllegalStateException(
                    "jdwp agent did not start listening on port %d within %dms"
                        .formatted(jdwpPort, JDWP_READY_TIMEOUT_MS),
                    failure);
              }

              // The debuggee may have exited, or been cancelled, while this waited for the loop:
              // a host opened now would be closed at once, and the editor's one attach refused.
              if (!jdwpReady.isDone() || exited.isDone() || !isLive(token)) {
                spawned.session().cancel();
                throw new IllegalStateException(
                    "the debuggee ended before the debugger attached on port %d"
                        .formatted(jdwpPort));
              }

              return openDebugHost(moduleRel, token, jdwpPort, label);
            });
  }

  private DebugStartResult openDebugHost(
      final String moduleRel, final String token, final int jdwpPort, final String label) {
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
                state.registry().allSourceRoots(),
                state.manifest().libraries().dependencySourceDirs(),
                state.manifest().libraries().jdkModuleSourceDirs())
            .flatMap(List::stream)
            .toList();
    final DapHost host;
    try {
      host =
          DapHost.start(
              new LatheProviderContext(
                  new FrameSources(
                      task -> eventLoop.submit(Executors.callable(task)),
                      file -> state.registry().moduleWorkerFor(file),
                      debugSourceRoots),
                  reactorIndex.current()),
              () -> eventLoop.execute(() -> cancelRun(token)));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    activeDebugHosts.put(token, host);
    LOG.info(
        () -> "[debug] %s %s dap=%d jdwp=%d".formatted(moduleRel, label, host.port(), jdwpPort));
    return new DebugStartResult(host.port(), jdwpPort);
  }

  // The trees a run's selections are in: a class's own (a source set's, else test's); a package or
  // module run, test's. Class-name filters select nothing of their own.
  private static List<String> selectionTrees(
      final Path workspaceRoot, final String moduleRel, final List<TestSelection> selections) {
    return selections.stream()
        .filter(
            selection ->
                selection.kind() != TestSelectionKind.CLASS_NAME_INCLUDE
                    && selection.kind() != TestSelectionKind.CLASS_NAME_EXCLUDE)
        .map(
            selection ->
                selection.kind() == TestSelectionKind.CLASS
                        || selection.kind() == TestSelectionKind.METHOD
                    ? SourceSetScope.treeOf(
                            workspaceRoot, moduleRel, selection.value().split("#", 2)[0])
                        .orElse(LatheLayout.TEST_CLASSES_DIR)
                    : LatheLayout.TEST_CLASSES_DIR)
        .distinct()
        .toList();
  }

  // "test", or the source set a tree is named after.
  private static String sourceSetLabel(final String tree) {
    return LatheLayout.TEST_CLASSES_DIR.equals(tree) ? "test" : LatheLayout.sourceSetName(tree);
  }

  // A tree's captured test launch, checked by the completeness gate. A tree with no capture gets an
  // incomplete gate whose reason says how to capture one, and no template.
  private static CheckedTestLaunch checkedTestLaunch(
      final Path workspaceRoot,
      final ReactorRewrite reactorRewrite,
      final String moduleRel,
      final List<TestSelection> selections,
      final String tree)
      throws IOException {
    final Optional<TestLaunchData> template = new LaunchReader(workspaceRoot).test(moduleRel, tree);
    if (template.isEmpty()) {
      return new CheckedTestLaunch(
          null,
          CompletenessResult.blocked(
              List.of(noCapturedLaunch(workspaceRoot, moduleRel, selections, tree))));
    }

    return new CheckedTestLaunch(
        template.get(), CompletenessGate.verify(template.get(), workspaceRoot, reactorRewrite));
  }

  // template is null when the gate is incomplete because nothing was captured.
  private record CheckedTestLaunch(TestLaunchData template, CompletenessResult gate) {
    CheckedTestLaunch {
      ValidCheck.check().notNull(gate, "gate").validate();
    }
  }

  /**
   * Publishes the debug session's final {@link LaunchOutcome} to the client over its run token —
   * the debug twin of the {@code lathe.run.test} command response. The debug launch returns its DAP
   * ports immediately, long before the debuggee exits, so this session-end notification is the only
   * way the client learns the run's aggregate result and can complete its results reconciliation
   * (gutters, summary, pass/fail). A blank token (no live surface listening) is a no-op; a null
   * outcome (the JVM died before producing one) is reported as blocked so the client never waits
   * forever. Fires for both {@code debugTestFuture} and {@code debugMainFuture}; a main debug
   * carries no {@code testResults}, which the client ignores for a token it never registered.
   */
  private void publishTestFinished(
      final String token, final LaunchOutcome outcome, final Throwable error) {
    if (token.isBlank()) {
      return;
    }

    final LaunchOutcome finished = finishedOutcome(outcome, error);
    LOG.fine(() -> "[debug] %s finished exit=%d".formatted(token, finished.exitCode()));
    client.testFinished(new TestFinishedParams(token, finished));
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

  // The tests a run of the module would execute, found by a discover-mode launch that runs none of
  // them. Answered from the cache until the module's test classes change (sync and save compiles
  // rewrite its test-classes stamp); a client asking for many modules gets a queue, not a JVM each.
  CompletableFuture<TestDiscovery> discoverTestsFuture(final String moduleRel) {
    eventLoop.checkOnLoop();
    return discoveries.discovery(state.root(), moduleRel, () -> queuedDiscovery(moduleRel));
  }

  // Discovery JVMs run one at a time: each starts once the one queued before it ends.
  private CompletableFuture<TestDiscovery> queuedDiscovery(final String moduleRel) {
    final CompletableFuture<TestDiscovery> result =
        eventLoop
            .rejoin(lastDiscovery)
            .handle((previous, error) -> moduleRel)
            .thenCompose(this::launchDiscovery);
    lastDiscovery = result;
    return result;
  }

  private CompletableFuture<TestDiscovery> launchDiscovery(final String moduleRel) {
    final List<TestSelection> selections =
        state.layout().moduleRun(moduleRel).selections().stream()
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
        .thenApply(outcome -> discovery(moduleRel, outcome));
  }

  private TestDiscovery discovery(final String moduleRel, final LaunchOutcome outcome) {
    final Map<String, String> paths =
        state
            .layout()
            .testSources(
                moduleRel, outcome.testResults().stream().map(TestResult::className).toList())
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

  private static String sourceUri(final String path) {
    return path.isEmpty() ? "" : LatheUri.fromPath(Path.of(path));
  }

  private static String selectionLabel(final List<TestSelection> selections) {
    return selections.stream().map(TestSelection::value).collect(Collectors.joining(", "));
  }

  /**
   * Streams each drained transcript line to the client over the run token so the client can render
   * it live. Fired from {@code LaunchSession}'s drain threads, not the event loop: the payload is
   * immutable and carries no session state, and lsp4j serializes the writes, so publishing off the
   * event loop is safe here. A blank token (no live surface listening) yields a no-op.
   */
  private Consumer<TranscriptLine> streamConsumer(final String token) {
    if (token.isBlank()) {
      return line -> {};
    }

    return line -> client.testOutput(new TestOutputParams(token, line));
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
   * mark that position live mid-run. Same off-loop publication rationale as {@link
   * #streamConsumer}; a blank token yields a no-op.
   */
  private Consumer<TestResult> resultConsumer(final String token) {
    if (token.isBlank()) {
      return result -> {};
    }

    return result -> client.testEvent(new TestEventParams(token, result));
  }

  // With the command that captures it, so a reader (an agent above all) knows the remedy. A class
  // the build's test filter leaves out (an integration test, say) is never captured, so naming a
  // sync for it would send the reader round in circles.
  private static String noCapturedLaunch(
      final Path workspaceRoot,
      final String moduleRel,
      final List<TestSelection> selections,
      final String tree) {
    // The editor's sync does not run a source set's test task, so it is never the remedy.
    if (!LatheLayout.TEST_CLASSES_DIR.equals(tree)) {
      return "no captured launch of the %s tests of %s: the test task that runs them captures it whenever it runs (without running the tests with -Plathe.capture.only=true); a source set no test task runs (jmh, say) runs with the build"
          .formatted(LatheLayout.sourceSetName(tree), moduleRel);
    }

    final boolean classesOnly =
        !selections.isEmpty()
            && selections.stream()
                .allMatch(
                    selection ->
                        selection.kind() == TestSelectionKind.CLASS
                            || selection.kind() == TestSelectionKind.METHOD);
    final List<String> classes =
        selections.stream().map(selection -> selection.value().split("#")[0]).distinct().toList();
    final Optional<TestFilterData> filter =
        IOUtil.unchecked(() -> new LaunchReader(workspaceRoot).testFilter(moduleRel));
    if (classesOnly && filter.isPresent() && classes.stream().noneMatch(filter.get()::includes)) {
      return "%s: the build's test filter for %s leaves it out (an integration test, for example), so no capture includes it and Lathe cannot run it; run it with the build"
          .formatted(String.join(", ", classes), moduleRel);
    }

    return "no captured test launch for %s; run `%s` to capture it"
        .formatted(moduleRel, LatheLayout.captureTestsCommand(moduleRel));
  }

  CompletableFuture<LaunchOutcome> runNamedFuture(final String name, final String token) {
    eventLoop.checkOnLoop();
    final RunItem config = resolveConfig(name);
    return switch (config.kind()) {
      case MAIN -> startMainRun(config.module(), config.mainClass(), token, config);
      case TEST -> startTestRun(config.module(), config.selectors(), token, config);
    };
  }

  CompletableFuture<DebugStartResult> debugNamedFuture(final String name, final String token) {
    eventLoop.checkOnLoop();
    final RunItem config = resolveConfig(name);
    return switch (config.kind()) {
      case MAIN -> startDebugMain(config.module(), config.mainClass(), token, config);
      case TEST -> startDebugTest(config.module(), config.selectors(), token, config);
    };
  }

  List<RunConfigInfo> listRunConfigs() {
    eventLoop.checkOnLoop();
    return readRunConfig().configs().stream().map(RunCoordinator::toInfo).toList();
  }

  RunConfigWriter.Saved saveRunConfig(
      final String name,
      final String moduleRel,
      final RunKind kind,
      final String mainClass,
      final List<TestSelection> selectors,
      final boolean overwrite)
      throws IOException {
    eventLoop.checkOnLoop();
    return new RunConfigWriter(state.root())
        .save(name, moduleRel, kind, mainClass, selectors, overwrite);
  }

  // Read fresh every call so an edited config file takes effect on the next run without a reload.
  private RunOverlaySet readRunConfig() {
    return new RunConfigReader(state.root()).read();
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
}
