package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.core.Stopwatch;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// What is asked about a whole open file: its symbols, folds, semantic tokens and runnable targets,
// its code actions and missing imports, the class enclosing a line, and a debugger expression
// attributed in it. Reads the session's analysis; keeps no state.
public final class FileFeatures {

  private static final Logger LOG = Logger.getLogger(FileFeatures.class.getName());

  private final SourceAnalysisSession session;
  private final JavaSourceCompiler compiler;
  private final SourceParser parser;

  FileFeatures(
      final SourceAnalysisSession session,
      final JavaSourceCompiler compiler,
      final SourceParser parser) {
    this.session = session;
    this.compiler = compiler;
    this.parser = parser;
  }

  public List<DocumentSymbol> documentSymbol(final String uri, final String content) {
    final var t = Stopwatch.start();
    final var symbols =
        parser
            .parseContent(
                uri, content, (trees, tree) -> DocumentSymbolScanner.scan(trees, tree, content))
            .orElseGet(List::of);
    LOG.fine(
        () -> "[documentSymbol] %s %dms symbols=%d".formatted(uri, t.elapsedMs(), symbols.size()));
    return symbols;
  }

  public List<RunTarget> runnables(
      final String uri, final String content, final int expectedVersion, final String moduleRel) {
    final var t = Stopwatch.start();
    final AttributedFileAnalysis analysis =
        session.analysisAtVersion(uri, content, expectedVersion);
    if (analysis == null || analysis.tree() == null) {
      return List.of();
    }

    final var runnables = RunnableScanner.scan(analysis, uri, moduleRel);
    LOG.fine(
        () -> "[runnables] %s %dms targets=%d".formatted(uri, t.elapsedMs(), runnables.size()));
    return runnables;
  }

  /**
   * The binary name (e.g. {@code com.example.Outer$Inner}) of the class enclosing a 1-based line,
   * from the already-attributed analysis of an open file — the mapping the debug adapter's
   * source-lookup provider needs to arm a JDI breakpoint. Empty when the file is not
   * open/attributed or the line is outside any class.
   */
  public Optional<String> enclosingBinaryName(final String uri, final int oneBasedLine) {
    final Optional<AttributedFileAnalysis> cached = session.cachedAnalysis(uri);
    if (cached.isEmpty() || cached.get().tree() == null) {
      return Optional.empty();
    }

    final AttributedFileAnalysis analysis = cached.get();
    final CompilationUnitTree cu = analysis.tree();
    final long offset = Positions.toOffset(cu, oneBasedLine - 1, 0);
    if (offset == javax.tools.Diagnostic.NOPOS) {
      return Optional.empty();
    }

    for (TreePath p = CursorElements.pathAt(analysis.trees(), cu, offset);
        p != null;
        p = p.getParentPath()) {
      if (p.getLeaf() instanceof ClassTree) {
        final Element element = analysis.trees().getElement(p);
        if (element instanceof final TypeElement type) {
          return Optional.of(analysis.elements().getBinaryName(type).toString());
        }
      }
    }

    return Optional.empty();
  }

  /**
   * Attributes a debugger expression in the scope of a suspended frame (Stage 1 of expression
   * evaluation): splices {@code var __LATHE_EVAL__ = (expression);} into {@code content} at {@code
   * oneBasedLine}, attributes the whole unit, and lifts the attributed initializer subtree. Uses a
   * transient FAST compile that does not touch the open-document cache. Empty when the unit cannot
   * be attributed or the spliced statement did not land in a resolvable scope.
   */
  public Optional<AttributedExpression> attributeExpression(
      final String uri, final String content, final int oneBasedLine, final String expression) {
    final var t = Stopwatch.start();
    final String spliced = ExpressionSplice.at(content, oneBasedLine, expression);
    final AttributedFileAnalysis analysis =
        compiler.compile(uri, spliced, CompileMode.FAST).fileAnalysis();
    final Optional<AttributedExpression> result =
        ExpressionSplice.locate(analysis)
            .map(
                path ->
                    new AttributedExpression(
                        path, analysis.trees(), analysis.elements(), analysis.types()));
    LOG.fine(
        () ->
            "[eval:attribute] %s line=%d %dms resolved=%s"
                .formatted(uri, oneBasedLine, t.elapsedMs(), result.isPresent()));
    return result;
  }

  /**
   * Attributes an expression evaluated against an object of {@code receiverType} rather than a
   * stack frame (the debugger's object-scoped evaluation, used by logical collection/map views):
   * the expression is spliced as {@code receiver.EXPR} inside a synthetic static method that takes
   * {@code receiver} of that type, so javac resolves the members against the object's type. The
   * interpreter then binds {@code receiver} to the actual object. {@code receiverType} is a source
   * type name (nested types written with {@code .}).
   */
  public Optional<AttributedExpression> attributeReceiverExpression(
      final String uri, final String receiverType, final String receiver, final String expression) {
    final var t = Stopwatch.start();
    final String content =
        "final class __LATHE_OBJ__ { static void __e(%s %s) { var %s = (%s.%s); } }"
            .formatted(receiverType, receiver, ExpressionSplice.SENTINEL, receiver, expression);
    final AttributedFileAnalysis analysis =
        compiler.compile(uri, content, CompileMode.FAST).fileAnalysis();
    final Optional<AttributedExpression> result =
        ExpressionSplice.locate(analysis)
            .map(
                path ->
                    new AttributedExpression(
                        path, analysis.trees(), analysis.elements(), analysis.types()));
    LOG.fine(
        () ->
            "[eval:attribute-receiver] %s type=%s %dms resolved=%s"
                .formatted(uri, receiverType, t.elapsedMs(), result.isPresent()));
    return result;
  }

  public List<FoldingRange> foldingRange(final String uri, final String content) {
    final var t = Stopwatch.start();
    final var ranges =
        parser.parseContent(uri, content, FoldingRangeScanner::scan).orElseGet(List::of);
    LOG.fine(() -> "[foldingRange] %s %dms ranges=%d".formatted(uri, t.elapsedMs(), ranges.size()));
    return ranges;
  }

  public List<SemanticToken> semanticTokens(
      final String uri, final String content, final int expectedVersion) {
    final AttributedFileAnalysis analysis =
        session.analysisAtVersion(uri, content, expectedVersion);
    return analysis != null ? analysis.semanticTokens() : null;
  }

  public List<Either<Command, CodeAction>> codeAction(
      final String uri,
      final String content,
      final int version,
      final Range range,
      final List<CodeActionRequest> requests,
      final WorkspaceTypeIndex typeIndex,
      final LearnedStaticImports staticImports) {
    final var t = Stopwatch.start();
    LOG.fine(() -> "[codeAction] %s diags=%d".formatted(uri, requests.size()));

    final var analysis = session.analysis(uri, content, version, () -> {});
    if (analysis == null) {
      return List.of();
    }

    final var importProvider = new ImportQuickFixProvider();
    final var addThrowsProvider = new AddThrowsProvider();
    final var tryCatchProvider = new TryCatchWrapProvider();
    final var declareProvider = new DeclareVariableProvider();
    final var missingImplProvider = new MissingMethodImplProvider();
    final var seen = new HashSet<String>();
    final var actions = new ArrayList<Either<Command, CodeAction>>();

    for (final CodeActionRequest request : requests) {
      final DiagnosticPayload payload = request.payload();
      LOG.fine(() -> "[codeAction:diag] kind=%s name=%s".formatted(payload.kind(), payload.name()));

      final List<Either<Command, CodeAction>> provided =
          switch (payload.kind()) {
            case TYPE_REF -> importProvider.provide(request, analysis, typeIndex);
            case UNREPORTED_EXCEPTION ->
                Stream.concat(
                        addThrowsProvider.provide(request, analysis, typeIndex).stream(),
                        tryCatchProvider.provide(request, analysis, typeIndex).stream())
                    .toList();
            case VARIABLE_REF ->
                Stream.concat(
                        ImportQuickFixProvider.staticImportActions(request, analysis, staticImports)
                            .stream(),
                        declareProvider.provide(request, analysis, typeIndex).stream())
                    .toList();
            case QUALIFIER_REF ->
                Stream.concat(
                        importProvider.provide(request, analysis, typeIndex).stream(),
                        ImportQuickFixProvider.staticImportActions(request, analysis, staticImports)
                            .stream())
                    .toList();
            case MISSING_METHOD_IMPL -> missingImplProvider.provide(request, analysis, typeIndex);
          };

      addUnique(actions, seen, provided);
    }

    if (requests.stream().anyMatch(r -> r.payload().kind().offersTypeImport())) {
      actions.add(
          Either.forLeft(
              new Command(
                  "Add missing imports…", "lathe.missingImports", List.of(Map.of("uri", uri)))));
    }

    addUnique(actions, seen, new ReplaceVarProvider().provide(uri, range, analysis));
    addUnique(actions, seen, new ConvertToVarProvider().provide(uri, range, analysis));
    addUnique(actions, seen, new ExtractVariableProvider().provide(uri, range, analysis));
    addUnique(actions, seen, new ExtractConstantProvider().provide(uri, range, analysis));
    addUnique(actions, seen, new ExtractFieldProvider().provide(uri, range, analysis));
    addUnique(actions, seen, new ExtractMethodProvider().provide(uri, range, analysis));
    addUnique(actions, seen, new TryWithResourcesProvider().provide(uri, range, analysis));
    addUnique(actions, seen, new AddConstructorParameterProvider().provide(uri, range, analysis));

    LOG.fine(() -> "[codeAction] %s %dms actions=%d".formatted(uri, t.elapsedMs(), actions.size()));
    return actions;
  }

  public MissingImportsResult missingImports(
      final String uri,
      final String content,
      final int version,
      final WorkspaceTypeIndex typeIndex,
      final LearnedStaticImports staticImports) {
    final var t = Stopwatch.start();
    // Compile first: it yields the unresolved-type diagnostics and refreshes the analysis cache, so
    // the subsequent analysis() is a cache hit (no second compile).
    final List<Diagnostic> diags = session.compile(uri, content, version, CompileMode.OPEN);
    final var analysis = session.analysis(uri, content, version, () -> {});
    if (analysis == null || analysis.tree() == null) {
      return MissingImportsResult.empty();
    }

    final var importAnalyzer = new ImportAnalyzer(analysis);
    final Optional<ImportLayout> layout = importAnalyzer.layout();
    if (layout.isEmpty()) {
      return MissingImportsResult.empty();
    }

    final Set<String> alreadyImported = importAnalyzer.importedQualifiedNames();
    final Map<String, Position> typeNames =
        unresolvedNames(diags, DiagnosticPayload.Kind::offersTypeImport);
    final Map<String, Position> memberNames =
        unresolvedNames(diags, DiagnosticPayload.Kind::offersStaticImport);
    final Set<String> plainTypeNames =
        unresolvedNames(diags, kind -> kind == DiagnosticPayload.Kind.TYPE_REF).keySet();
    // A qualifier (Objects in Objects.requireNonNull) may be either, so it gets both kinds of
    // candidate. A name with none is listed only as a plain type, to say nothing matched it; any
    // other such name is a typo, not an import to add.
    final List<MissingImportsResult.MissingImport> items =
        Stream.concat(typeNames.keySet().stream(), memberNames.keySet().stream())
            .distinct()
            .map(
                name -> {
                  final List<String> types =
                      typeNames.containsKey(name)
                          ? ImportCandidates.resolve(
                              name, typeNames.get(name), analysis, typeIndex, alreadyImported)
                          : List.of();
                  final List<String> statics =
                      memberNames.containsKey(name)
                          ? StaticImportCandidates.importSpellings(staticImports, analysis, name)
                          : List.of();
                  return new MissingImportsResult.MissingImport(
                      name, Stream.concat(types.stream(), statics.stream()).toList());
                })
            .filter(item -> !item.candidates().isEmpty() || plainTypeNames.contains(item.name()))
            .toList();

    LOG.fine(() -> "[missingImports] %s %dms names=%d".formatted(uri, t.elapsedMs(), items.size()));
    return new MissingImportsResult(layout.get(), items);
  }

  // Distinct unresolved names of the matching kinds in encounter order, each mapped to the first
  // diagnostic position that reported it (used to compute the accessibility scope for candidate
  // resolution).
  private static Map<String, Position> unresolvedNames(
      final List<Diagnostic> diags, final Predicate<DiagnosticPayload.Kind> kinds) {
    return diags.stream()
        .filter(d -> d.getData() instanceof DiagnosticPayload p && kinds.test(p.kind()))
        .collect(
            Collectors.toMap(
                d -> ((DiagnosticPayload) d.getData()).name(),
                d -> d.getRange().getStart(),
                (first, later) -> first,
                LinkedHashMap::new));
  }

  private static void addUnique(
      final List<Either<Command, CodeAction>> actions,
      final HashSet<String> seen,
      final List<Either<Command, CodeAction>> provided) {
    for (final var action : provided) {
      if (action.isRight() && seen.add(action.getRight().getTitle())) {
        actions.add(action);
      }
    }
  }
}
