package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.PortUtil;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.launch.JdwpOptions;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.RunKind;
import io.github.aglibs.lathe.core.schema.TestFilterData;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import io.github.aglibs.lathe.server.debug.DapHost;
import io.github.aglibs.lathe.server.debug.DebugStartResult;
import io.github.aglibs.lathe.server.debug.LatheProviderContext;
import io.github.aglibs.lathe.server.module.CompilationWorker;
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
import io.github.aglibs.lathe.server.run.TestEventParams;
import io.github.aglibs.lathe.server.run.TestFinishedParams;
import io.github.aglibs.lathe.server.run.TestOutputParams;
import io.github.aglibs.lathe.server.run.TestResult;
import io.github.aglibs.lathe.server.run.TranscriptLine;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.services.LanguageClient;

// Runs and debugs tests and main classes by replaying their captured launches, discovers a module's
// tests, and keeps the named run configurations. Event-loop state: the live runs and debug hosts,
// and the discovery cache; launch and drain threads reach them only through the loop.
final class RunCoordinator {

  private static final Logger LOG = Logger.getLogger(RunCoordinator.class.getName());

  private static final long JDWP_READY_TIMEOUT_MS = 15_000;

  private final LanguageClient client;
  private final ServerEventLoop eventLoop;
  private final WorkspaceState state;
  private final ReactorTypeIndex reactorIndex;
  // In-flight replays keyed by run token, so a cancel can reach the right LaunchSession. A plain
  // map, not a concurrent one: it is mutated only on the event loop. The run and exit threads
  // marshal put/remove via eventLoop.execute (see runTestFuture) rather than touching it directly.
  private final Map<String, LaunchSession> activeRuns = new HashMap<>();
  private final Map<String, DapHost> activeDebugHosts = new HashMap<>();
  // Test discoveries per module, valid while the module's test-classes stamp is unchanged, and the
  // last one queued: discovery JVMs run one at a time. Both touched on the event loop only.
  private final Map<String, CachedDiscovery> discoveries = new HashMap<>();
  private CompletableFuture<TestDiscovery> lastDiscovery =
      CompletableFuture.completedFuture(new TestDiscovery("", List.of(), List.of()));

  RunCoordinator(
      final LanguageClient client,
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
    return startTestRun(moduleRel, selections, token, baselineOverlay(moduleRel, RunKind.TEST));
  }

  private CompletableFuture<LaunchOutcome> startTestRun(
      final String moduleRel,
      final List<TestSelection> selections,
      final String token,
      final RunItem overlay) {
    final List<Path> runnerClasspath = state.manifest().runnerClasspath();
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
    final Path root = state.root();
    final ReactorRewrite reactorRewrite = state.manifest().reactorRewrite();
    final var t = Stopwatch.start();
    final var result = new CompletableFuture<LaunchOutcome>();
    // Register/deregister the run by token so a cancel can reach it. Marshaled onto the event loop
    // so
    // activeRuns keeps the single-threaded discipline of every other field. Removal covers the
    // blocked/error paths too (removing an unregistered token is a no-op).
    result.whenComplete((outcome, error) -> eventLoop.execute(() -> activeRuns.remove(token)));
    final Consumer<LaunchSession> onStart =
        session -> eventLoop.execute(() -> activeRuns.put(token, session));

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
   * on the event loop; the launched JVM is tracked by {@code token} so {@link #cancelRun} kills it,
   * and the DAP host is closed when the debuggee exits.
   */
  CompletableFuture<DebugStartResult> debugTest(
      final String moduleRel, final List<TestSelection> selections, final String token) {
    return startDebugTest(moduleRel, selections, token, baselineOverlay(moduleRel, RunKind.TEST));
  }

  private CompletableFuture<DebugStartResult> startDebugTest(
      final String moduleRel,
      final List<TestSelection> selections,
      final String token,
      final RunItem overlay) {
    final List<Path> runnerClasspath = state.manifest().runnerClasspath();
    if (runnerClasspath.isEmpty()) {
      throw new IllegalStateException("no lathe-test-runner jar recorded — run a build first");
    }

    try {
      final TestLaunchData template =
          new LaunchReader(state.root())
              .test(moduleRel)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          noCapturedLaunch(state.root(), moduleRel, selections)));
      assertLaunchComplete(
          CompletenessGate.verify(template, state.root(), state.manifest().reactorRewrite()));

      final int jdwpPort = PortUtil.free();
      final var jdwp = new JdwpOptions(jdwpPort);
      final var jdwpReady = new CompletableFuture<Void>();
      final Path resultsSink = Files.createTempFile("lathe-results-", ".ndjson");
      final ResolvedLaunch resolved =
          RunOverlay.applyToTest(
              template,
              state.root(),
              state.manifest().reactorRewrite(),
              runnerClasspath,
              withTestFilter(state.root(), moduleRel, selections),
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
  CompletableFuture<DebugStartResult> debugMain(
      final String moduleRel, final String mainClass, final String token) {
    return startDebugMain(moduleRel, mainClass, token, baselineOverlay(moduleRel, RunKind.MAIN));
  }

  private CompletableFuture<DebugStartResult> startDebugMain(
      final String moduleRel, final String mainClass, final String token, final RunItem overlay) {
    try {
      final int jdwpPort = PortUtil.free();
      final var jdwp = new JdwpOptions(jdwpPort);
      final MainLaunchPlan plan =
          resolveMainLaunch(
              state.root(), state.manifest().reactorRewrite(), moduleRel, mainClass, overlay, jdwp);
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

    // A null launch is the blocked plan.
    MainLaunchPlan {
      blockedReasons = List.copyOf(blockedReasons);
    }

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
        return MainLaunchPlan.blocked(
            List.of(noCapturedLaunch(workspaceRoot, moduleRel, List.of())));
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
  private CompletableFuture<DebugStartResult> attachDebugHost(
      final String moduleRel,
      final String token,
      final LaunchSession session,
      final int jdwpPort,
      final CompletableFuture<Void> jdwpReady,
      final String label) {
    activeRuns.put(token, session);
    session
        .onExit()
        .whenComplete(
            (outcome, error) ->
                eventLoop.execute(
                    () -> {
                      publishTestFinished(token, outcome, error);
                      endDebugSession(token);
                    }));

    // Launcher spawns the JVM and returns before its -agentlib:jdwp socket is listening. Gate on
    // the agent's own "Listening for transport ... at address:" banner (surfaced through the drain
    // and completed onto jdwpReady by jdwpReadyConsumer) rather than probing the port with a
    // throwaway TCP connect, which the agent reports as a failed debugger handshake. The DapHost's
    // java-debug connection is then the only connection the agent ever sees, so the editor's attach
    // (once, no retry) never races a probe. suspend=y guarantees the banner prints shortly, then
    // parks. The wait rejoins the loop instead of blocking it while the JVM starts.
    return eventLoop
        .rejoin(jdwpReady.orTimeout(JDWP_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        .handle(
            (ready, failure) -> {
              if (failure != null) {
                throw new IllegalStateException(
                    "jdwp agent did not start listening on port %d within %dms"
                        .formatted(jdwpPort, JDWP_READY_TIMEOUT_MS),
                    failure);
              }

              // The debuggee may have exited, or been cancelled, while this waited for the loop:
              // a host opened now would never be closed.
              if (!activeRuns.containsKey(token)) {
                throw new IllegalStateException(
                    "the debuggee exited before the debugger attached on port %d"
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
                state.manifest().libraries().depSourceDirs(),
                state.manifest().libraries().jdkModuleSourceDirs())
            .flatMap(List::stream)
            .toList();
    final DapHost host;
    try {
      host =
          DapHost.start(
              new LatheProviderContext(
                  this::debugWorkerFor, debugSourceRoots, reactorIndex.current()),
              () -> eventLoop.execute(() -> cancelRun(token)));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    activeDebugHosts.put(token, host);
    LOG.info(
        () -> "[debug] %s %s dap=%d jdwp=%d".formatted(moduleRel, label, host.port(), jdwpPort));
    return new DebugStartResult(host.port(), jdwpPort);
  }

  // Called on the debug adapter's threads: reads the workspace on the event loop like every other
  // access, and always the current registry, since a sync replaces it mid-session. Cancelled (so
  // the debugger request fails) once the session has closed.
  private Optional<CompilationWorker> debugWorkerFor(final Path file) {
    return eventLoop
        .submit(() -> state.registry().moduleSourceFor(file).map(state.registry()::workerFor))
        .join();
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

  // The tests a run of the module would execute, found by a discover-mode launch that runs none of
  // them. Answered from the cache until the module's test classes change (sync and save compiles
  // rewrite its test-classes stamp); a client asking for many modules gets a queue, not a JVM each.
  CompletableFuture<TestDiscovery> discoverTests(final String moduleRel) {
    final FileTime stamp = testClassesStamp(moduleRel);
    final CachedDiscovery cached = discoveries.get(moduleRel);
    if (cached != null && Objects.equals(cached.stamp(), stamp)) {
      return cached.result();
    }

    final Path persisted =
        SourceLayout.moduleLatheDir(state.root(), moduleRel)
            .resolve(LatheLayout.TEST_DISCOVERY_FILE);
    final Optional<TestDiscovery> stored = readDiscovery(persisted, stamp);
    if (stored.isPresent()) {
      final var result = CompletableFuture.completedFuture(stored.get());
      discoveries.put(moduleRel, new CachedDiscovery(stamp, result));
      return result;
    }

    final CompletableFuture<TestDiscovery> result =
        eventLoop
            .rejoin(lastDiscovery)
            .handle((previous, error) -> moduleRel)
            .thenCompose(this::launchDiscovery)
            .thenApply(discovery -> writeDiscovery(persisted, stamp, discovery));
    lastDiscovery = result;
    discoveries.put(moduleRel, new CachedDiscovery(stamp, result));
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
    return eventLoop
        .rejoin(startTestRun(moduleRel, selections, "", overlay))
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
        SourceLayout.moduleLatheDir(state.root(), moduleRel)
            .resolve(LatheLayout.compiledStampsFileName(LatheLayout.TEST_CLASSES_DIR));
    return Files.exists(stamps) ? IOUtil.unchecked(() -> Files.getLastModifiedTime(stamps)) : null;
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
   * mark that position live mid-run. Same off-loop publication rationale as {@link
   * #streamConsumer}; a blank token yields a no-op.
   */
  private Consumer<TestResult> resultConsumer(final String token) {
    if (token.isBlank()) {
      return result -> {};
    }

    return result -> ((LatheLanguageClient) client).testEvent(new TestEventParams(token, result));
  }

  // With the command that captures it, so a reader (an agent above all) knows the remedy. A class
  // the build's test filter leaves out (an integration test, say) is never captured, so naming a
  // sync for it would send the reader round in circles.
  private static String noCapturedLaunch(
      final Path workspaceRoot, final String moduleRel, final List<TestSelection> selections) {
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
        result.complete(
            LaunchOutcome.blocked(List.of(noCapturedLaunch(workspaceRoot, moduleRel, selections))));
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

      gate.notes()
          .forEach(note -> onLine.accept(new TranscriptLine(TranscriptLine.Stream.STDERR, note)));
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
                  completeRun(
                      moduleRel,
                      label,
                      overlay.configLabel(),
                      t,
                      result,
                      outcome == null ? null : outcome.withNotes(gate.notes()),
                      error));
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
    final Path root = state.root();
    final ReactorRewrite reactorRewrite = state.manifest().reactorRewrite();
    final var t = Stopwatch.start();
    final var result = new CompletableFuture<LaunchOutcome>();
    result.whenComplete((outcome, error) -> eventLoop.execute(() -> activeRuns.remove(token)));
    final Consumer<LaunchSession> onStart =
        session -> eventLoop.execute(() -> activeRuns.put(token, session));

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

  CompletableFuture<DebugStartResult> debugNamed(final String name, final String token) {
    final RunItem config = resolveConfig(name);
    return switch (config.kind()) {
      case MAIN -> startDebugMain(config.module(), config.mainClass(), token, config);
      case TEST -> startDebugTest(config.module(), config.selectors(), token, config);
    };
  }

  List<RunConfigInfo> listRunConfigs() {
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
