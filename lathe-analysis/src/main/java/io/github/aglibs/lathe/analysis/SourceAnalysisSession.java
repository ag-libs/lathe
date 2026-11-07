package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.ClassTree;
import io.github.aglibs.lathe.analysis.completion.CompletionEngine;
import io.github.aglibs.lathe.analysis.completion.CompletionRequest;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

public final class SourceAnalysisSession implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(SourceAnalysisSession.class.getName());

  private final JavaSourceCompiler compiler;
  private final CompletionEngine completionEngine;
  private final DefinitionLocator definitionLocator;
  private final JavadocLocator javadocLocator;
  private final Map<String, CachedFileAnalysis> cache = new HashMap<>();
  // Per file, the content its last analysis parsed cleanly; absent when that analysis had a parse
  // error. Only such a sibling joins another file's analysis.
  private final Map<String, String> cleanParses = new HashMap<>();
  private final SourceParser parser;
  private final TypeHierarchyResolver typeHierarchyResolver;
  private final NavigationFeatures navigation;
  private final ReferenceFeatures references;
  private final HierarchyFeatures hierarchy;
  private final FileFeatures files;

  public SourceAnalysisSession(final JavaSourceCompiler compiler) {
    this.compiler = compiler;
    this.parser = new SourceParser();
    this.completionEngine = new CompletionEngine(parser, compiler);
    this.definitionLocator = new DefinitionLocator(parser);
    this.javadocLocator = new JavadocLocator(parser);
    this.typeHierarchyResolver = new TypeHierarchyResolver(parser);
    this.navigation =
        new NavigationFeatures(
            this, compiler, parser, definitionLocator, javadocLocator, typeHierarchyResolver);
    this.references = new ReferenceFeatures(this, compiler);
    this.hierarchy = new HierarchyFeatures(this, definitionLocator, typeHierarchyResolver);
    this.files = new FileFeatures(this, compiler, parser);
  }

  public NavigationFeatures navigation() {
    return navigation;
  }

  public ReferenceFeatures references() {
    return references;
  }

  public HierarchyFeatures hierarchy() {
    return hierarchy;
  }

  public FileFeatures files() {
    return files;
  }

  public List<Diagnostic> compile(
      final String uri, final String content, final int version, final CompileMode mode) {
    return compile(uri, content, version, mode, () -> {});
  }

  // Full-compile a tree's changed files together so a sealed/permits pair resolves; returns what
  // was written, for stale-output cleanup, and which sources failed.
  public BatchWrite compileBatch(
      final List<TransientSource> sources, final CancelChecker cancelChecker) {
    final var t = Stopwatch.start();
    final BatchWrite run = compiler.compileBatch(sources, cancelChecker);
    cancelChecker.checkCanceled();
    LOG.info(
        () ->
            "[compile:batch] %d files from %s %dms failed=%d"
                .formatted(
                    sources.size(), firstUri(sources), t.elapsedMs(), run.failedUris().size()));
    return run;
  }

  private List<Diagnostic> compile(
      final String uri,
      final String content,
      final int version,
      final CompileMode mode,
      final CancelChecker cancelChecker) {
    final var t = Stopwatch.start();
    final CompilerResult run = compiler.compile(uri, content, mode, cancelChecker);
    cancelChecker.checkCanceled();
    cache.put(uri, new CachedFileAnalysis(content, version, run.fileAnalysis()));
    recordParse(uri, content, run);
    return diagnosed(mode.tag, uri, content, run, t);
  }

  // A file's diagnostics, as an OPEN compile finds them, without keeping its analysis. A check of
  // many files at once would otherwise hold one analysis a file until the cache evicts it.
  public List<Diagnostic> diagnosticsTransient(final String uri, final String content) {
    final var t = Stopwatch.start();
    return diagnosed("check", uri, content, compiler.compileDetached(uri, content), t);
  }

  private List<Diagnostic> diagnosed(
      final String tag,
      final String uri,
      final String content,
      final CompilerResult run,
      final Stopwatch t) {
    final List<Diagnostic> diags = mapDiagnostics(run, content);
    LOG.info(
        () -> "[compile:%s] %s %dms diags=%d".formatted(tag, uri, t.elapsedMs(), diags.size()));
    return diags;
  }

  // Analyze several open documents in one task, each cached and diagnosed as its own OPEN compile
  // would be; returns the diagnostics by URI.
  public Map<String, List<Diagnostic>> compileOpenBatch(
      final List<TransientSource> sources,
      final Map<String, Integer> versionByUri,
      final CancelChecker cancelChecker) {
    final var t = Stopwatch.start();
    final List<CompilerResult> runs = compiler.analyzeOpenBatch(sources, cancelChecker);
    cancelChecker.checkCanceled();
    final var diagnostics = new HashMap<String, List<Diagnostic>>();
    for (int i = 0; i < sources.size(); i++) {
      final TransientSource source = sources.get(i);
      final CompilerResult run = runs.get(i);
      cache.put(
          source.uri(),
          new CachedFileAnalysis(
              source.content(), versionByUri.get(source.uri()), run.fileAnalysis()));
      recordParse(source.uri(), source.content(), run);
      diagnostics.put(source.uri(), mapDiagnostics(run, source.content()));
    }

    LOG.info(
        () ->
            "[compile:open-batch] %d files from %s %dms"
                .formatted(sources.size(), firstUri(sources), t.elapsedMs()));
    return Map.copyOf(diagnostics);
  }

  // Where a batch ran, for its log line: the line has room for one file, not all of them.
  private static String firstUri(final List<TransientSource> sources) {
    return sources.isEmpty() ? "nothing" : sources.get(0).uri();
  }

  private static List<Diagnostic> mapDiagnostics(final CompilerResult run, final String content) {
    final List<Diagnostic> compiled =
        JavacDiagnosticMapper.filterAndMap(run.diagnostics(), content);
    JavacDiagnosticMapper.enrich(compiled, run.fileAnalysis());
    final boolean compileFailed =
        compiled.stream().anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);
    final List<Diagnostic> unusedDiags =
        compileFailed ? List.of() : UnusedDeclarationScanner.scan(run.fileAnalysis(), content);
    return unusedDiags.isEmpty()
        ? compiled
        : Stream.concat(compiled.stream(), unusedDiags.stream()).toList();
  }

  public CompletionOutcome complete(
      final String uri,
      final String content,
      final int version,
      final Position pos,
      final CompletionContext context,
      final WorkspaceTypeIndex typeIndex,
      final List<String> moduleNames,
      final LearnedStaticImports staticImports) {
    final var t = Stopwatch.start();
    final var request =
        new CompletionRequest(
            uri, content, pos, context, cache.get(uri), typeIndex, moduleNames, staticImports);
    final var outcome = completionEngine.complete(request);
    if (outcome.freshAnalysis() != null) {
      cache.put(uri, new CachedFileAnalysis(content, version, outcome.freshAnalysis()));
    }

    LOG.fine(
        () ->
            "[completion] %s %dms items=%d reattributed=%s"
                .formatted(
                    uri, t.elapsedMs(), outcome.items().size(), outcome.freshAnalysis() != null));
    return outcome;
  }

  /**
   * Completion over transient content that neither reads nor writes the open-document cache — the
   * debug console splices REPL text into a frame's source ({@code content}) and completes there, so
   * the synthetic buffer must never displace the real file's cached analysis. {@code baseline} is
   * the unmodified file: it is attributed fresh (FAST, uncached) and supplied as the completion's
   * prior analysis, which the semantic completer needs to resolve the frame's locals, parameters,
   * and members — without it only keywords and type-index candidates surface.
   */
  public CompletionOutcome completeTransient(
      final String uri,
      final String baseline,
      final String content,
      final Position pos,
      final CompletionContext context,
      final WorkspaceTypeIndex typeIndex,
      final List<String> moduleNames) {
    final var t = Stopwatch.start();
    final AttributedFileAnalysis analysis =
        compiler.compile(uri, baseline, CompileMode.FAST).fileAnalysis();
    final CachedFileAnalysis prior =
        analysis != null ? new CachedFileAnalysis(baseline, -1, analysis) : null;
    final var request =
        new CompletionRequest(
            uri,
            content,
            pos,
            context,
            prior,
            typeIndex,
            moduleNames,
            LearnedStaticImports.empty());
    final var outcome = completionEngine.complete(request);
    LOG.fine(
        () ->
            "[completion:transient] %s %dms items=%d"
                .formatted(uri, t.elapsedMs(), outcome.items().size()));
    return outcome;
  }

  private void recordParse(final String uri, final String content, final CompilerResult run) {
    if (run.parsedCleanly()) {
      cleanParses.put(uri, content);
    } else {
      cleanParses.remove(uri);
    }
  }

  public boolean parsedCleanly(final String uri, final String content) {
    return content.equals(cleanParses.get(uri));
  }

  public void dropFromCache(final String uri) {
    cleanParses.remove(uri);
    if (cache.remove(uri) != null) {
      LOG.fine(() -> "[evict] %s dropped".formatted(uri));
    }
  }

  public List<TypeIndexEntry> cachedTypeEntries(final Set<String> neededSimpleNames) {
    if (neededSimpleNames.isEmpty()) {
      return List.of();
    }

    return cache.values().stream()
        .filter(cached -> cached.analysis().tree() != null)
        .flatMap(cached -> typeEntriesForFile(cached.analysis().tree(), neededSimpleNames))
        .toList();
  }

  private static Stream<TypeIndexEntry> typeEntriesForFile(
      final com.sun.source.tree.CompilationUnitTree tree, final Set<String> neededSimpleNames) {
    final String pkg = tree.getPackageName() != null ? tree.getPackageName().toString() : "";
    return tree.getTypeDecls().stream()
        .filter(ClassTree.class::isInstance)
        .map(ClassTree.class::cast)
        .filter(ct -> neededSimpleNames.contains(ct.getSimpleName().toString()))
        .map(ct -> typeEntry(ct.getSimpleName().toString(), pkg));
  }

  private static TypeIndexEntry typeEntry(final String simpleName, final String pkg) {
    final String fqName = pkg.isEmpty() ? simpleName : "%s.%s".formatted(pkg, simpleName);
    return new TypeIndexEntry(simpleName, fqName, pkg, TypeKind.CLASS, true, List.of());
  }

  // The open file's attributed analysis: the cached one when it has this content, else a fresh
  // compile, cached. Null when the file does not attribute.
  public AttributedFileAnalysis analysis(
      final String uri,
      final String content,
      final int version,
      final CancelChecker cancelChecker) {
    return cachedOrCompiled(uri, content, version, false, cancelChecker);
  }

  // The open file's attributed analysis for exactly this version: a cached analysis of the same
  // text under another version is recompiled, so a version-keyed answer (semantic tokens,
  // runnables) is for the version the client asked about. Null when the file does not attribute.
  public AttributedFileAnalysis analysisAtVersion(
      final String uri, final String content, final int expectedVersion) {
    return cachedOrCompiled(uri, content, expectedVersion, true, () -> {});
  }

  // The cached analysis when it has this content (and, when versionExact, this version) and a
  // tree; otherwise a fresh compile, cached.
  private AttributedFileAnalysis cachedOrCompiled(
      final String uri,
      final String content,
      final int version,
      final boolean versionExact,
      final CancelChecker cancelChecker) {
    final CachedFileAnalysis current = cache.get(uri);
    final boolean usable =
        current != null
            && current.content().equals(content)
            && (!versionExact || current.version() == version)
            && current.analysis().tree() != null;
    if (!usable) {
      compile(uri, content, version, CompileMode.OPEN, cancelChecker);
    }

    cancelChecker.checkCanceled();
    final CachedFileAnalysis cached = cache.get(uri);
    return cached != null ? cached.analysis() : null;
  }

  // The open file's cached analysis, whatever content it was compiled from; empty when none is
  // cached. Never compiles.
  public Optional<AttributedFileAnalysis> cachedAnalysis(final String uri) {
    final CachedFileAnalysis cached = cache.get(uri);
    return cached != null ? Optional.ofNullable(cached.analysis()) : Optional.empty();
  }

  // A file attributed once, uncached: a file that is not open, or content that must not displace
  // the open file's cached analysis.
  public AttributedFileAnalysis transientAnalysis(
      final String uri, final String content, final CancelChecker cancelChecker) {
    final var t = Stopwatch.start();
    final CompilerResult run = compiler.compile(uri, content, CompileMode.FAST, cancelChecker);
    LOG.info(
        () ->
            "[compile:fast] %s %dms diags=%d"
                .formatted(uri, t.elapsedMs(), run.diagnostics().size()));
    return run.fileAnalysis();
  }

  // Many files attributed together, uncached.
  public List<TransientAnalysis> transientAnalyses(
      final List<TransientSource> sources, final CancelChecker cancelChecker) {
    final var t = Stopwatch.start();
    final List<TransientAnalysis> analyses = compiler.analyzeBatch(sources, cancelChecker);
    LOG.fine(() -> "[analyze:batch] size=%d %dms".formatted(sources.size(), t.elapsedMs()));
    return analyses;
  }

  public void close() {
    cache.clear();
    parser.close();
    compiler.close();
  }

  // The cursor's position in the request's file, attributed (the cached analysis when current);
  // empty when the file has no tree.
  public Optional<Cursor> cursor(final SourceFeatureRequest request) {
    final var analysis = analysis(request.uri(), request.content(), request.version(), () -> {});
    if (analysis == null || analysis.tree() == null) {
      return Optional.empty();
    }

    final long offset =
        Positions.toOffset(analysis.tree(), request.pos().getLine(), request.pos().getCharacter());
    return Optional.of(
        new Cursor(
            analysis, CursorElements.pathAt(analysis.trees(), analysis.tree(), offset), offset));
  }
}
