package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.CamelCaseMatcher;
import io.github.aglibs.lathe.analysis.DeclarationNames;
import io.github.aglibs.lathe.analysis.ImplementationHit;
import io.github.aglibs.lathe.analysis.MissingImportsResult;
import io.github.aglibs.lathe.analysis.MissingImportsResult.MissingImport;
import io.github.aglibs.lathe.analysis.Positions;
import io.github.aglibs.lathe.analysis.ReferenceMatch;
import io.github.aglibs.lathe.analysis.ReferenceRole;
import io.github.aglibs.lathe.analysis.RenameProvider;
import io.github.aglibs.lathe.analysis.RunTarget;
import io.github.aglibs.lathe.analysis.RunnableKind;
import io.github.aglibs.lathe.analysis.SourcelessSymbol;
import io.github.aglibs.lathe.analysis.TextMentions;
import io.github.aglibs.lathe.analysis.TransientSource;
import io.github.aglibs.lathe.core.CollectionUtil;
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
import io.github.aglibs.lathe.server.engine.LatheChangeImpact.Family;
import io.github.aglibs.lathe.server.engine.LatheDefinition;
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
import io.github.aglibs.lathe.server.engine.LathePropertyMentions;
import io.github.aglibs.lathe.server.engine.LatheReference;
import io.github.aglibs.lathe.server.engine.LatheReferences;
import io.github.aglibs.lathe.server.engine.LatheRename;
import io.github.aglibs.lathe.server.engine.LatheSymbol;
import io.github.aglibs.lathe.server.engine.LatheTestRun;
import io.github.aglibs.lathe.server.engine.LatheVerifyChange;
import io.github.aglibs.lathe.server.engine.Noted;
import io.github.aglibs.lathe.server.format.FormatEngine;
import io.github.aglibs.lathe.server.format.FormatEngines;
import io.github.aglibs.lathe.server.run.LaunchTarget;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.Serial;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
import org.eclipse.lsp4j.SymbolKind;
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
  // A JDK type in a package its module does not export to everyone: listed last.
  private static final int JDK_INTERNAL_RANK = 3;
  private static final int REACTOR_RANK = 0;
  // The reactor's generated types: with its hand-written ones a tier, after them on a tie.
  private static final int GENERATED_RANK = 1;
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

  // Every put creates a fresh generation, so the LSP version is irrelevant to freshness here.
  private static final int VERSION = 1;

  private final SessionHost host = new SessionHost();
  private final AtomicLong runToken = new AtomicLong();
  private final Path workspaceRoot;
  // The workspace root with its links resolved, which a file's real path is compared against.
  private final Path realWorkspaceRoot;
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
    this.realWorkspaceRoot = IOUtil.unchecked(() -> workspaceRoot.toRealPath());
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
    host.execute(session -> session.formats().use(formatter, formatterSpec));
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
  public Noted<LatheDefinition> definition(final Path file, final int line, final int column) {
    return noted(
            List.of(file),
            positionWork(
                file,
                line,
                column,
                (session, uri, pos, cancel, progress) ->
                    session.features().explainedDefinitionFuture(uri, pos)))
        .map(
            answer ->
                new LatheDefinition(
                    answer.locations().stream().map(this::toLatheLocation).toList(),
                    answer
                        .jdkSourcesNote()
                        .or(
                            () ->
                                answer
                                    .sourcelessNote()
                                    .map(
                                        note ->
                                            "%s; describe_symbol shows its declaration from the class file."
                                                .formatted(note)))
                        .orElse(null)));
  }

  /**
   * Uses of the symbol at {@code line}/{@code column} (0-based) across the reactor that have one of
   * the {@code kept} accesses, each enriched with a snippet, capped to {@code maxResults} with the
   * true total for pagination. A declaration is no use, neither the symbol's own nor an override's:
   * an unused symbol has none, as {@link #analyzeChange} counts them, and is still told from a
   * position that names no symbol. An unused field or accessor carries where its property name is
   * still text.
   */
  public Noted<LatheReferences> references(
      final Path file,
      final int line,
      final int column,
      final int maxResults,
      final Set<LatheReference.Access> kept) {
    final Noted<LatheReferences> found =
        noted(
                List.of(file),
                positionWork(
                    file,
                    line,
                    column,
                    (session, uri, pos, cancel, progress) ->
                        session.search().referencesFuture(uri, pos, true, cancel, progress)))
            .map(matches -> keptReferences(matches, kept, maxResults, file, line, column));
    final LatheReferences refs = found.value();
    if (!refs.resolved() || refs.total() > 0 || refs.filteredOut() > 0) {
      return found;
    }

    return found.map(unused -> unused.withPropertyMentions(propertyMentions(file, line, column)));
  }

  // A field or accessor no Java code uses may still be bound by its property name, which javac
  // does not resolve: a template's ${owner.city}, a framework reading accessors, a test's
  // hasProperty("city"). Where that name is still text, as rename lists an old name (MC-109).
  // Asked only for an unused symbol, so the scan stays rare.
  private LathePropertyMentions propertyMentions(
      final Path file, final int line, final int column) {
    final Optional<String> property = propertyAt(file, line, column);
    if (property.isEmpty()) {
      return LathePropertyMentions.NONE;
    }

    final List<Location> mentions =
        textMentions(property.get(), "references").stream()
            .filter(
                mention ->
                    origin(LatheUri.toPath(mention.getUri())) == LatheLocation.Origin.REACTOR)
            .toList();
    return new LathePropertyMentions(
        property.get(),
        mentions.stream().limit(LISTED_MENTIONS).map(this::toLatheLocation).toList(),
        mentions.size());
  }

  // Best effort, as the answer it adds to is complete without it.
  private Optional<String> propertyAt(final Path file, final int line, final int column) {
    try {
      return request(
              positionWork(
                  file,
                  line,
                  column,
                  (session, uri, pos, cancel, progress) -> session.search().targetFuture(uri, pos)))
          .flatMap(BeanProperties::name);
    } catch (final RuntimeException e) {
      LOG.log(
          Level.WARNING,
          e,
          () -> "[references] %s:%d property not resolved".formatted(file, line + 1));
      return Optional.empty();
    }
  }

  private LatheReferences keptReferences(
      final List<ReferenceMatch> matches,
      final Set<LatheReference.Access> kept,
      final int maxResults,
      final Path file,
      final int line,
      final int column) {
    // The declarations come too: a position naming a symbol is its declaration or one of its uses,
    // so an empty search means no symbol, and an unused symbol finds only its declaration.
    final List<ReferenceMatch> found =
        matches.stream().filter(match -> match.role() != ReferenceRole.DECLARATION).toList();
    final List<ReferenceMatch> uses =
        ordered(
            found.stream().filter(match -> kept.contains(access(match))).toList(),
            ReferenceMatch::toLocation,
            () -> listHome(file, line, column));
    final long filteredOut = found.stream().filter(match -> !kept.contains(access(match))).count();
    return new LatheReferences(
        uses.size(),
        uses.size() > maxResults,
        uses.stream().limit(maxResults).map(this::toLatheReference).toList(),
        (int) filteredOut,
        !matches.isEmpty());
  }

  private LatheReference toLatheReference(final ReferenceMatch match) {
    return new LatheReference(toLatheLocation(match.toLocation()), access(match));
  }

  private static LatheReference.Access access(final ReferenceMatch match) {
    return switch (match.role()) {
      case READ -> LatheReference.Access.READ;
      case WRITE -> LatheReference.Access.WRITE;
      case DECLARATION, IMPORT, SHARED_STATIC_IMPORT, INVOCATION, TYPE_USE ->
          LatheReference.Access.OTHER;
    };
  }

  private CappedLocations capLocations(
      final List<? extends Location> locations,
      final int maxResults,
      final Supplier<ListHome> home) {
    final List<LatheLocation> capped =
        ordered(locations, (Location found) -> found, home).stream()
            .limit(maxResults)
            .map(this::toLatheLocation)
            .toList();
    return new CappedLocations(locations.size(), locations.size() > maxResults, capped);
  }

  private record CappedLocations(int total, boolean truncated, List<LatheLocation> locations) {
    private CappedLocations {
      ValidCheck.check().notNull(locations, "locations").validate();
      locations = List.copyOf(locations);
    }
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
        .map(either -> capImplementations(either, maxResults, () -> listHome(file, line, column)));
  }

  private LatheImplementations capImplementations(
      final Either<List<? extends Location>, List<? extends LocationLink>> either,
      final int maxResults,
      final Supplier<ListHome> home) {
    // The implementation search answers in locations (FeatureAnswers.definitionResult).
    final CappedLocations capped = capLocations(either.getLeft(), maxResults, home);
    return new LatheImplementations(capped.total(), capped.truncated(), capped.locations());
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
    final String content = ChangeVerifier.read(file);
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
  // that names the symbol instead of counting its column; a qualified name, as an import or a
  // requires spells it, stands at its last segment that is a name (java.util.List at List,
  // java.util.* at util, String.class at String). The text only finds the candidates: javac
  // resolves each, and refuses one inside a comment or a string (NotOnName).
  public List<Integer> nameColumns(final Path file, final int line, final String name) {
    final String text = sourceLine(file, line).orElse("");
    if (name.indexOf('.') < 0) {
      return TextMentions.wordStarts(text, name).boxed().toList();
    }

    int end = name.length();
    int segment = -1;
    while (end > 0 && segment < 0) {
      final int start = name.lastIndexOf('.', end - 1) + 1;
      final String part = name.substring(start, end);
      if (SourceVersion.isIdentifier(part) && !SourceVersion.isKeyword(part)) {
        segment = start;
      }

      end = start - 1;
    }

    if (segment < 0) {
      return List.of();
    }

    final int offset = segment;
    return IntStream.rangeClosed(0, text.length() - name.length())
        .filter(at -> text.startsWith(name, at))
        .filter(at -> DeclarationNames.identifierStart(text, at) == at)
        .filter(
            at ->
                at + name.length() == text.length()
                    || !Character.isJavaIdentifierPart(text.charAt(at + name.length())))
        .map(at -> at + offset)
        .boxed()
        .toList();
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
                (session, uri, pos, cancel, progress) ->
                    session.features().hoverFuture(uri, pos, cancel)))
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
                kindName(symbol.getKind()).toLowerCase(Locale.ROOT),
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
                    .thenApply(
                        outcome ->
                            new SymbolHits(
                                session.features().workspaceSymbol(name, reactorOnly),
                                session.features().sourcelessSymbols(name, reactorOnly))))
        .map(hits -> ranked(name, hits).limit(maxResults).toList());
  }

  // The located hits by rank, as IntelliJ ranks them: the type the query names first, in its case
  // before ignoring it; then the reactor's types before the JDK's and dependencies', and within
  // each how well the name matches (MC-113: Helidon's generated ListenerConfig is its public API,
  // not to be listed after every hand-written type that matches worse); a hand-written type before
  // a generated one that matches as well. The sourceless hits, which have no file, go after the
  // located ones among the dependencies' types, and JDK internals last, a named one too (MC-110).
  // Lazy, so only the hits kept read their snippet.
  private Stream<LatheSymbol> ranked(final String query, final SymbolHits hits) {
    final String simpleQuery = query.substring(query.lastIndexOf('.') + 1);
    final String lowerQuery = query.toLowerCase(Locale.ROOT);
    final var degrees = new HashMap<String, Integer>();
    final var ranks = new IdentityHashMap<SymbolInformation, Integer>();
    final var named = new IdentityHashMap<SymbolInformation, Integer>();
    for (final SymbolInformation symbol : hits.located()) {
      degrees.computeIfAbsent(
          symbol.getName(),
          name -> CamelCaseMatcher.degree(simpleQuery, name).orElse(Integer.MIN_VALUE));
      ranks.put(symbol, rank(symbol));
      named.put(symbol, namedRank(symbol, query, lowerQuery));
    }

    final List<SymbolInformation> located =
        hits.located().stream()
            .sorted(
                Comparator.comparingInt((SymbolInformation symbol) -> named.get(symbol))
                    .thenComparingInt(symbol -> tier(ranks.get(symbol)))
                    .thenComparing(
                        Comparator.comparingInt(
                                (SymbolInformation symbol) -> degrees.get(symbol.getName()))
                            .reversed())
                    .thenComparingInt(ranks::get))
            .toList();
    return Stream.of(
            located.stream()
                .filter(symbol -> ranks.get(symbol) < JDK_INTERNAL_RANK)
                .map(this::toLatheSymbol),
            hits.sourceless().stream().map(LatheEngine::toLatheSymbol),
            located.stream()
                .filter(symbol -> ranks.get(symbol) == JDK_INTERNAL_RANK)
                .map(this::toLatheSymbol))
        .flatMap(Function.identity());
  }

  // 0 when the symbol is the type the query names in the query's case, 1 when only ignoring case,
  // 2 when it does not name it.
  private static int namedRank(
      final SymbolInformation symbol, final String query, final String lowerQuery) {
    final String qualified = qualifiedName(symbol);
    if (names(symbol.getName(), qualified, query)) {
      return 0;
    }

    final boolean ignoringCase =
        names(
            symbol.getName().toLowerCase(Locale.ROOT),
            qualified.toLowerCase(Locale.ROOT),
            lowerQuery);
    return ignoringCase ? 1 : 2;
  }

  // A search ranks the reactor's hand-written and generated types as one tier.
  private static int tier(final int rank) {
    return rank == GENERATED_RANK ? REACTOR_RANK : rank;
  }

  // A search's hits with a file, and those in dependencies without sources, which have none.
  private record SymbolHits(List<SymbolInformation> located, List<SourcelessSymbol> sourceless) {
    SymbolHits {
      ValidCheck.check().notNull(located, "located").notNull(sourceless, "sourceless").validate();
      located = List.copyOf(located);
      sourceless = List.copyOf(sourceless);
    }
  }

  // Whether a type is the one the query names: its simple name, or a qualified name ending in it
  // (`ListenerConfig.Builder`, `io.helidon.webserver.ListenerConfig`). A generated type is often
  // the one code uses, its hand-written blueprint package-private (MC-110).
  private static boolean names(
      final String simpleName, final String qualified, final String query) {
    return simpleName.equals(query)
        || qualified.equals(query)
        || qualified.endsWith(".%s".formatted(query));
  }

  private static String qualifiedName(final SymbolInformation symbol) {
    final String container = symbol.getContainerName();
    return container == null || container.isEmpty()
        ? symbol.getName()
        : "%s.%s".formatted(container, symbol.getName());
  }

  // The reactor's hand-written types, then its generated ones, then the JDK's and dependencies',
  // then JDK internals: hidden from a plain project, but one that adds exports (an annotation
  // processor, a javac plugin) uses them. The reactor's two are one tier in a search, this order
  // breaking a tie in how well their names match.
  private int rank(final SymbolInformation symbol) {
    return switch (origin(LatheUri.toPath(symbol.getLocation().getUri()))) {
      case REACTOR -> REACTOR_RANK;
      case GENERATED -> GENERATED_RANK;
      case EXTERNAL -> jdkInternal(symbol.getContainerName()) ? JDK_INTERNAL_RANK : 2;
    };
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

  // The Java word where LSP has none: a record is a Struct there, an enum constant an EnumMember.
  private static String kindName(final SymbolKind kind) {
    return switch (kind) {
      case Struct -> "Record";
      case EnumMember -> "Enum constant";
      default -> kind.name();
    };
  }

  private LatheSymbol toLatheSymbol(final SymbolInformation symbol) {
    final String container = symbol.getContainerName() == null ? "" : symbol.getContainerName();
    return new LatheSymbol(
        symbol.getName(),
        kindName(symbol.getKind()),
        container,
        toLatheLocation(symbol.getLocation()));
  }

  private static LatheSymbol toLatheSymbol(final SourcelessSymbol symbol) {
    return new LatheSymbol(
        symbol.name(), kindName(symbol.kind()), symbol.container(), null, symbol.dependency());
  }

  /**
   * Rename the symbol at {@code line}/{@code column} (0-based) to {@code newName} across the
   * reactor, applying the javac-computed edits to disk. Refuses (throws) if the rename would touch
   * a file outside the reactor, since those cannot be safely rewritten.
   */
  public LatheRename rename(
      final Path file, final int line, final int column, final String newName) {
    final String original = ChangeVerifier.read(file);
    final Optional<List<Location>> localMentions =
        request(
            positionWork(
                file,
                line,
                column,
                (session, uri, pos, cancel, progress) ->
                    session.renames().localTextMentionsFuture(uri, pos)));
    // The engine moves files itself, so a type's rename may move its file.
    final RenameEdit renamed =
        request(
            positionWork(
                file,
                line,
                column,
                (session, uri, pos, cancel, progress) ->
                    session.renames().renameFuture(uri, pos, newName, true, cancel, progress)));
    final WorkspaceEdit edit = renamed.edit();
    final String oldName = wordAt(file, line, column).orElseThrow();
    final Map<String, List<TextEdit>> textEdits = RenameProvider.textEdits(edit);
    final List<LatheLocation> generated =
        renamed.leftInGenerated().stream()
            .limit(LISTED_MENTIONS)
            .map(this::toLatheLocation)
            .toList();
    final int generatedCount = renamed.leftInGenerated().size();
    if (textEdits.isEmpty()) {
      return new LatheRename(
          newName, 0, List.of(), List.of(), 0, List.of(), 0, generated, generatedCount);
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

    // Searched before the edits are written, so a failed walk leaves the workspace untouched.
    final List<Location> outside =
        OutsideSources.mentioning(
            workspaceRoot, query(session -> session.verifier().sourceRoots()), oldName);

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
    // Recorded as they are before the write pass, which may regenerate them from the renamed
    // sources: a file regenerated by then is no longer the rename's to check.
    final List<Path> leftInGenerated =
        renamed.leftInGenerated().stream()
            .map(location -> LatheUri.toPath(location.getUri()))
            .distinct()
            .toList();
    if (!leftInGenerated.isEmpty()) {
      request(
          (session, cancel, progress) -> {
            session.verifier().checkUntilRegenerated(leftInGenerated);
            return CompletableFuture.completedFuture(leftInGenerated);
          });
    }

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
            .orElseGet(() -> textMentions(oldName, "rename"))
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
        mentions.size(),
        outside.stream().limit(LISTED_MENTIONS).map(this::toLatheLocation).toList(),
        outside.size(),
        generated,
        generatedCount);
  }

  private static List<Location> renamedMentions(
      final List<Location> mentions, final String original, final List<TextEdit> edits) {
    final String renamed = Positions.applyEdits(original, edits);
    final var results = new ArrayList<Location>();
    for (final Location mention : mentions) {
      final Position at = mention.getRange().getStart();
      final int before = Positions.toOffset(original, at);
      int after = before;
      for (final TextEdit edit : edits) {
        final Range range = edit.getRange();
        final int end = Positions.toOffset(original, range.getEnd());
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
  private List<Location> textMentions(final String name, final String operation) {
    try {
      return TextMentions.find(query(session -> session.verifier().textMentionSources(name)), name);
    } catch (final RuntimeException e) {
      LOG.log(
          Level.WARNING, e, () -> "[%s] text mentions of %s not listed".formatted(operation, name));
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
            lastLine(CollectionUtil.last(edits))));
  }

  // Adds the imports that have one candidate; the rest are the agent's to choose (ambiguous) or
  // fix (unknown names). An import can make another name resolve: a supertype brings the types
  // nested in it, so the names left are read again after each round that added any.
  public LatheImports fixImports(final Path file) {
    requireReactorFile(file);
    final String uri = LatheUri.fromPath(file);
    final var added = new ArrayList<String>();
    MissingImportsResult missing = missingImports(file);
    List<String> round = unambiguous(missing, added);
    while (!round.isEmpty()) {
      writeFile(file, uri, missing.edits(round));
      added.addAll(round);
      missing = missingImports(file);
      round = unambiguous(missing, added);
    }

    return new LatheImports(
        displayPath(file, uri), added.stream().sorted().toList(), missing.items());
  }

  // Missing imports are read from the file's document, so it is registered first.
  private MissingImportsResult missingImports(final Path file) {
    return request(
        fileWork(
            file,
            (session, fileUri, diagnostics, cancel, progress) ->
                session.features().missingImportsFuture(fileUri)));
  }

  // A name with one candidate, unless a type nested in one of the file's other missing names is
  // named so too: once that type is imported the name may be the nested one, inherited from it
  // (HealthCheck.Result), so the name waits for the next round.
  private static List<String> unambiguous(
      final MissingImportsResult missing, final List<String> added) {
    final Set<String> missingNames =
        missing.items().stream().map(MissingImport::name).collect(Collectors.toUnmodifiableSet());
    return missing.items().stream()
        .filter(item -> item.candidates().size() == 1)
        .filter(
            item -> item.nested().stream().noneMatch(nested -> nestedInAny(nested, missingNames)))
        .map(item -> item.candidates().get(0))
        .filter(candidate -> !added.contains(candidate))
        .toList();
  }

  // Whether the nested type's enclosing type is one of the names (a.b.Outer.Inner is in Outer).
  private static boolean nestedInAny(final String nested, final Set<String> names) {
    final String outer = nested.substring(0, nested.lastIndexOf('.'));
    return names.contains(outer.substring(outer.lastIndexOf('.') + 1));
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
                  final Optional<String> failing =
                      session
                          .verifier()
                          .failingUnder(file)
                          .or(() -> firstError(file, uri, diagnostics));
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
                    session.runs().runFuture(new LaunchTarget.Tests(moduleRel, selections), token)),
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
  // it first), and a file's every top-level test class runs, not only the first. A method runs in
  // every class that declares it, nested ones too, as a method selector reaches only its own class.
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

    if (scope == TestScope.METHOD) {
      return matching;
    }

    final long outermost =
        matching.stream().mapToLong(LatheEngine::nestingDepth).min().orElseThrow();
    return matching.stream().filter(target -> nestingDepth(target) == outermost).toList();
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
  // The callers' list is ordered from the declaration's module, as the declaration is asked about.
  private List<LatheCall> incomingCalls(final CallHierarchyItem item) {
    final List<Map.Entry<String, Location>> sites =
        request(
                (session, cancel, progress) ->
                    session.search().incomingCallsFuture(item, cancel, progress))
            .stream()
            .flatMap(
                call ->
                    call.getFromRanges().stream()
                        .map(
                            range ->
                                Map.entry(
                                    call.getFrom().getName(),
                                    new Location(call.getFrom().getUri(), range))))
            .toList();
    return ordered(
            sites,
            Map.Entry::getValue,
            () -> listHome(query(WorkspaceSession::layout), LatheUri.toPath(item.getUri())))
        .stream()
        .map(site -> new LatheCall(site.getKey(), toLatheLocation(site.getValue())))
        .toList();
  }

  // The order every capped list answers in: the project's own sources, then its generated ones,
  // then the JDK's and dependencies'; within the project's, the files of a module before those of
  // none, main sources before tests, the module declaring the symbol first in each, then by
  // module, file and position. Path order alone put a sibling module whose name sorts first, and
  // its tests, before the declaring module (MC-100); and the declaring module's tests ahead of
  // other modules' production code would hide the uses a change breaks. A cap then keeps what the
  // agent edits. The layout and the declaring module are read only for a list with something to
  // order.
  private <T> List<T> ordered(
      final List<? extends T> items,
      final Function<? super T, Location> location,
      final Supplier<ListHome> home) {
    if (items.size() < 2) {
      return List.copyOf(items);
    }

    final ListHome listHome = home.get();
    final Map<Path, Optional<SourceLayout.Placement>> placements = new HashMap<>();
    final List<ListOrderKey<T>> keys = new ArrayList<>(items.size());
    for (final T item : items) {
      final Location at = location.apply(item);
      final Path path = LatheUri.toPath(at.getUri());
      final Optional<SourceLayout.Placement> placement =
          placements.computeIfAbsent(path, listHome.layout()::placementOf);
      keys.add(new ListOrderKey<>(item, at, origin(path), placement.orElse(null)));
    }

    final Comparator<ListOrderKey<T>> order =
        Comparator.comparing((ListOrderKey<T> key) -> key.origin())
            .thenComparing(key -> key.placement() == null)
            .thenComparing(key -> key.placement() != null && key.placement().test())
            .thenComparing(key -> !listHome.declares(key.placement()))
            .thenComparing(key -> key.placement() == null ? "" : key.placement().moduleRel())
            .thenComparing(key -> key.location().getUri())
            .thenComparingInt(key -> key.location().getRange().getStart().getLine())
            .thenComparingInt(key -> key.location().getRange().getStart().getCharacter());
    keys.sort(order);
    return keys.stream().map(ListOrderKey::item).toList();
  }

  // An item of a capped list with what orders it; placement is null for a file in no module.
  private record ListOrderKey<T>(
      T item, Location location, LatheLocation.Origin origin, SourceLayout.Placement placement) {
    ListOrderKey {
      ValidCheck.check()
          .notNull(item, "item")
          .notNull(location, "location")
          .notNull(origin, "origin")
          .validate();
    }
  }

  // What a capped list is ordered from: the workspace's layout and the module declaring the
  // symbol, null where the declaration is in no module of the project.
  private record ListHome(SourceLayout layout, String declaringModule) {
    ListHome {
      ValidCheck.check().notNull(layout, "layout").validate();
    }

    boolean declares(final SourceLayout.Placement placement) {
      return placement != null && placement.moduleRel().equals(declaringModule);
    }
  }

  // The symbol at the position's declaration, as definition finds it, read in the same request as
  // the layout; the asked file where it resolves to none.
  private ListHome listHome(final Path file, final int line, final int column) {
    return request(
        positionWork(
            file,
            line,
            column,
            (session, uri, pos, cancel, progress) ->
                session
                    .features()
                    .explainedDefinitionFuture(uri, pos)
                    .thenApply(
                        answer ->
                            listHome(
                                session.layout(),
                                answer.locations().stream()
                                    .map(found -> LatheUri.toPath(found.getUri()))
                                    .findFirst()
                                    .orElse(file)))));
  }

  private static ListHome listHome(final SourceLayout layout, final Path declaration) {
    return new ListHome(
        layout,
        layout.placementOf(declaration).map(SourceLayout.Placement::moduleRel).orElse(null));
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
  // A pending build-file change defers the check to the sync it names; a running build, or another
  // server updating .lathe/, defers it until that ends.
  public LatheVerifyChange verifyChange(final List<Path> files) {
    final ChangeVerifier.VerifyPlan plan =
        request(
            (session, cancel, progress) ->
                session
                    .reconcileForVerify()
                    .thenApply(outcome -> session.verifier().plan(outcome, files)));
    final ReconcileOutcome outcome = plan.outcome();
    if (outcome.deferral() != ReconcileOutcome.DeferReason.NONE) {
      final String sync =
          outcome.deferral().resolvedBySync() ? LatheLayout.SYNC_SCRIPT_COMMAND : null;
      return new LatheVerifyChange(
          outcome.deferral(), List.of(), List.of(), sync, List.of(), List.of());
    }

    if (plan.changed().isEmpty() && plan.targets().isEmpty()) {
      return new LatheVerifyChange(null, List.of(), List.of(), null, List.of(), List.of());
    }

    final var checked = Stopwatch.start();
    final Map<String, List<Path>> targets = plan.targets();
    final Map<String, String> contents =
        targets.values().stream()
            .flatMap(List::stream)
            .distinct()
            .collect(
                Collectors.toUnmodifiableMap(
                    LatheUri::fromPath, ChangeVerifier::read, (first, sameFile) -> first));
    // Every check starts before any is awaited, so the modules compile side by side, each on its
    // own worker; one at a time, a change with callers across a large reactor took seconds. A check
    // keeps no analysis: opened, each file would hold one until the cache evicted it, and on a
    // large reactor they outgrew the heap.
    final Set<Path> changedSet = Set.copyOf(plan.changed());
    final Set<String> changedUris =
        changedSet.stream().map(LatheUri::fromPath).collect(Collectors.toUnmodifiableSet());
    // The classes of a changed file the build's compile fails are those of its last good compile:
    // its callers are analyzed with its edited source instead, so they see what it declares now.
    final List<TransientSource> failedSources =
        plan.failedWrites().keySet().stream()
            .map(LatheUri::fromPath)
            .filter(contents::containsKey)
            .map(uri -> new TransientSource(uri, contents.get(uri)))
            .toList();
    final Map<String, List<Diagnostic>> analyses =
        withFailedWrites(
            request(
                (session, cancel, progress) ->
                    session.verifier().check(contents, changedUris, failedSources, cancel)),
            plan.failedWrites());
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
        null,
        perModule,
        plan.downstream(),
        syncFor(plan.downstream(), plan.moduleCount()),
        plan.failedWrites().keySet().stream()
            .map(file -> displayPath(file, LatheUri.fromPath(file)))
            .sorted()
            .toList(),
        plan.uncheckedCallers().stream()
            .map(file -> displayPath(file, LatheUri.fromPath(file)))
            .toList());
  }

  // A changed file the build's compile fails also reports why: the processors and plugins that
  // compile runs, and the analysis compile does not, fail it where the check passes.
  private static Map<String, List<Diagnostic>> withFailedWrites(
      final Map<String, List<Diagnostic>> checked, final Map<Path, List<Diagnostic>> failedWrites) {
    final var merged = new HashMap<>(checked);
    failedWrites.forEach(
        (file, causes) -> merged.merge(LatheUri.fromPath(file), causes, WritePass::withWriteOnly));
    return Map.copyOf(merged);
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
            file ->
                reportedIn(
                    file,
                    changed.contains(file),
                    analyses.getOrDefault(LatheUri.fromPath(file), List.of())))
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
   * family, production vs test reference counts, the reactor modules that use or implement it, and
   * the relevant test classes. Read-only — composes definition/reference analysis, no compile of
   * the change.
   */
  public Noted<LatheChangeImpact> analyzeChange(
      final Path file, final int line, final int column, final int maxResults) {
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
                        .hoverFuture(uri, pos, cancel)
                        .thenCompose(
                            hover ->
                                session
                                    .search()
                                    .familyFuture(uri, pos)
                                    .thenCombine(
                                        session.search().symbolFamilyFuture(uri, pos),
                                        (members, symbol) -> Map.entry(symbol, members))
                                    .thenCompose(
                                        found ->
                                            session
                                                .search()
                                                // Uses only: the declarations are the family.
                                                .referencesFuture(uri, pos, false, cancel, progress)
                                                .thenApply(ReferenceSearch::toLocations)
                                                .thenApply(
                                                    references ->
                                                        new ChangeFacts(
                                                            hover,
                                                            found.getKey().family(),
                                                            found.getKey().overrides(),
                                                            found.getValue(),
                                                            references))))));
    final ChangeFacts facts = read.value();
    final String signature = hoverText(facts.hover());
    // Read once for both lists, and only where there is something to order.
    final ListHome declaring = facts.members().size() < 2 ? null : listHome(file, line, column);
    final Supplier<ListHome> home = () -> declaring;
    final CappedLocations overriddenBy = familyMembers(facts.members(), false, maxResults, home);
    final CappedLocations redeclaredBy = familyMembers(facts.members(), true, maxResults, home);
    // Every reference is counted: a count from a capped list would read as the whole.
    final List<Path> paths =
        facts.references().stream().map(ref -> LatheUri.toPath(ref.getUri())).toList();
    // A signature change breaks every implementation and redeclaration, in a module that never
    // calls it too.
    final List<Path> implementationPaths =
        facts.members().stream().map(hit -> LatheUri.toPath(hit.location().getUri())).toList();
    final Map<Path, ModulePlacement> placements =
        query(
            session ->
                session
                    .verifier()
                    .classifyPaths(
                        Stream.concat(paths.stream(), implementationPaths.stream())
                            .distinct()
                            .toList()));

    int production = 0;
    int test = 0;
    final var affectedModules = new TreeSet<String>();
    final var relevantTests = new TreeSet<String>();
    for (final Path ref : paths) {
      final ModulePlacement placement = placements.get(ref);
      if (placement == null) {
        continue; // a reference outside the reactor (dependency/JDK) — not in the impact set
      }

      affectedModules.add(placement.moduleRel());
      if (placement.test()) {
        test++;
        relevantTests.add(FileUtil.javaTypeName(ref));
      } else {
        production++;
      }
    }

    // A test that implements the symbol (a stub, a fake) breaks too.
    for (final Path implementation : implementationPaths) {
      final ModulePlacement placement = placements.get(implementation);
      if (placement == null) {
        continue;
      }

      affectedModules.add(placement.moduleRel());
      if (placement.test()) {
        relevantTests.add(FileUtil.javaTypeName(implementation));
      }
    }

    final var impact =
        new LatheChangeImpact(
            signature,
            facts.family(),
            facts.overrides(),
            overriddenBy.locations(),
            overriddenBy.total(),
            redeclaredBy.locations(),
            redeclaredBy.total(),
            production,
            test,
            List.copyOf(affectedModules),
            List.copyOf(relevantTests));
    // No reference at all: a field or accessor may still be bound by its property name.
    return new Noted<>(
        production + test == 0 && !signature.isBlank()
            ? impact.withPropertyMentions(propertyMentions(file, line, column))
            : impact,
        read.notes());
  }

  // What analyze_change reads about the symbol, in one request: its hover, what kind of family it
  // has, the methods it overrides, the members below it, and its references. The hover may be
  // absent.
  private record ChangeFacts(
      Hover hover,
      Family family,
      List<String> overrides,
      List<ImplementationHit> members,
      List<? extends Location> references) {

    ChangeFacts {
      ValidCheck.check()
          .notNull(family, "family")
          .notNull(overrides, "overrides")
          .notNull(members, "members")
          .notNull(references, "references")
          .validate();
      overrides = List.copyOf(overrides);
      members = List.copyOf(members);
    }
  }

  // The family members below the symbol of one kind, implementations or redeclarations: the first
  // maxResults listed, all counted.
  private CappedLocations familyMembers(
      final List<ImplementationHit> family,
      final boolean redeclarations,
      final int maxResults,
      final Supplier<ListHome> home) {
    return capLocations(
        family.stream()
            .filter(hit -> hit.redeclaration() == redeclarations)
            .map(ImplementationHit::location)
            .toList(),
        maxResults,
        home);
  }

  // The file's own first compile error, by line, against the classes as they are now: a class it
  // uses may be gone or changed while the file is not, which no build compile failed. -Werror's
  // error belongs to no file, as for a failed build compile, and Lathe's own package-directory
  // error is not javac's: the build compiles and runs such a file.
  private Optional<String> firstError(
      final Path file, final String uri, final List<Diagnostic> diagnostics) {
    return ChangeVerifier.firstBuildError(diagnostics)
        .map(
            diagnostic ->
                "%s:%s".formatted(displayPath(file, uri), WritePass.errorLine(diagnostic)));
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

  // Whether the read tools answer for an existing file: one in the workspace, or one a module
  // compiles or the build extracted as a dependency's or the JDK's sources. By real path, so a link
  // in the workspace to a file outside it is outside too.
  public boolean inWorkspaceOrSources(final Path file) {
    final Path real = IOUtil.unchecked(() -> file.toRealPath());
    return real.startsWith(realWorkspaceRoot) || query(session -> session.verifier().routed(real));
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

  // A dependency's or the JDK's source has no workspace path: its absolute path, which the agent
  // can
  // open and hand back as it is, and whose cache directories name the artifact and its version. A
  // location in no file (a class in a jar or the JDK image) keeps its URI.
  private String displayPath(final Path target, final String uri) {
    if (target.startsWith(workspaceRoot)) {
      return FileUtil.slashed(workspaceRoot.relativize(target));
    }

    return uri.startsWith("file:") ? target.toString() : uri;
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
