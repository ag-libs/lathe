package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.lathe.core.ExceptionUtil;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import io.github.aglibs.lathe.server.LatheTextDocumentService;
import io.github.aglibs.lathe.server.LatheUri;
import io.github.aglibs.lathe.server.ModulePlacement;
import io.github.aglibs.lathe.server.ReconcileOutcome;
import io.github.aglibs.lathe.server.analysis.MissingImportsResult;
import io.github.aglibs.lathe.server.analysis.MissingImportsResult.MissingImport;
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
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
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
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * In-process facade into the language server for non-editor clients (the MCP server): it drives the
 * same workspace-session analysis the LSP handlers do, reading each file from disk so it reflects
 * out-of-process edits, and returns results directly instead of over JSON-RPC.
 */
public final class LatheEngine {

  private static final Logger LOG = Logger.getLogger(LatheEngine.class.getName());

  private static final long REQUEST_TIMEOUT_SECONDS = 30;

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
    // Only the agent's tools touch the files, and they write the disk: no editor buffer to keep.
    service.setDocumentsFollowDisk(true);
    service.useWorkspaceFormatter(workspaceRoot);
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

  // An agent's column is easily off by one, and javac answers for the smallest tree around a
  // position: a method for its modifiers or indentation, the class past a line's end, the enclosing
  // method for a word in a comment. So the position tools answer only on a name: first a word
  // (cheap, no compile), then one javac's tree has a name at, not a word of a comment or a literal.
  public boolean onName(final Path file, final int line, final int column) {
    if (!onWord(file, line, column)) {
      return false;
    }

    compileFromDisk(file); // register the file and warm its analysis before resolving
    return await(service.onNameFuture(LatheUri.fromPath(file), new Position(line, column)));
  }

  private boolean onWord(final Path file, final int line, final int column) {
    return sourceLine(file, line)
        .filter(text -> column >= 0 && column < text.length())
        .filter(text -> Character.isJavaIdentifierPart(text.charAt(column)))
        .map(
            text ->
                text.substring(
                    SourceLocator.identifierStart(text, column),
                    SourceLocator.identifierEnd(text, column)))
        .filter(word -> Character.isJavaIdentifierStart(word.charAt(0)))
        .filter(word -> !SourceVersion.isKeyword(word))
        .isPresent();
  }

  public Optional<String> sourceLine(final Path file, final int line) {
    final List<String> lines = IOUtil.unchecked(() -> Files.readAllLines(file));
    return line >= 0 && line < lines.size() ? Optional.of(lines.get(line)) : Optional.empty();
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

  // The file's types and their members in source order, each at its nesting depth.
  public List<LatheOutlineItem> outline(final Path file) {
    compileFromDisk(file); // register the file and warm its analysis before scanning
    final String uri = LatheUri.fromPath(file);
    final var params = new DocumentSymbolParams(new TextDocumentIdentifier(uri));
    return await(service.documentSymbol(params)).stream()
        .filter(Either::isRight)
        .flatMap(symbol -> outlineItems(uri, symbol.getRight(), 0))
        .toList();
  }

  private Stream<LatheOutlineItem> outlineItems(
      final String uri, final DocumentSymbol symbol, final int depth) {
    final List<DocumentSymbol> children =
        symbol.getChildren() == null ? List.of() : symbol.getChildren();
    return Stream.concat(
        Stream.of(
            new LatheOutlineItem(
                symbol.getKind().name().toLowerCase(Locale.ROOT),
                symbol.getName(),
                depth,
                toLatheLocation(new Location(uri, symbol.getSelectionRange())))),
        children.stream().flatMap(child -> outlineItems(uri, child, depth + 1)));
  }

  /**
   * Name-addressed symbol search across the reactor, dependencies, and the JDK (an index query, no
   * per-file warmup), each hit enriched with a snippet and where it lives, capped to {@code
   * maxResults}.
   */
  public List<LatheSymbol> searchSymbols(final String query, final int maxResults) {
    await(service.reconcileForVerify()); // so types the agent just wrote are found
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
          "the rename would edit files outside the reactor: %s".formatted(nonReactor));
    }

    final List<LatheFileEdit> files =
        textEdits.entrySet().stream()
            .map(
                entry -> {
                  final String uri = moves.getOrDefault(entry.getKey(), entry.getKey());
                  final var path = LatheUri.toPath(uri);
                  return new LatheFileEdit(
                      uri, displayPath(path, uri), origin(path), entry.getValue().size());
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
  }

  private static void applyFileEdits(final String uri, final List<TextEdit> edits) {
    final var path = LatheUri.toPath(uri);
    final String content = IOUtil.unchecked(() -> Files.readString(path));
    final String updated = SourceLocator.applyEdits(content, edits);
    IOUtil.unchecked(() -> Files.writeString(path, updated));
  }

  // Runs the formatter the project pins, as the editor's format-on-save does, and writes the result
  // like a rename. Empty when the file was already formatted.
  public Optional<LatheFormat> format(final Path file) {
    requireReactorFile(file);
    compileFromDisk(file); // the formatter reads the file's document, so register it
    final String uri = LatheUri.fromPath(file);
    final List<TextEdit> edits = await(service.formatFuture(uri));
    if (edits.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(
        new LatheFormat(
            writeFile(file, uri, edits),
            firstLine(edits.get(0)),
            lastLine(edits.get(edits.size() - 1))));
  }

  // Adds the imports that have one candidate; the rest are the agent's to choose (ambiguous) or
  // fix (unknown names).
  public LatheImports fixImports(final Path file) {
    requireReactorFile(file);
    compileFromDisk(file); // missing imports are read from the file's document, so register it
    final String uri = LatheUri.fromPath(file);
    final MissingImportsResult missing = await(service.missingImportsFuture(uri));
    final Map<Boolean, List<MissingImport>> byUnambiguous =
        missing.items().stream()
            .collect(Collectors.partitioningBy(item -> item.candidates().size() == 1));
    final List<String> added =
        byUnambiguous.get(true).stream().map(item -> item.candidates().get(0)).sorted().toList();
    final String path =
        added.isEmpty() ? displayPath(file, uri) : writeFile(file, uri, missing.edits(added));
    return new LatheImports(path, added, byUnambiguous.get(false));
  }

  // The tools that write one file leave dependency, JDK and generated sources alone.
  private void requireReactorFile(final Path file) {
    if (origin(file) != LatheLocation.Origin.REACTOR) {
      throw new IllegalStateException(
          "%s is not a reactor file, so Lathe does not edit it".formatted(file));
    }
  }

  private String writeFile(final Path file, final String uri, final List<TextEdit> edits) {
    final var edit =
        new LatheFileEdit(uri, displayPath(file, uri), LatheLocation.Origin.REACTOR, edits.size());
    writeAndCommit(Map.of(uri, edits), Map.of(), List.of(edit));
    return edit.path();
  }

  // The formatter answers with one edit spanning what changed; its lines in the formatted file.
  private static int firstLine(final TextEdit edit) {
    return edit.getRange().getStart().getLine() + 1;
  }

  // A replacement ending in a line break ends on the line before it.
  private static int lastLine(final TextEdit edit) {
    final String text = edit.getNewText();
    final long breaks = text.chars().filter(c -> c == '\n').count();
    return firstLine(edit) + (int) (text.endsWith("\n") ? breaks - 1 : breaks);
  }

  /**
   * What an answer about {@code files} cannot vouch for: files whose write failed (fix the code),
   * what only a sync can fix, and a running build. Read after the tool's own write pass.
   */
  public LatheNotes notes(final List<Path> files) {
    return await(service.notesFuture(files));
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
    final List<RunTarget> selected = selectTargets(targets, scope, method, file);
    final List<TestSelection> selections =
        selected.stream()
            .map(target -> new TestSelection(scope.selectionKind(), target.id()))
            .toList();
    final String token = "mcp-run-%d".formatted(runToken.incrementAndGet());
    return LatheTestRun.from(
        await(service.runTestFuture(selected.get(0).moduleRel(), selections, token)),
        selected.stream().map(RunTarget::id).toList());
  }

  // The outermost targets of the scope: a nested class runs with its enclosing one (the scan lists
  // it first), and a file's every top-level test class runs, not only the first. A method is one,
  // the outermost of its name.
  static List<RunTarget> selectTargets(
      final List<RunTarget> targets, final TestScope scope, final String method, final Path file) {
    final List<RunTarget> matching =
        targets.stream()
            .filter(target -> target.kind() == scope.runnableKind())
            .filter(target -> scope != TestScope.METHOD || target.label().equals(method))
            .toList();
    if (matching.isEmpty()) {
      throw new IllegalStateException(
          scope == TestScope.METHOD
              ? "no test method '%s' in %s".formatted(method, file.getFileName())
              : "no %s test target in %s".formatted(scope.wireName(), file.getFileName()));
    }

    final long outermost =
        matching.stream().mapToLong(LatheEngine::nestingDepth).min().orElseThrow();
    final List<RunTarget> outer =
        matching.stream().filter(target -> nestingDepth(target) == outermost).toList();
    return scope == TestScope.METHOD ? outer.subList(0, 1) : outer;
  }

  private static long nestingDepth(final RunTarget target) {
    return target.id().chars().filter(c -> c == '$').count();
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
        incoming ? incomingCalls(items.get(0)) : outgoingCalls(items.get(0));
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

  // The reactor's modules, each with the reactor modules it depends on and its test-launch capture.
  public List<LatheModuleSummary> modules() {
    return await(service.moduleSummariesFuture());
  }

  // Recompiles the change set and its callers in-process; Lathe never runs the build, it names the
  // sync that would. No files: what changed on disk plus what the tools wrote since the last check.
  // A pending build-file sync or a running build defers the check to a full sync.
  public LatheVerifyChange verifyChange(final List<Path> files) {
    final ReconcileOutcome outcome = await(service.reconcileForVerify());
    if (outcome.deferral() != ReconcileOutcome.DeferReason.NONE) {
      // Another server's pass ends on its own; a sync would only add a build to wait for.
      final String sync =
          outcome.deferral() == ReconcileOutcome.DeferReason.OTHER_SERVER_WRITING
              ? null
              : LatheLayout.SYNC_SCRIPT_COMMAND;
      return new LatheVerifyChange(outcome.deferral().name(), List.of(), List.of(), sync);
    }

    // With no files: what changed on disk, and what the tools wrote themselves (already stamped).
    final List<Path> touched =
        files.isEmpty()
            ? Stream.concat(
                    outcome.reacted().stream(), await(service.takeWrittenSinceVerify()).stream())
                .distinct()
                .toList()
            : files;
    // A file put back to what the last build compiled is no change.
    final List<Path> changed = await(service.differingFromBuild(touched));
    if (changed.isEmpty()) {
      return new LatheVerifyChange(null, List.of(), List.of(), null);
    }

    final var checked = Stopwatch.start();
    final Map<String, List<Path>> targets = await(service.verifyTargetsByModule(changed));
    // Every check starts before any is awaited, so the modules compile side by side, each on its
    // own worker; one at a time, a change with callers across a large reactor took seconds.
    final Map<Path, CompletableFuture<List<Diagnostic>>> analyses =
        targets.values().stream()
            .flatMap(List::stream)
            .distinct()
            .collect(Collectors.toUnmodifiableMap(Function.identity(), this::analyze));
    final Set<Path> changedSet = Set.copyOf(changed);
    final List<LatheModuleDiagnostics> perModule =
        targets.entrySet().stream()
            .map(
                entry ->
                    new LatheModuleDiagnostics(
                        entry.getKey(), diagnose(entry.getValue(), changedSet, analyses)))
            .filter(module -> !module.diagnostics().isEmpty())
            .toList();
    // Every caller is checked, however many: the line shows when that grows costly (a core type's
    // callers across a large reactor).
    LOG.info(
        () ->
            "[verify] %d file(s) in %d module(s) %dms diags=%d"
                .formatted(
                    targets.values().stream().mapToInt(List::size).sum(),
                    targets.size(),
                    checked.elapsedMs(),
                    perModule.stream().mapToInt(module -> module.diagnostics().size()).sum()));
    final List<String> downstream = await(service.downstreamModuleRels(changed));
    return new LatheVerifyChange(
        null, perModule, downstream, syncFor(downstream, await(service.reactorModuleCount())));
  }

  // Naming the dependents builds them and, with -am, every module they depend on, the changed ones
  // included (not -amd: it adds dependents without their own upstream, which broke on Helidon).
  // Past half the reactor that is nearly a full build anyway, and the list could approach the
  // 8,191-character Windows command line.
  private static String syncFor(final List<String> dependents, final int reactorModules) {
    if (dependents.isEmpty()) {
      return null;
    }

    return dependents.size() * 2 > reactorModules
        ? LatheLayout.SYNC_SCRIPT_COMMAND
        : LatheLayout.scopedSyncCommand(dependents);
  }

  // An agent reads any count as something to fix: a caller's warnings predate the change, and hints
  // (unused code) say nothing about whether it compiles. Errors first. Freshened once by
  // verifyChange, not again for each file.
  private List<LatheDiagnostic> diagnose(
      final List<Path> files,
      final Set<Path> changed,
      final Map<Path, CompletableFuture<List<Diagnostic>>> analyses) {
    return files.stream()
        .flatMap(file -> reportedIn(file, changed.contains(file), await(analyses.get(file))))
        .sorted(Comparator.comparing(located -> located.diagnostic().getSeverity()))
        .toList();
  }

  private Stream<LatheDiagnostic> reportedIn(
      final Path file, final boolean changedFile, final List<Diagnostic> diagnostics) {
    final String path = displayPath(file, file.toUri().toString());
    return diagnostics.stream()
        .filter(diagnostic -> reported(diagnostic, changedFile))
        .map(diagnostic -> new LatheDiagnostic(path, diagnostic));
  }

  private static boolean reported(final Diagnostic diagnostic, final boolean changedFile) {
    return changedFile
        ? diagnostic.getSeverity() != DiagnosticSeverity.Hint
        : diagnostic.getSeverity() == DiagnosticSeverity.Error;
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
  // answers see those edits. What the pass could not write (code that does not compile, a pending
  // build-file sync, a running build) answers from the current mirror, as notes() reports.
  private List<Diagnostic> compileFromDisk(final Path file) {
    await(service.reconcileForVerify());
    return await(analyze(file));
  }

  private CompletableFuture<List<Diagnostic>> analyze(final Path file) {
    final var uri = LatheUri.fromPath(file);
    final String content = IOUtil.unchecked(() -> Files.readString(file));
    return service.diagnosticsFuture(uri, content, VERSION);
  }

  public void close() {
    service.close();
  }

  private LatheLocation toLatheLocation(final Location location) {
    final var targetPath = LatheUri.toPath(location.getUri());
    return new LatheLocation(
        location.getUri(),
        displayPath(targetPath, location.getUri()),
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

  // A dependency or JDK source has no workspace path, so it keeps its URI.
  private String displayPath(final Path target, final String uri) {
    return target.startsWith(workspaceRoot)
        ? FileUtil.slashed(workspaceRoot.relativize(target))
        : uri;
  }

  // The target line alone: an agent reads the file when it needs more, and every extra line of a
  // long reference list costs it context.
  private static String snippet(final Path file, final Range range) {
    final List<String> lines = IOUtil.unchecked(() -> Files.readAllLines(file));
    final int target = range.getStart().getLine();
    return target < lines.size() ? lines.get(target).strip() : "";
  }

  private static <T> T await(final CompletableFuture<T> future) {
    try {
      return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("the request was interrupted", e);
    } catch (final TimeoutException e) {
      throw new IllegalStateException(
          "the request timed out after %ds; see the server log".formatted(REQUEST_TIMEOUT_SECONDS),
          e);
    } catch (final ExecutionException e) {
      // A request the server refused carries its reason, which the caller must see.
      final String message =
          ExceptionUtil.rootCause(e) instanceof final ResponseErrorException refused
              ? refused.getMessage()
              : "the request failed; see the server log";
      throw new IllegalStateException(message, e);
    }
  }
}
