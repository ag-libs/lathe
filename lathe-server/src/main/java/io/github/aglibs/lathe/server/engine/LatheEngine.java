package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import io.github.aglibs.lathe.server.LatheTextDocumentService;
import io.github.aglibs.lathe.server.LatheUri;
import io.github.aglibs.lathe.server.ModulePlacement;
import io.github.aglibs.lathe.server.ReconcileOutcome;
import io.github.aglibs.lathe.server.analysis.RenameProvider;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import io.github.aglibs.lathe.server.run.RunTarget;
import io.github.aglibs.lathe.server.run.RunnableKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

/**
 * In-process facade into the language server for non-editor clients (the MCP server): it drives the
 * same workspace-session analysis the LSP handlers do, reading each file from disk so it reflects
 * out-of-process edits, and returns results directly instead of over JSON-RPC.
 */
public final class LatheEngine {

  private static final long REQUEST_TIMEOUT_SECONDS = 30;
  private static final int SNIPPET_CONTEXT_LINES = 3;

  // analyze_change classifies every reference, so it needs the full set, not a page. The cap is a
  // safety bound; LatheChangeImpact.referencesTruncated flags when it is exceeded.
  private static final int IMPACT_MAX_RESULTS = 1000;

  // Every put creates a fresh generation, so the LSP version is irrelevant to freshness here.
  private static final int VERSION = 1;

  private final LatheTextDocumentService service = new LatheTextDocumentService();
  private final AtomicLong runToken = new AtomicLong();
  private final Path workspaceRoot;
  private final Path latheDir;

  /**
   * What a {@link #runTest} call replays — the adapter between the MCP {@code scope} wire value and
   * the substrate's runnable/selection kinds, which live in different modules.
   */
  public enum TestScope {
    CLASS(RunnableKind.TEST_CLASS, TestSelectionKind.CLASS),
    METHOD(RunnableKind.TEST_METHOD, TestSelectionKind.METHOD),
    PACKAGE(RunnableKind.TEST_PACKAGE, TestSelectionKind.PACKAGE);

    private final RunnableKind runnableKind;
    private final TestSelectionKind selectionKind;

    TestScope(final RunnableKind runnableKind, final TestSelectionKind selectionKind) {
      this.runnableKind = runnableKind;
      this.selectionKind = selectionKind;
    }

    public RunnableKind runnableKind() {
      return runnableKind;
    }

    public TestSelectionKind selectionKind() {
      return selectionKind;
    }

    public String wireName() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<TestScope> from(final String wireName) {
      return Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(wireName)).findFirst();
    }
  }

  /** Which way {@link #callHierarchy} walks: callers of the symbol, or callees it invokes. */
  public enum CallDirection {
    INCOMING,
    OUTGOING;

    public static Optional<CallDirection> from(final String wireName) {
      return Arrays.stream(values()).filter(d -> d.name().equalsIgnoreCase(wireName)).findFirst();
    }
  }

  public LatheEngine(final Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
    this.latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    service.connect(new NoopLanguageClient());
    // The engine moves files itself, so a public type's rename can move its declaring file.
    service.setFileRenameSupported(true);
    service.initialize(workspaceRoot);
  }

  /** Compiler diagnostics for {@code file} at its current on-disk content. */
  public List<Diagnostic> diagnostics(final Path file) {
    return compileFromDisk(file);
  }

  /**
   * Definition target(s) for the symbol at {@code line}/{@code column} (0-based), each enriched
   * with a source snippet and where it resolved.
   */
  public List<LatheLocation> definition(final Path file, final int line, final int column) {
    compileFromDisk(file); // register the file and warm its analysis before resolving
    final var params =
        new DefinitionParams(
            new TextDocumentIdentifier(file.toUri().toString()), new Position(line, column));
    final var either = await(service.definition(params));
    final List<? extends Location> locations = either.isLeft() ? either.getLeft() : List.of();
    return locations.stream().map(this::toLatheLocation).toList();
  }

  /**
   * References to the symbol at {@code line}/{@code column} (0-based) across the reactor, each
   * enriched with a snippet, capped to {@code maxResults} with the true total for pagination.
   */
  public LatheReferences references(
      final Path file, final int line, final int column, final int maxResults) {
    compileFromDisk(file); // register the file and warm its analysis before searching
    return referencesAt(file, line, column, maxResults);
  }

  private LatheReferences referencesAt(
      final Path file, final int line, final int column, final int maxResults) {
    final var params = new ReferenceParams();
    params.setTextDocument(new TextDocumentIdentifier(file.toUri().toString()));
    params.setPosition(new Position(line, column));
    params.setContext(new ReferenceContext(true));
    final List<? extends Location> locations = await(service.references(params));
    final List<LatheLocation> capped =
        locations.stream().limit(maxResults).map(this::toLatheLocation).toList();
    return new LatheReferences(locations.size(), locations.size() > maxResults, capped);
  }

  /**
   * Implementations of the interface (or overrides of the method) at {@code line}/{@code column}
   * (0-based) across the reactor, each enriched with a snippet, capped to {@code maxResults}.
   */
  public LatheImplementations findImplementations(
      final Path file, final int line, final int column, final int maxResults) {
    compileFromDisk(file); // register the file and warm its analysis before resolving
    return implementationsAt(file, line, column, maxResults);
  }

  private LatheImplementations implementationsAt(
      final Path file, final int line, final int column, final int maxResults) {
    final var params =
        new ImplementationParams(
            new TextDocumentIdentifier(file.toUri().toString()), new Position(line, column));
    final var either = await(service.implementation(params));
    final List<? extends Location> locations = either.isLeft() ? either.getLeft() : List.of();
    final List<LatheLocation> capped =
        locations.stream().limit(maxResults).map(this::toLatheLocation).toList();
    return new LatheImplementations(locations.size(), locations.size() > maxResults, capped);
  }

  /**
   * The hover markdown for the symbol at {@code line}/{@code column} (0-based) — rendered signature
   * and javadoc — or empty when there is nothing to describe.
   */
  public String describe(final Path file, final int line, final int column) {
    compileFromDisk(file); // register the file and warm its analysis before resolving
    return hoverAt(file, line, column);
  }

  private String hoverAt(final Path file, final int line, final int column) {
    final var params =
        new HoverParams(
            new TextDocumentIdentifier(file.toUri().toString()), new Position(line, column));
    final Hover hover = await(service.hover(params));
    if (hover == null || hover.getContents() == null || !hover.getContents().isRight()) {
      return "";
    }

    return hover.getContents().getRight().getValue();
  }

  /**
   * Name-addressed symbol search across the reactor, dependencies, and the JDK (an index query, no
   * per-file warmup), each hit enriched with a snippet and where it lives, capped to {@code
   * maxResults}.
   */
  public List<LatheSymbol> searchSymbols(final String query, final int maxResults) {
    final List<? extends SymbolInformation> symbols = await(service.workspaceSymbolFuture(query));
    return symbols.stream().limit(maxResults).map(this::toLatheSymbol).toList();
  }

  private LatheSymbol toLatheSymbol(final SymbolInformation symbol) {
    final String container = symbol.getContainerName() == null ? "" : symbol.getContainerName();
    return new LatheSymbol(
        symbol.getName(),
        symbol.getKind().name(),
        container,
        toLatheLocation(symbol.getLocation()));
  }

  /**
   * Rename the symbol at {@code line}/{@code column} (0-based) to {@code newName} across the
   * reactor, applying the javac-computed edits to disk. Refuses (throws) if the rename would touch
   * a file outside the reactor, since those cannot be safely rewritten.
   */
  public LatheRename rename(
      final Path file, final int line, final int column, final String newName) {
    compileFromDisk(file); // register the file and warm its analysis before computing the rename
    final var params =
        new RenameParams(
            new TextDocumentIdentifier(file.toUri().toString()),
            new Position(line, column),
            newName);
    final WorkspaceEdit edit = await(service.renameEdit(params));
    final Map<String, List<TextEdit>> textEdits = RenameProvider.textEdits(edit);
    if (textEdits.isEmpty()) {
      return new LatheRename(newName, 0, List.of());
    }

    final Map<String, String> moves = RenameProvider.fileMoves(edit);
    final List<String> nonReactor =
        Stream.concat(textEdits.keySet().stream(), moves.values().stream())
            .filter(uri -> origin(LatheUri.toPath(uri)) != LatheLocation.Origin.REACTOR)
            .sorted()
            .toList();
    if (!nonReactor.isEmpty()) {
      throw new IllegalStateException(
          "[rename] refusing: would edit non-reactor file(s) %s".formatted(nonReactor));
    }

    final List<LatheFileEdit> files =
        textEdits.entrySet().stream()
            .map(
                entry -> {
                  final String uri = moves.getOrDefault(entry.getKey(), entry.getKey());
                  return new LatheFileEdit(
                      uri, origin(LatheUri.toPath(uri)), entry.getValue().size());
                })
            .sorted(Comparator.comparing(LatheFileEdit::uri))
            .toList();
    writeAndCommit(textEdits, moves, files);
    final int total = files.stream().mapToInt(LatheFileEdit::editCount).sum();
    return new LatheRename(newName, total, files);
  }

  // Holds the reactor lock while writing, so an editor's server on the same workspace never
  // reconciles a half-written rename, and returns once the mirror holds the whole change.
  private void writeAndCommit(
      final Map<String, List<TextEdit>> textEdits,
      final Map<String, String> moves,
      final List<LatheFileEdit> files) {
    IOUtil.unchecked(() -> LatheLock.acquire(LatheLayout.reactorLockFile(latheDir)));
    try {
      textEdits.forEach(LatheEngine::applyFileEdits);
      moves.forEach(
          (oldUri, newUri) ->
              IOUtil.unchecked(() -> Files.move(LatheUri.toPath(oldUri), LatheUri.toPath(newUri))));
      final Set<Path> changed =
          files.stream()
              .map(file -> LatheUri.toPath(file.uri()))
              .collect(Collectors.toUnmodifiableSet());
      await(service.writeReported(changed));
      // An editor's server on this workspace sees the files freshly stamped, so it would never
      // re-index the renamed types; the touch makes it re-scan the mirror, as after a Maven sync.
      final Path manifest = latheDir.resolve(LatheLayout.WORKSPACE_JSON);
      if (Files.exists(manifest)) {
        IOUtil.unchecked(() -> FileUtil.touch(manifest));
      }

    } finally {
      IOUtil.unchecked(() -> LatheLock.release(LatheLayout.reactorLockFile(latheDir)));
    }

    // So this rename's stale-module advisory reflects the commit, without the 2s scan lag.
    await(service.refreshStaleModulesFuture());
  }

  private static void applyFileEdits(final String uri, final List<TextEdit> edits) {
    final var path = LatheUri.toPath(uri);
    final String content = IOUtil.unchecked(() -> Files.readString(path));
    final String updated = SourceLocator.applyEdits(content, edits);
    IOUtil.unchecked(() -> Files.writeString(path, updated));
  }

  /** Reactor-relative paths of modules whose source is newer than their compiled classes. */
  public List<String> staleModules() {
    return await(service.staleModulesFuture());
  }

  /**
   * Replay a test at {@code scope} (the whole class, one {@code method}, or the file's package)
   * against the compiled classpath, no reactor build. Resolves the target from the file's runnables
   * so it reuses the editor's selection mapping.
   */
  public LatheTestRun runTest(final Path file, final TestScope scope, final String method) {
    compileFromDisk(file);
    final var uri = file.toUri().toString();
    final List<RunTarget> targets = await(service.runnablesFuture(uri));
    final RunTarget target = selectTarget(targets, scope, method, file);
    final var selection = new TestSelection(scope.selectionKind(), target.id());
    final String token = "mcp-run-%d".formatted(runToken.incrementAndGet());
    return LatheTestRun.from(
        await(service.runTestFuture(target.moduleRel(), List.of(selection), token)));
  }

  private static RunTarget selectTarget(
      final List<RunTarget> targets, final TestScope scope, final String method, final Path file) {
    return targets.stream()
        .filter(target -> target.kind() == scope.runnableKind())
        .filter(target -> scope != TestScope.METHOD || target.label().equals(method))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    scope == TestScope.METHOD
                        ? "no test method '%s' in %s".formatted(method, file.getFileName())
                        : "no %s test target in %s"
                            .formatted(scope.wireName(), file.getFileName())));
  }

  /**
   * One level of the call hierarchy for the symbol at {@code line}/{@code column} (0-based): its
   * callers ({@code INCOMING}) or the methods it calls ({@code OUTGOING}), each with a snippet.
   * Follows the real cross-module call graph, not text.
   */
  public LatheCallHierarchy callHierarchy(
      final Path file,
      final int line,
      final int column,
      final CallDirection direction,
      final int maxResults) {
    compileFromDisk(file);
    final var prepare =
        new CallHierarchyPrepareParams(
            new TextDocumentIdentifier(file.toUri().toString()), new Position(line, column));
    final List<CallHierarchyItem> items = await(service.prepareCallHierarchy(prepare));
    final boolean incoming = direction == CallDirection.INCOMING;
    if (items.isEmpty()) {
      return new LatheCallHierarchy(incoming, 0, false, List.of());
    }

    final List<LatheCall> all =
        incoming ? incomingCalls(items.getFirst()) : outgoingCalls(items.getFirst());
    return new LatheCallHierarchy(
        incoming, all.size(), all.size() > maxResults, all.stream().limit(maxResults).toList());
  }

  // One LatheCall per call site (a caller may invoke the symbol at several ranges).
  private List<LatheCall> incomingCalls(final CallHierarchyItem item) {
    return await(service.callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(item)))
        .stream()
        .flatMap(this::callSites)
        .toList();
  }

  private Stream<LatheCall> callSites(final CallHierarchyIncomingCall call) {
    final CallHierarchyItem from = call.getFrom();
    return call.getFromRanges().stream()
        .map(
            range ->
                new LatheCall(from.getName(), toLatheLocation(new Location(from.getUri(), range))));
  }

  private List<LatheCall> outgoingCalls(final CallHierarchyItem item) {
    return await(service.callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(item)))
        .stream()
        .map(this::toCallee)
        .toList();
  }

  private LatheCall toCallee(final CallHierarchyOutgoingCall call) {
    final CallHierarchyItem to = call.getTo();
    return new LatheCall(
        to.getName(), toLatheLocation(new Location(to.getUri(), to.getSelectionRange())));
  }

  /**
   * Recompile the change set in-process against the captured classpath (no reactor build) and
   * report the resulting compiler diagnostics, grouped by module. With no {@code files}, the change
   * set is detected from disk (sources newer than their last compile); otherwise it is those files.
   * When the change reaches other modules, {@link LatheVerifyChange#suggestedSync()} carries the
   * scoped sync that builds the remainder — Lathe never runs the build. If the in-process recompile
   * cannot be trusted (a pending POM sync, too many files, or a running build), the result is
   * deferred to a full sync.
   */
  public LatheVerifyChange verifyChange(final List<Path> files) {
    final ReconcileOutcome outcome = await(service.reconcileForVerify());
    if (outcome.deferral() != ReconcileOutcome.DeferReason.NONE) {
      return new LatheVerifyChange(
          outcome.deferral().name(), List.of(), List.of(), LatheLayout.SYNC_SCRIPT_COMMAND);
    }

    final List<Path> changed = files.isEmpty() ? outcome.reacted() : files;
    if (changed.isEmpty()) {
      return new LatheVerifyChange(null, List.of(), List.of(), null);
    }

    final Map<String, List<Path>> targets = await(service.verifyTargetsByModule(changed));
    final List<LatheModuleDiagnostics> perModule =
        targets.entrySet().stream()
            .map(entry -> new LatheModuleDiagnostics(entry.getKey(), diagnose(entry.getValue())))
            .filter(module -> !module.diagnostics().isEmpty())
            .toList();
    final List<String> downstream = await(service.downstreamModuleRels(changed));
    final String suggestedSync =
        downstream.isEmpty()
            ? null
            : LatheLayout.scopedSyncCommand(new TreeSet<>(targets.keySet()));
    return new LatheVerifyChange(null, perModule, downstream, suggestedSync);
  }

  private List<Diagnostic> diagnose(final List<Path> files) {
    return files.stream().flatMap(file -> compileFromDisk(file).stream()).toList();
  }

  /**
   * Pre-edit impact of the symbol at {@code line}/{@code column} (0-based): its signature, override
   * family, production vs test reference counts, the reactor modules that use it, and the relevant
   * test classes. Read-only — composes definition/reference analysis, no compile of the change.
   */
  public LatheChangeImpact analyzeChange(final Path file, final int line, final int column) {
    compileFromDisk(file); // warm once; the queries below reuse the analysis
    final String signature = hoverAt(file, line, column);
    final List<LatheLocation> overrideFamily =
        implementationsAt(file, line, column, IMPACT_MAX_RESULTS).implementations();
    final LatheReferences refs = referencesAt(file, line, column, IMPACT_MAX_RESULTS);
    final List<Path> paths =
        refs.references().stream().map(ref -> LatheUri.toPath(ref.uri())).toList();
    final Map<Path, ModulePlacement> placements = await(service.classifyPaths(paths));

    int production = 0;
    int test = 0;
    final var affectedModules = new TreeSet<String>();
    final var relevantTests = new TreeSet<String>();
    for (final LatheLocation ref : refs.references()) {
      final ModulePlacement placement = placements.get(LatheUri.toPath(ref.uri()));
      if (placement == null) {
        continue; // a reference outside the reactor (dependency/JDK) — not in the impact set
      }

      affectedModules.add(placement.moduleRel());
      if (placement.test()) {
        test++;
        relevantTests.add(FileUtil.javaTypeName(LatheUri.toPath(ref.uri())));
      } else {
        production++;
      }
    }

    return new LatheChangeImpact(
        signature,
        overrideFamily,
        production,
        test,
        refs.truncated(),
        List.copyOf(affectedModules),
        List.copyOf(relevantTests));
  }

  // An agent edits files with its own tools, outside the editor's change events, and queries right
  // after, before the idle reconcile tick has caught up: freshen the mirror first so cross-file
  // answers see those edits. A deferral (POM sync, bulk change, running build) answers from the
  // current mirror, which staleModules reports.
  private List<Diagnostic> compileFromDisk(final Path file) {
    await(service.reconcileForVerify());
    final var uri = file.toUri().toString();
    final String content = IOUtil.unchecked(() -> Files.readString(file));
    return await(service.diagnosticsFuture(uri, content, VERSION));
  }

  public void close() {
    service.close();
  }

  private LatheLocation toLatheLocation(final Location location) {
    final var targetPath = LatheUri.toPath(location.getUri());
    return new LatheLocation(
        location.getUri(),
        location.getRange(),
        origin(targetPath),
        snippet(targetPath, location.getRange()));
  }

  private LatheLocation.Origin origin(final Path target) {
    if (target.startsWith(latheDir)) {
      return LatheLocation.Origin.GENERATED;
    }

    if (target.startsWith(workspaceRoot)) {
      return LatheLocation.Origin.REACTOR;
    }

    return LatheLocation.Origin.EXTERNAL;
  }

  private static String snippet(final Path file, final Range range) {
    final List<String> lines = IOUtil.unchecked(() -> Files.readAllLines(file));
    final int target = range.getStart().getLine();
    final int from = Math.max(0, target - SNIPPET_CONTEXT_LINES);
    final int to = Math.min(lines.size() - 1, target + SNIPPET_CONTEXT_LINES);
    return IntStream.rangeClosed(from, to)
        .mapToObj(i -> "%s%d  %s".formatted(i == target ? "> " : "  ", i + 1, lines.get(i)))
        .collect(Collectors.joining(System.lineSeparator()));
  }

  private static <T> T await(final CompletableFuture<T> future) {
    try {
      return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("[engine] request interrupted", e);
    } catch (final ExecutionException | TimeoutException e) {
      throw new IllegalStateException("[engine] request failed", e);
    }
  }
}
