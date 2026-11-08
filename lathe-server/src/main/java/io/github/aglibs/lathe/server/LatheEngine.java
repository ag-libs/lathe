package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.DeclarationNames;
import io.github.aglibs.lathe.analysis.MissingImportsResult;
import io.github.aglibs.lathe.analysis.MissingImportsResult.MissingImport;
import io.github.aglibs.lathe.analysis.Positions;
import io.github.aglibs.lathe.analysis.RenameProvider;
import io.github.aglibs.lathe.analysis.RunTarget;
import io.github.aglibs.lathe.analysis.RunnableKind;
import io.github.aglibs.lathe.analysis.TextMentions;
import io.github.aglibs.lathe.core.ExceptionUtil;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.server.engine.LatheCall;
import io.github.aglibs.lathe.server.engine.LatheCallHierarchy;
import io.github.aglibs.lathe.server.engine.LatheChangeImpact;
import io.github.aglibs.lathe.server.engine.LatheDiagnostic;
import io.github.aglibs.lathe.server.engine.LatheFileEdit;
import io.github.aglibs.lathe.server.engine.LatheFormat;
import io.github.aglibs.lathe.server.engine.LatheImplementations;
import io.github.aglibs.lathe.server.engine.LatheImports;
import io.github.aglibs.lathe.server.engine.LatheLocation;
import io.github.aglibs.lathe.server.engine.LatheModuleDiagnostics;
import io.github.aglibs.lathe.server.engine.LatheModuleSummary;
import io.github.aglibs.lathe.server.engine.LatheNotes;
import io.github.aglibs.lathe.server.engine.LatheOutlineItem;
import io.github.aglibs.lathe.server.engine.LatheReferences;
import io.github.aglibs.lathe.server.engine.LatheRename;
import io.github.aglibs.lathe.server.engine.LatheSymbol;
import io.github.aglibs.lathe.server.engine.LatheTestRun;
import io.github.aglibs.lathe.server.engine.LatheVerifyChange;
import io.github.aglibs.lathe.server.engine.Noted;
import io.github.aglibs.lathe.server.format.FormatEngine;
import io.github.aglibs.lathe.server.format.FormatEngines;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.Serial;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * In-process facade into the language server for non-editor clients (the MCP server): it drives the
 * same workspace-session analysis the LSP handlers do, reading each file from disk so it reflects
 * out-of-process edits, and returns results directly instead of over JSON-RPC.
 */
public final class LatheEngine {

  private static final Logger LOG = Logger.getLogger(LatheEngine.class.getName());

  // A request now chains what took several waits of 30 s each (freshen, compile, the tool's own
  // work): the bound only stops a request that hangs.
  private static final long REQUEST_TIMEOUT_SECONDS = 120;
  // As long as an unused module session is kept, so a pause finds them released.
  private static final long IDLE_SCAN_PAUSE_MS = 5 * 60 * 1000;
  private static final int TEST_CLASS_CANDIDATES = 20;
  private static final int LISTED_MENTIONS = 20;
  private static final Set<String> JDK_PACKAGES =
      systemPackages(descriptor -> descriptor.packages().stream());
  private static final Set<String> JDK_EXPORTED_PACKAGES =
      systemPackages(
          descriptor ->
              descriptor.exports().stream()
                  .filter(exports -> !exports.isQualified())
                  .map(ModuleDescriptor.Exports::source));

  // analyze_change classifies every reference, so it needs the full set, not a page. The cap is a
  // safety bound; LatheChangeImpact.referencesTruncated flags when it is exceeded.
  private static final int IMPACT_MAX_RESULTS = 1000;

  // Every put creates a fresh generation, so the LSP version is irrelevant to freshness here.
  private static final int VERSION = 1;

  private final SessionHost host = new SessionHost();
  private final AtomicLong runToken = new AtomicLong();
  private final Path workspaceRoot;
  private final Path latheDir;
  private final DiskEdits diskEdits;

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
  }

  /** Which way {@link #callHierarchy} walks: callers of the symbol, or callees it invokes. */
  public enum CallDirection {
    INCOMING,
    OUTGOING
  }

  // Which code an answer covers: the reactor's own (generated sources included), or also the JDK
  // and dependencies, whose entries bury the reactor's in a call tree or a name search.
  public enum SymbolScope {
    REACTOR,
    ALL;

    public boolean includes(final LatheLocation.Origin origin) {
      return this == ALL || origin != LatheLocation.Origin.EXTERNAL;
    }
  }

  public LatheEngine(final Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
    this.latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    this.diskEdits =
        new DiskEdits(
            latheDir,
            changed ->
                request((session, cancel, progress) -> session.writePass().writeReported(changed)));
    host.connect(new NoopLanguageClient());
    // Only the agent's tools touch the files, and they write the disk: no editor buffer to keep.
    host.setDocumentsFollowDisk(true);
    // An agent cannot answer a prompt: a due sync stays in every answer's notes until a reload.
    host.setInteractive(false);
    // Every query scans first, so between them the scan only costs CPU.
    host.setIdleScanPause(IDLE_SCAN_PAUSE_MS);
    // Resolved here, off the loop: it reads the style file and builds the formatter.
    final FormatterSpec formatterSpec = FormatEngines.workspaceSpec(workspaceRoot);
    final FormatEngine formatter =
        formatterSpec == null ? null : FormatEngines.forSpec(formatterSpec, workspaceRoot);
    host.execute(session -> session.formats().use(formatter, formatterSpec != null));
    host.initialize(workspaceRoot);
  }

  /** Compiler diagnostics for {@code file} at its current on-disk content. */
  public Noted<List<LatheDiagnostic>> diagnostics(final Path file) {
    final String path = displayPath(file, LatheUri.fromPath(file));
    return noted(
            List.of(file),
            fileWork(
                file,
                (session, uri, found, cancel, progress) ->
                    CompletableFuture.completedFuture(found)))
        .map(
            diagnostics ->
                diagnostics.stream()
                    .map(diagnostic -> LatheDiagnostic.of(path, diagnostic))
                    .toList());
  }

  /**
   * Definition target(s) for the symbol at {@code line}/{@code column} (0-based), each enriched
   * with a source snippet and where it resolved.
   */
  public Noted<List<LatheLocation>> definition(final Path file, final int line, final int column) {
    return noted(
            List.of(file),
            positionWork(
                file,
                line,
                column,
                (session, uri, pos, cancel, progress) ->
                    session.features().definitionFuture(uri, pos)))
        .map(this::definitionLocations);
  }

  private List<LatheLocation> definitionLocations(
      final Either<List<? extends Location>, List<? extends LocationLink>> either) {
    final List<? extends Location> locations = either.isLeft() ? either.getLeft() : List.of();
    return locations.stream().map(this::toLatheLocation).toList();
  }

  /**
   * References to the symbol at {@code line}/{@code column} (0-based) across the reactor, each
   * enriched with a snippet, capped to {@code maxResults} with the true total for pagination.
   */
  public Noted<LatheReferences> references(
      final Path file, final int line, final int column, final int maxResults) {
    return noted(
            List.of(file),
            positionWork(
                file,
                line,
                column,
                (session, uri, pos, cancel, progress) ->
                    session.search().referencesFuture(uri, pos, true, cancel, progress)))
        .map(locations -> capReferences(locations, maxResults));
  }

  private LatheReferences capReferences(
      final List<? extends Location> locations, final int maxResults) {
    final List<LatheLocation> capped =
        locations.stream().limit(maxResults).map(this::toLatheLocation).toList();
    return new LatheReferences(locations.size(), locations.size() > maxResults, capped);
  }

  /**
   * Implementations of the interface (or overrides of the method) at {@code line}/{@code column}
   * (0-based) across the reactor, each enriched with a snippet, capped to {@code maxResults}.
   */
  public Noted<LatheImplementations> findImplementations(
      final Path file, final int line, final int column, final int maxResults) {
    return noted(
            List.of(file),
            positionWork(
                file,
                line,
                column,
                (session, uri, pos, cancel, progress) ->
                    session.search().implementationFuture(uri, pos)))
        .map(either -> capImplementations(either, maxResults));
  }

  private LatheImplementations capImplementations(
      final Either<List<? extends Location>, List<? extends LocationLink>> either,
      final int maxResults) {
    final LatheReferences capped =
        capReferences(either.isLeft() ? either.getLeft() : List.of(), maxResults);
    return new LatheImplementations(capped.total(), capped.truncated(), capped.references());
  }

  // A position tool's own work, run once the name check passed, on the analysis it compiled.
  @FunctionalInterface
  private interface PositionWork<T> {
    CompletableFuture<T> start(
        WorkspaceSession session,
        String uri,
        Position pos,
        CancelChecker cancel,
        ProgressReporter.Task progress);
  }

  // One request for a position tool: the name check (a word here, then a name in javac's tree on
  // the loop), then the tool's work, chained. An agent's column is easily off by one, and javac
  // answers for the smallest tree around a position: a method for its modifiers or indentation, the
  // class past a line's end, the enclosing method for a word in a comment. So the position tools
  // answer only on a name, and the tool goes on with the analysis that checked it, so the file is
  // compiled once.
  private <T> SessionHost.LoopWork<T> positionWork(
      final Path file, final int line, final int column, final PositionWork<T> work) {
    requireWord(file, line, column);
    final var pos = new Position(line, column);
    return fileWork(
        file,
        (session, uri, diagnostics, cancel, progress) ->
            session
                .features()
                .onNameFuture(uri, pos)
                .thenCompose(
                    onName -> {
                      if (!onName) {
                        throw new NotOnName();
                      }

                      return work.start(session, uri, pos, cancel, progress);
                    }));
  }

  // A file tool's own work, run once the file is compiled as it is on disk.
  @FunctionalInterface
  private interface FileWork<T> {
    CompletableFuture<T> start(
        WorkspaceSession session,
        String uri,
        List<Diagnostic> diagnostics,
        CancelChecker cancel,
        ProgressReporter.Task progress);
  }

  // One request for a file tool: freshen the workspace (what the agent wrote outside the editor),
  // compile the file as it is on disk, then the tool's work, chained.
  private <T> SessionHost.LoopWork<T> fileWork(final Path file, final FileWork<T> work) {
    final String uri = LatheUri.fromPath(file);
    final String content = read(file);
    return (session, cancel, progress) ->
        session
            .reconcileForVerify()
            .thenCompose(outcome -> session.diagnosticsFuture(uri, content, VERSION))
            .thenCompose(diagnostics -> work.start(session, uri, diagnostics, cancel, progress));
  }

  // A read tool's answer and the notes on the files it is about, in one request: the notes are
  // read once the answer is computed, from the same state.
  private <T> Noted<T> noted(final List<Path> files, final SessionHost.LoopWork<T> work) {
    return request(
        (session, cancel, progress) ->
            work.start(session, cancel, progress)
                .thenApply(value -> new Noted<T>(value, session.notes(files))));
  }

  // A read of session state as one request.
  private <T> T query(final Function<WorkspaceSession, T> read) {
    return request(
        (session, cancel, progress) -> CompletableFuture.completedFuture(read.apply(session)));
  }

  private void requireWord(final Path file, final int line, final int column) {
    if (wordAt(file, line, column).isEmpty()) {
      throw new NotOnName();
    }
  }

  // An expected answer, not a failure: the tool cannot do this here, and the message says why.
  public static final class Refused extends IllegalStateException {
    @Serial private static final long serialVersionUID = 1L;

    Refused(final String reason) {
      super(reason);
    }
  }

  /** A position tool was asked at a position that is not on a name. */
  public static final class NotOnName extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    NotOnName() {
      super(null, null, false, false);
    }
  }

  private Optional<String> wordAt(final Path file, final int line, final int column) {
    return sourceLine(file, line)
        .filter(text -> column >= 0 && column < text.length())
        .filter(text -> Character.isJavaIdentifierPart(text.charAt(column)))
        .map(
            text ->
                text.substring(
                    DeclarationNames.identifierStart(text, column),
                    DeclarationNames.identifierEnd(text, column)))
        .filter(word -> Character.isJavaIdentifierStart(word.charAt(0)))
        .filter(word -> !SourceVersion.isKeyword(word));
  }

  public Optional<String> sourceLine(final Path file, final int line) {
    final List<String> lines = IOUtil.unchecked(() -> Files.readAllLines(file));
    return line >= 0 && line < lines.size() ? Optional.of(lines.get(line)) : Optional.empty();
  }

  // Where name stands as a whole word on the line (0-based columns, left to right), for an agent
  // that names the symbol instead of counting its column. The text only finds the candidates: javac
  // resolves each, and refuses one inside a comment or a string (NotOnName).
  public List<Integer> nameColumns(final Path file, final int line, final String name) {
    return TextMentions.wordStarts(sourceLine(file, line).orElse(""), name).boxed().toList();
  }

  /**
   * The hover markdown for the symbol at {@code line}/{@code column} (0-based) — rendered signature
   * and javadoc — or empty when there is nothing to describe.
   */
  public Noted<String> describe(final Path file, final int line, final int column) {
    return noted(
            List.of(file),
            positionWork(
                file,
                line,
                column,
                (session, uri, pos, cancel, progress) -> session.features().hoverFuture(uri, pos)))
        .map(LatheEngine::hoverText);
  }

  private static String hoverText(final Hover hover) {
    if (hover == null || hover.getContents() == null || !hover.getContents().isRight()) {
      return "";
    }

    return hover.getContents().getRight().getValue();
  }

  // The file's types and their members in source order, each at its nesting depth.
  public Noted<List<LatheOutlineItem>> outline(final Path file) {
    final String uri = LatheUri.fromPath(file);
    // The file is registered and its analysis warmed before it is scanned.
    return noted(
            List.of(file),
            fileWork(
                file,
                (session, fileUri, diagnostics, cancel, progress) ->
                    session.features().documentSymbolFuture(fileUri)))
        .map(symbols -> symbols.stream().flatMap(symbol -> outlineItems(uri, symbol, 0)).toList());
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
  public Noted<List<LatheSymbol>> searchSymbols(
      final String query, final SymbolScope scope, final int maxResults) {
    // Agents write globs by habit; the search already matches a prefix.
    final String name = query.replaceAll("^\\*+|\\*+$", "");
    if (name.isBlank()) {
      return new Noted<>(List.of(), notes(List.of()));
    }

    final boolean reactorOnly = scope == SymbolScope.REACTOR;
    // Freshened first, so types the agent just wrote are found.
    return noted(
            List.of(),
            (session, cancel, progress) ->
                session
                    .reconcileForVerify()
                    .thenApply(outcome -> session.features().workspaceSymbol(name, reactorOnly)))
        .map(
            symbols ->
                symbols.stream()
                    .sorted(Comparator.comparingInt(this::rank))
                    .limit(maxResults)
                    .map(this::toLatheSymbol)
                    .toList());
  }

  // The reactor's types first, then the JDK's and dependencies', then JDK internals: hidden from a
  // plain project, but one that adds exports (an annotation processor, a javac plugin) uses them.
  private int rank(final SymbolInformation symbol) {
    if (origin(LatheUri.toPath(symbol.getLocation().getUri())) != LatheLocation.Origin.EXTERNAL) {
      return 0;
    }

    return jdkInternal(symbol.getContainerName()) ? 2 : 1;
  }

  // In a JDK package its modules do not export to everyone (javac's internals, jdk.internal): no
  // code uses it without --add-exports. A container is a package or an enclosing type, so its
  // package is the longest prefix the JDK has.
  static boolean jdkInternal(final String container) {
    return container != null
        && Stream.iterate(container, prefix -> !prefix.isEmpty(), LatheEngine::parentName)
            .filter(JDK_PACKAGES::contains)
            .findFirst()
            .filter(jdkPackage -> !JDK_EXPORTED_PACKAGES.contains(jdkPackage))
            .isPresent();
  }

  private static String parentName(final String qualified) {
    final int dot = qualified.lastIndexOf('.');
    return dot < 0 ? "" : qualified.substring(0, dot);
  }

  private static Set<String> systemPackages(
      final Function<ModuleDescriptor, Stream<String>> packages) {
    return ModuleFinder.ofSystem().findAll().stream()
        .map(ModuleReference::descriptor)
        .flatMap(packages)
        .collect(Collectors.toUnmodifiableSet());
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
    final String original = read(file);
    final Optional<List<Location>> localMentions =
        request(
            positionWork(
                file,
                line,
                column,
                (session, uri, pos, cancel, progress) ->
                    session.renames().localTextMentionsFuture(uri, pos)));
    // The engine moves files itself, so a type's rename may move its file.
    final WorkspaceEdit edit =
        request(
            positionWork(
                file,
                line,
                column,
                (session, uri, pos, cancel, progress) ->
                    session.renames().renameFuture(uri, pos, newName, true, cancel, progress)));
    final String oldName = wordAt(file, line, column).orElseThrow();
    final Map<String, List<TextEdit>> textEdits = RenameProvider.textEdits(edit);
    if (textEdits.isEmpty()) {
      return new LatheRename(newName, 0, List.of(), List.of(), 0);
    }

    final Map<String, String> moves = RenameProvider.fileMoves(edit);
    final List<String> nonReactor =
        Stream.concat(textEdits.keySet().stream(), moves.values().stream())
            .filter(uri -> origin(LatheUri.toPath(uri)) != LatheLocation.Origin.REACTOR)
            .sorted()
            .toList();
    if (!nonReactor.isEmpty()) {
      throw new Refused(
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
    diskEdits.apply(
        textEdits,
        moves,
        files.stream()
            .map(written -> LatheUri.toPath(written.uri()))
            .collect(Collectors.toUnmodifiableSet()));
    final int total = files.stream().mapToInt(LatheFileEdit::editCount).sum();
    // A rename changes only what resolves to the symbol: the name left as text is the agent's to
    // review, never edited here.
    final List<Location> mentions =
        localMentions
            .map(
                found ->
                    renamedMentions(
                        found,
                        original,
                        textEdits.getOrDefault(LatheUri.fromPath(file), List.of())))
            .orElseGet(() -> textMentions(oldName))
            .stream()
            .filter(
                mention ->
                    origin(LatheUri.toPath(mention.getUri())) == LatheLocation.Origin.REACTOR)
            .toList();
    return new LatheRename(
        newName,
        total,
        files,
        mentions.stream().limit(LISTED_MENTIONS).map(this::toLatheLocation).toList(),
        mentions.size());
  }

  private static List<Location> renamedMentions(
      final List<Location> mentions, final String original, final List<TextEdit> edits) {
    final String renamed = Positions.applyEdits(original, edits);
    final var results = new ArrayList<Location>();
    for (final Location mention : mentions) {
      final Position at = mention.getRange().getStart();
      final int before = Positions.toOffset(original, at.getLine(), at.getCharacter());
      int after = before;
      for (final TextEdit edit : edits) {
        final Range range = edit.getRange();
        final int end =
            Positions.toOffset(original, range.getEnd().getLine(), range.getEnd().getCharacter());
        if (end <= before) {
          final int start =
              Positions.toOffset(
                  original, range.getStart().getLine(), range.getStart().getCharacter());
          after += edit.getNewText().length() - (end - start);
        }
      }

      final var position = Positions.offsetToPosition(renamed, after);
      results.add(new Location(mention.getUri(), new Range(position, position)));
    }

    return List.copyOf(results);
  }

  // Best effort: the rename is on disk by now, and a scan that fails must not report it as failed.
  // The scan reads and parses files on this thread, not the event loop, which serves every other
  // request meanwhile.
  private List<Location> textMentions(final String name) {
    try {
      return TextMentions.find(query(session -> session.verifier().textMentionSources(name)), name);
    } catch (final RuntimeException e) {
      LOG.log(Level.WARNING, e, () -> "[rename] text mentions of %s not listed".formatted(name));
      return List.of();
    }
  }

  // Holds the reactor lock while writing, so an editor's server on the same workspace never
  // reconciles a half-written rename, and returns once the mirror holds the whole change.

  // Runs the formatter the project pins, as the editor's format-on-save does, and writes the result
  // like a rename. Empty when the file was already formatted.
  public Optional<LatheFormat> format(final Path file) {
    requireReactorFile(file);
    final String uri = LatheUri.fromPath(file);
    // The formatter reads the file's document, which the file request registers.
    final List<TextEdit> edits =
        request(
            fileWork(
                file,
                (session, fileUri, diagnostics, cancel, progress) ->
                    session.formats().formatOrRefuseFuture(fileUri)));
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
    final String uri = LatheUri.fromPath(file);
    // Missing imports are read from the file's document, so it is registered first.
    final MissingImportsResult missing =
        request(
            fileWork(
                file,
                (session, fileUri, diagnostics, cancel, progress) ->
                    session.features().missingImportsFuture(fileUri)));
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
      throw new Refused("%s is not a reactor file, so Lathe does not edit it".formatted(file));
    }
  }

  private String writeFile(final Path file, final String uri, final List<TextEdit> edits) {
    diskEdits.apply(Map.of(uri, edits), Map.of(), Set.of(file));
    return displayPath(file, uri);
  }

  // The formatter answers with one edit spanning what changed; its lines in the formatted file.
  private static int firstLine(final TextEdit edit) {
    return edit.getRange().getStart().getLine();
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
    return query(session -> session.notes(files));
  }

  /**
   * Replay a test at {@code scope} (the whole class, one {@code method}, or the file's package)
   * against the compiled classpath, no reactor build. Resolves the target from the file's runnables
   * so it reuses the editor's selection mapping.
   */
  public Noted<LatheTestRun> runTest(final Path file, final TestScope scope, final String method) {
    final Noted<TestFacts> read =
        noted(
            List.of(file),
            fileWork(
                file,
                (session, uri, diagnostics, cancel, progress) -> {
                  final Optional<String> failing = session.verifier().failingUnder(file);
                  return session
                      .features()
                      .runnablesFuture(uri)
                      .thenApply(targets -> new TestFacts(failing.orElse(null), targets));
                }));
    final TestFacts facts = read.value();
    // Replaying the last good classes would answer for code that is no longer there.
    facts
        .failing()
        .ifPresent(
            error -> {
              throw new Refused(
                  "%s was not run: code it runs does not compile: %s"
                      .formatted(file.getFileName(), error));
            });
    final List<RunTarget> targets = facts.targets();
    if (targets.isEmpty()) {
      throw new Refused(noTests(file, read.notes()));
    }

    final List<RunTarget> selected = selectTargets(targets, scope, method, file);
    final List<TestSelection> selections =
        selected.stream()
            .map(target -> new TestSelection(scope.selectionKind(), target.id()))
            .toList();
    final String token = "mcp-run-%d".formatted(runToken.incrementAndGet());
    final String moduleRel = selected.get(0).moduleRel();
    return new Noted<>(
        LatheTestRun.from(
            request(
                (session, cancel, progress) ->
                    session.runs().runTestFuture(moduleRel, selections, token)),
            selected.stream().map(RunTarget::id).toList()),
        read.notes());
  }

  // Why a file runs nothing: no module compiles it (the note says why), or it holds no test, and
  // then its test class when the reactor has one by the usual name.
  private String noTests(final Path file, final LatheNotes notes) {
    final String name = FileUtil.javaTypeName(file);
    final Set<String> testNames = Set.of("%sTest".formatted(name), "%sTests".formatted(name));
    return notes.notChecked().stream()
        .findFirst()
        .orElseGet(
            () ->
                searchSymbols("%sTest".formatted(name), SymbolScope.REACTOR, TEST_CLASS_CANDIDATES)
                    .value()
                    .stream()
                    .filter(symbol -> testNames.contains(symbol.name()))
                    .findFirst()
                    .map(
                        symbol ->
                            "no test in %s; its test class may be %s"
                                .formatted(file.getFileName(), symbol.location().path()))
                    .orElseGet(() -> "no test in %s".formatted(file.getFileName())));
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
      throw new Refused(
          scope == TestScope.METHOD
              ? "no test method '%s' in %s".formatted(method, file.getFileName())
              : "no %s test target in %s"
                  .formatted(scope.name().toLowerCase(Locale.ROOT), file.getFileName()));
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
  public Noted<LatheCallHierarchy> callHierarchy(
      final Path file,
      final int line,
      final int column,
      final CallDirection direction,
      final SymbolScope scope,
      final int maxResults) {
    final boolean incoming = direction == CallDirection.INCOMING;
    return noted(
            List.of(file),
            positionWork(
                file,
                line,
                column,
                (session, uri, pos, cancel, progress) ->
                    session.search().prepareCallHierarchyFuture(uri, pos)))
        .map(
            items -> {
              if (items.isEmpty()) {
                return new LatheCallHierarchy(incoming, 0, false, List.of());
              }

              final List<LatheCall> all =
                  incoming ? incomingCalls(items.get(0)) : outgoingCalls(items.get(0), scope);
              return new LatheCallHierarchy(
                  incoming,
                  all.size(),
                  all.size() > maxResults,
                  all.stream().limit(maxResults).toList());
            });
  }

  // One LatheCall per call site (a caller may invoke the symbol at several ranges).
  private List<LatheCall> incomingCalls(final CallHierarchyItem item) {
    return request(
            (session, cancel, progress) ->
                session.search().incomingCallsFuture(item, cancel, progress))
        .stream()
        .flatMap(
            call ->
                callSites(call.getFrom().getName(), call.getFrom().getUri(), call.getFromRanges()))
        .toList();
  }

  // Where the method calls each callee, in source order, so the answer reads as the method does.
  private List<LatheCall> outgoingCalls(final CallHierarchyItem item, final SymbolScope scope) {
    return request((session, cancel, progress) -> session.search().outgoingCallsFuture(item))
        .stream()
        .filter(call -> scope.includes(origin(LatheUri.toPath(call.getTo().getUri()))))
        .flatMap(call -> callSites(call.getTo().getName(), item.getUri(), call.getFromRanges()))
        .sorted(
            Comparator.comparingInt((LatheCall call) -> call.location().line())
                .thenComparingInt(call -> call.location().column()))
        .toList();
  }

  private Stream<LatheCall> callSites(
      final String name, final String uri, final List<Range> ranges) {
    return ranges.stream()
        .map(range -> new LatheCall(name, toLatheLocation(new Location(uri, range))));
  }

  // The reactor's modules, each with the reactor modules it depends on and its test-launch capture.
  public Noted<List<LatheModuleSummary>> modules() {
    return noted(
        List.of(),
        (session, cancel, progress) ->
            CompletableFuture.completedFuture(session.verifier().moduleSummaries()));
  }

  // Recompiles the change set and its callers in-process; Lathe never runs the build, it names the
  // sync that would. No files: every change not yet built, on disk or by the tools.
  // A pending build-file sync or a running build defers the check to a full sync.
  public LatheVerifyChange verifyChange(final List<Path> files) {
    final ChangeVerifier.VerifyPlan plan =
        request(
            (session, cancel, progress) ->
                session
                    .reconcileForVerify()
                    .thenApply(outcome -> session.verifier().plan(outcome, files)));
    final ReconcileOutcome outcome = plan.outcome();
    if (outcome.deferral() != ReconcileOutcome.DeferReason.NONE) {
      // Another server's pass ends on its own; a sync would only add a build to wait for.
      final String sync =
          outcome.deferral() == ReconcileOutcome.DeferReason.OTHER_SERVER_WRITING
              ? null
              : LatheLayout.SYNC_SCRIPT_COMMAND;
      return new LatheVerifyChange(outcome.deferral(), List.of(), List.of(), sync);
    }

    if (plan.changed().isEmpty()) {
      return new LatheVerifyChange(null, List.of(), List.of(), null);
    }

    final var checked = Stopwatch.start();
    final Map<String, List<Path>> targets = plan.targets();
    final Map<String, String> contents =
        targets.values().stream()
            .flatMap(List::stream)
            .distinct()
            .collect(
                Collectors.toUnmodifiableMap(
                    LatheUri::fromPath, LatheEngine::read, (first, sameFile) -> first));
    // Every check starts before any is awaited, so the modules compile side by side, each on its
    // own worker; one at a time, a change with callers across a large reactor took seconds. A check
    // keeps no analysis: opened, each file would hold one until the cache evicted it, and on a
    // large reactor they outgrew the heap.
    final Map<String, List<Diagnostic>> analyses =
        request((session, cancel, progress) -> checkAll(session, contents));
    final Set<Path> changedSet = Set.copyOf(plan.changed());
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
    return new LatheVerifyChange(
        null, perModule, plan.downstream(), syncFor(plan.downstream(), plan.moduleCount()));
  }

  // Compiles every file as a transient check, side by side on their modules' workers, and answers
  // once all are done.
  private static CompletableFuture<Map<String, List<Diagnostic>>> checkAll(
      final WorkspaceSession session, final Map<String, String> contents) {
    return FeatureAnswers.concatAll(
            contents.entrySet().stream()
                .map(
                    entry ->
                        session
                            .features()
                            .diagnosticsTransientFuture(entry.getKey(), entry.getValue())
                            .thenApply(
                                diagnostics -> List.of(Map.entry(entry.getKey(), diagnostics)))))
        .thenApply(
            entries ->
                entries.stream()
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)));
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
      final Map<String, List<Diagnostic>> analyses) {
    return files.stream()
        .flatMap(
            file -> reportedIn(file, changed.contains(file), analyses.get(LatheUri.fromPath(file))))
        .sorted(Comparator.comparing(LatheDiagnostic::severity))
        .toList();
  }

  private Stream<LatheDiagnostic> reportedIn(
      final Path file, final boolean changedFile, final List<Diagnostic> diagnostics) {
    final String path = displayPath(file, LatheUri.fromPath(file));
    return diagnostics.stream()
        .filter(diagnostic -> reported(diagnostic, changedFile))
        .map(diagnostic -> LatheDiagnostic.of(path, diagnostic));
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
  public Noted<LatheChangeImpact> analyzeChange(final Path file, final int line, final int column) {
    final Noted<ChangeFacts> read =
        noted(
            List.of(file),
            positionWork(
                file,
                line,
                column,
                (session, uri, pos, cancel, progress) ->
                    session
                        .features()
                        .hoverFuture(uri, pos)
                        .thenCompose(
                            hover ->
                                session
                                    .search()
                                    .implementationFuture(uri, pos)
                                    .thenCompose(
                                        implementations ->
                                            session
                                                .search()
                                                .referencesFuture(uri, pos, true, cancel, progress)
                                                .thenApply(
                                                    references ->
                                                        new ChangeFacts(
                                                            hover,
                                                            implementations,
                                                            references))))));
    final ChangeFacts facts = read.value();
    final String signature = hoverText(facts.hover());
    final List<LatheLocation> overrideFamily =
        capImplementations(facts.implementations(), IMPACT_MAX_RESULTS).implementations();
    final LatheReferences refs = capReferences(facts.references(), IMPACT_MAX_RESULTS);
    final List<Path> paths =
        refs.references().stream().map(ref -> LatheUri.toPath(ref.uri())).toList();
    final Map<Path, ModulePlacement> placements =
        query(session -> session.verifier().classifyPaths(paths));

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

    return new Noted<>(
        new LatheChangeImpact(
            signature,
            overrideFamily,
            production,
            test,
            refs.truncated(),
            List.copyOf(affectedModules),
            List.copyOf(relevantTests)),
        read.notes());
  }

  // What analyze_change reads about the symbol, in one request: its hover, its override family and
  // its references. The hover may be absent.
  private record ChangeFacts(
      Hover hover,
      Either<List<? extends Location>, List<? extends LocationLink>> implementations,
      List<? extends Location> references) {

    ChangeFacts {
      ValidCheck.check()
          .notNull(implementations, "implementations")
          .notNull(references, "references")
          .validate();
    }
  }

  // What run_test reads before it picks the tests: whether code the file runs fails to compile,
  // and the file's runnable targets.
  private record TestFacts(String failure, List<RunTarget> targets) {

    TestFacts {
      targets = List.copyOf(targets);
    }

    // The first error in code the file runs, when it does not compile.
    Optional<String> failing() {
      return Optional.ofNullable(failure);
    }
  }

  private static String read(final Path file) {
    return IOUtil.unchecked(() -> Files.readString(file));
  }

  public void close() {
    host.close();
  }

  private LatheLocation toLatheLocation(final Location location) {
    final var targetPath = LatheUri.toPath(location.getUri());
    return new LatheLocation(
        location.getUri(),
        displayPath(targetPath, location.getUri()),
        location.getRange().getStart().getLine(),
        location.getRange().getStart().getCharacter(),
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
  private String snippet(final Path file, final Range range) {
    return sourceLine(file, range.getStart().getLine()).map(String::strip).orElse("");
  }

  // One request: the work runs on the event loop as a single composed chain.
  private <T> T request(final SessionHost.LoopWork<T> work) {
    return await(host.onLoopCompose(null, work));
  }

  private static <T> T await(final CompletableFuture<T> future) {
    try {
      return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw new IllegalStateException("the request was interrupted", e);
    } catch (final TimeoutException e) {
      // Cancelled, so a search that checks for it stops instead of running on unseen.
      future.cancel(true);
      throw new IllegalStateException(
          "the request timed out after %ds; see the server log".formatted(REQUEST_TIMEOUT_SECONDS),
          e);
    } catch (final ExecutionException e) {
      // A request the server refused carries its reason, which the caller must see; the engine's
      // own answers thrown inside a request reach the caller as they were thrown.
      final Throwable cause = ExceptionUtil.rootCause(e);
      if (cause instanceof final ResponseErrorException refused) {
        throw new Refused(refused.getMessage());
      }

      if (cause instanceof final NotOnName notOnName) {
        throw notOnName;
      }

      if (cause instanceof final Refused refused) {
        throw refused;
      }

      throw new IllegalStateException("the request failed; see the server log", e);
    }
  }
}
