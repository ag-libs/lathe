package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import io.github.aglibs.lathe.server.LatheTextDocumentService;
import io.github.aglibs.lathe.server.LatheUri;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import io.github.aglibs.lathe.server.run.RunTarget;
import io.github.aglibs.lathe.server.run.RunnableKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
   * The hover markdown for the symbol at {@code line}/{@code column} (0-based) — rendered signature
   * and javadoc — or empty when there is nothing to describe.
   */
  public String describe(final Path file, final int line, final int column) {
    compileFromDisk(file); // register the file and warm its analysis before resolving
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
    final WorkspaceEdit edit = await(service.rename(params));
    final Map<String, List<TextEdit>> changes = edit == null ? Map.of() : edit.getChanges();
    if (changes == null || changes.isEmpty()) {
      return new LatheRename(newName, 0, List.of());
    }

    final List<String> nonReactor =
        changes.keySet().stream()
            .filter(uri -> origin(LatheUri.toPath(uri)) != LatheLocation.Origin.REACTOR)
            .sorted()
            .toList();
    if (!nonReactor.isEmpty()) {
      throw new IllegalStateException(
          "[rename] refusing: would edit non-reactor file(s) %s".formatted(nonReactor));
    }

    final List<LatheFileEdit> files = new ArrayList<>();
    for (final Map.Entry<String, List<TextEdit>> entry : changes.entrySet()) {
      files.add(applyFileEdits(entry.getKey(), entry.getValue()));
    }

    files.sort(Comparator.comparing(LatheFileEdit::uri));
    // Refresh now so this rename's advisory reflects the edits it just wrote, without the 2s lag.
    await(service.refreshStaleModulesFuture());
    final int total = files.stream().mapToInt(LatheFileEdit::editCount).sum();
    return new LatheRename(newName, total, files);
  }

  private LatheFileEdit applyFileEdits(final String uri, final List<TextEdit> edits) {
    final var path = LatheUri.toPath(uri);
    final String content = IOUtil.unchecked(() -> Files.readString(path));
    final String updated = applyEdits(content, edits);
    IOUtil.unchecked(() -> Files.writeString(path, updated));
    return new LatheFileEdit(uri, origin(path), edits.size());
  }

  // Rebuild the document from the original: for each edit (in document order) emit the untouched
  // gap before it plus its replacement, then the final untouched tail. Edits never overlap, so the
  // gaps are well-defined and offsets need no adjusting for earlier edits.
  private static String applyEdits(final String content, final List<TextEdit> edits) {
    final List<TextEdit> ordered =
        edits.stream()
            .sorted(
                Comparator.comparingInt((TextEdit e) -> offset(content, e.getRange().getStart())))
            .toList();
    return IntStream.rangeClosed(0, ordered.size())
        .mapToObj(i -> segment(content, ordered, i))
        .collect(Collectors.joining());
  }

  private static String segment(
      final String content, final List<TextEdit> ordered, final int index) {
    final int from = index == 0 ? 0 : offset(content, ordered.get(index - 1).getRange().getEnd());
    if (index == ordered.size()) {
      return content.substring(from); // the untouched tail after the last edit
    }

    final TextEdit edit = ordered.get(index);
    final int to = offset(content, edit.getRange().getStart());
    return "%s%s".formatted(content.substring(from, to), edit.getNewText());
  }

  private static int offset(final String content, final Position position) {
    return SourceLocator.toOffset(content, position.getLine(), position.getCharacter());
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

  private List<Diagnostic> compileFromDisk(final Path file) {
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
