package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.analysis.completion.CompletionEngine;
import io.github.aglibs.lathe.server.analysis.completion.CompletionOutcome;
import io.github.aglibs.lathe.server.analysis.completion.CompletionRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaFileObject;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public final class SourceAnalysisSession implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(SourceAnalysisSession.class.getName());

  private final JavaSourceCompiler compiler;
  private final CompletionEngine completionEngine;
  private final DefinitionLocator definitionLocator;
  private final JavadocLocator javadocLocator;
  private final Map<String, CachedFileAnalysis> cache = new HashMap<>();
  private final SourceParser parser;

  public SourceAnalysisSession(final JavaSourceCompiler compiler) {
    this.compiler = compiler;
    this.parser = new SourceParser();
    this.completionEngine = new CompletionEngine(parser, compiler);
    this.definitionLocator = new DefinitionLocator(parser);
    this.javadocLocator = new JavadocLocator(parser);
  }

  public List<Diagnostic> compile(
      final String uri, final String content, final int version, final CompileMode mode) {
    return compile(uri, content, version, mode, () -> {});
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
    if (mode != CompileMode.FULL) {
      cache.put(uri, new CachedFileAnalysis(content, version, run.fileAnalysis()));
    }

    final var compiled = filterAndMap(run.diagnostics(), content);
    enrichWithContext(compiled, run.fileAnalysis());
    final boolean compileFailed =
        compiled.stream().anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);
    final List<Diagnostic> unusedDiags =
        compileFailed ? List.of() : UnusedDeclarationScanner.scan(run.fileAnalysis(), content);
    final List<Diagnostic> diags =
        unusedDiags.isEmpty()
            ? compiled
            : Stream.concat(compiled.stream(), unusedDiags.stream()).toList();
    LOG.info(
        () ->
            "[compile:%s] %s %dms diags=%d".formatted(mode.tag, uri, t.elapsedMs(), diags.size()));
    return diags;
  }

  public CompletionOutcome complete(
      final String uri,
      final String content,
      final int version,
      final Position pos,
      final CompletionContext context,
      final WorkspaceTypeIndex typeIndex) {
    final var t = Stopwatch.start();
    final var request =
        new CompletionRequest(uri, content, pos, context, cache.get(uri), typeIndex);
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

  public void dropFromCache(final String uri) {
    cache.remove(uri);
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

  public List<FoldingRange> foldingRange(final String uri, final String content) {
    final var t = Stopwatch.start();
    final var ranges =
        parser.parseContent(uri, content, FoldingRangeScanner::scan).orElseGet(List::of);
    LOG.fine(() -> "[foldingRange] %s %dms ranges=%d".formatted(uri, t.elapsedMs(), ranges.size()));
    return ranges;
  }

  public List<SemanticToken> semanticTokens(final String uri, final int expectedVersion) {
    final CachedFileAnalysis ctx = cache.get(uri);
    if (ctx == null || ctx.version() != expectedVersion) {
      return null;
    }

    return ctx.analysis().semanticTokens();
  }

  public SignatureHelp signatureHelp(final SourceFeatureRequest request) {
    final var cur = resolve(request);
    if (cur == null) {
      return null;
    }

    final long offset =
        SourceLocator.toOffset(
            cur.analysis().tree(), request.pos().getLine(), request.pos().getCharacter());
    return SignatureHelpResolver.resolve(
        cur.analysis(), cur.path(), offset, parser, allRoots(request), javadocLocator);
  }

  public Hover hover(final SourceFeatureRequest request) {
    final var t = Stopwatch.start();
    final CursorContext cur = resolve(request);
    if (cur == null) {
      return null;
    }

    final VariableElement param =
        SourceLocator.parameterElementAt(cur.analysis().trees(), cur.path());
    if (param != null) {
      LOG.fine(() -> "[hover] param=%s %dms".formatted(param, t.elapsedMs()));
      return new Hover(new MarkupContent("markdown", HoverFormatter.formatParameter(param)));
    }

    final Element element = SourceLocator.elementAt(cur.analysis().trees(), cur.path());
    final TypeMirror type =
        cur.path() != null ? cur.analysis().trees().getTypeMirror(cur.path()) : null;
    final List<Path> allRoots = allRoots(request);
    final var javadoc =
        javadocLocator
            .locate(element, cur.analysis().trees(), allRoots)
            .map(JavadocMarkdownPrinter::format)
            .orElse(null);
    final var origin = request.manifest().originLabel(element, compiler.fileManager()).orElse(null);
    final var fmt = new TypeDisplayFormatter(cur.analysis().types());
    final List<String> sourceParamNames =
        element instanceof ExecutableElement exe ? parser.resolveParamNames(exe, allRoots) : null;
    LOG.fine(
        () ->
            "[hover] %dms element=%s type=%s doc=%s origin=%s"
                .formatted(t.elapsedMs(), element, type, javadoc != null, origin));
    return HoverFormatter.format(element, type, javadoc, origin, fmt, sourceParamNames)
        .map(md -> new Hover(new MarkupContent("markdown", md)))
        .orElse(null);
  }

  private static List<Path> allRoots(final SourceFeatureRequest request) {
    return Stream.concat(
            request.sourceRoots().stream(), request.manifest().externalSourceDirs().stream())
        .toList();
  }

  public ReferenceTarget resolveTarget(final SourceFeatureRequest request) {
    final var cur = resolve(request);
    if (cur == null) {
      return null;
    }

    final var element = SourceLocator.elementAt(cur.analysis().trees(), cur.path());
    if (element == null) {
      return null;
    }

    return ReferenceTarget.from(element, cur.analysis().types(), cur.analysis().elements());
  }

  public List<ReferenceMatch> searchReferences(
      final String uri,
      final String content,
      final int version,
      final ReferenceTarget target,
      final boolean includeDeclaration) {
    return searchReferences(uri, content, version, target, includeDeclaration, () -> {});
  }

  public List<ReferenceMatch> searchReferences(
      final String uri,
      final String content,
      final int version,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final CancelChecker cancelChecker) {
    final AttributedFileAnalysis analysis =
        ensureAttributedAnalysis(uri, content, version, cancelChecker);
    cancelChecker.checkCanceled();
    return locateReferences(uri, target, includeDeclaration, analysis);
  }

  public List<ReferenceMatch> searchReferencesTransient(
      final String uri,
      final String content,
      final ReferenceTarget target,
      final boolean includeDeclaration) {
    return searchReferencesTransient(uri, content, target, includeDeclaration, () -> {});
  }

  public List<ReferenceMatch> searchReferencesTransient(
      final String uri,
      final String content,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final CancelChecker cancelChecker) {
    final var t = Stopwatch.start();
    final CompilerResult run = compiler.compile(uri, content, CompileMode.FAST, cancelChecker);
    cancelChecker.checkCanceled();
    LOG.info(
        () ->
            "[compile:fast] %s %dms diags=%d"
                .formatted(uri, t.elapsedMs(), run.diagnostics().size()));
    return locateReferences(uri, target, includeDeclaration, run.fileAnalysis());
  }

  private List<ReferenceMatch> locateReferences(
      final String uri,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final AttributedFileAnalysis analysis) {
    if (analysis == null || analysis.tree() == null) {
      return List.of();
    }

    try {
      final List<ReferenceMatch> results =
          ReferenceLocator.references(analysis, target, uri, includeDeclaration);
      LOG.fine(
          () ->
              "[references] uri=%s element=%s hits=%d"
                  .formatted(uri, target.simpleName(), results.size()));
      return results;
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[references] failed to read source for %s".formatted(uri));
      return List.of();
    }
  }

  public List<Location> methodImplementations(
      final String uri,
      final String content,
      final int version,
      final ReferenceTarget target,
      final Set<String> candidateBinaryNames) {
    final var analysis = ensureAttributedAnalysis(uri, content, version);
    return analysis != null
        ? MethodImplementationLocator.locate(analysis, target, candidateBinaryNames, uri)
        : List.of();
  }

  public Optional<Location> definition(final SourceFeatureRequest request) {
    final var t = Stopwatch.start();
    final var cur = resolve(request);
    if (cur == null) {
      return Optional.empty();
    }

    final var element = SourceLocator.elementAt(cur.analysis().trees(), cur.path());
    Optional<Location> result =
        definitionLocator.locate(
            element, cur.analysis().trees(), request.sourceRoots(), request.uri());
    if (result.isEmpty()) {
      result =
          request
              .manifest()
              .externalSourceRoot(element, compiler.fileManager())
              .flatMap(root -> DefinitionLocator.findSourceFile(element, List.of(root)))
              .map(
                  file -> {
                    final var lspPos = definitionLocator.parsePosition(file, element);
                    return new Location(file.toUri().toString(), new Range(lspPos, lspPos));
                  });
    }

    final var finalResult = result;
    LOG.fine(
        () ->
            "[definition] %dms element=%s → %s"
                .formatted(
                    t.elapsedMs(),
                    element,
                    finalResult
                        .map(l -> "%s:%d".formatted(l.getUri(), l.getRange().getStart().getLine()))
                        .orElse("not found")));
    return result;
  }

  public List<Location> typeImplementations(
      final SourceFeatureRequest request, final WorkspaceTypeIndex typeIndex) {
    final var t = Stopwatch.start();
    final var cur = resolve(request);
    if (cur == null) {
      return List.of();
    }

    final var element = SourceLocator.elementAt(cur.analysis().trees(), cur.path());
    if (!(element instanceof final TypeElement typeElement)) {
      return List.of();
    }

    final var binaryName = cur.analysis().elements().getBinaryName(typeElement).toString();
    final List<Path> sourceRoots = typeSourceRoots(request);
    final List<Location> results =
        typeIndex.transitiveSubtypes(binaryName).stream()
            .flatMap(entry -> TypeSourceLocator.locate(entry, sourceRoots, parser).stream())
            .toList();
    LOG.fine(
        () ->
            "[implementation:type] %s %dms hits=%d"
                .formatted(request.uri(), t.elapsedMs(), results.size()));
    return results;
  }

  public List<TypeHierarchyItem> prepareTypeHierarchy(
      final SourceFeatureRequest request, final WorkspaceTypeIndex typeIndex) {
    final var cur = resolve(request);
    if (cur == null) {
      return List.of();
    }

    final var element = SourceLocator.elementAt(cur.analysis().trees(), cur.path());
    if (!(element instanceof final TypeElement typeElement)) {
      return List.of();
    }

    final var binaryName = cur.analysis().elements().getBinaryName(typeElement).toString();
    return typeIndex
        .findType(binaryName)
        .flatMap(entry -> typeHierarchyItem(entry, typeSourceRoots(request)))
        .map(List::of)
        .orElseGet(List::of);
  }

  public List<CallHierarchyItem> prepareCallHierarchy(final SourceFeatureRequest request) {
    final var cur = resolve(request);
    if (cur == null) {
      return List.of();
    }

    final var element = SourceLocator.elementAt(cur.analysis().trees(), cur.path());
    if (element == null) {
      return List.of();
    }

    final var kind = element.getKind();
    if (kind != ElementKind.METHOD && kind != ElementKind.CONSTRUCTOR) {
      return List.of();
    }

    final var ee = (ExecutableElement) element;
    final var owner = (TypeElement) ee.getEnclosingElement();
    final var target =
        ReferenceTarget.from(element, cur.analysis().types(), cur.analysis().elements());
    final String displayName = SourceLocator.declarationName(element).toString();
    final String ownerBinaryName = cur.analysis().elements().getBinaryName(owner).toString();
    final var symbolKind =
        kind == ElementKind.CONSTRUCTOR ? SymbolKind.Constructor : SymbolKind.Function;

    final TreePath methodPath = cur.analysis().trees().getPath(element);
    if (methodPath != null) {
      final var cu = cur.analysis().tree();
      final var positions = cur.analysis().trees().getSourcePositions();
      final long startOff = positions.getStartPosition(cu, methodPath.getLeaf());
      final long endOff = positions.getEndPosition(cu, methodPath.getLeaf());
      final var rangeStart = SourceLocator.offsetToPosition(cu, startOff);
      final var rangeEnd = SourceLocator.offsetToPosition(cu, endOff);
      final var range = new Range(rangeStart, rangeEnd);
      Position selStart;
      try {
        selStart =
            SourceLocator.declarationNamePosition(
                    cur.analysis().trees(), cu, methodPath, displayName)
                .orElse(rangeStart);
      } catch (final IOException e) {
        selStart = rangeStart;
      }
      final var selEnd =
          new Position(selStart.getLine(), selStart.getCharacter() + displayName.length());
      final var selectionRange = new Range(selStart, selEnd);
      final var item =
          new CallHierarchyItem(displayName, symbolKind, request.uri(), range, selectionRange);
      item.setDetail(ownerBinaryName);
      item.setData(
          CallHierarchyItemDataCodec.encode(
              new CallHierarchyItemData(
                  ownerBinaryName,
                  target.simpleName(),
                  target.erasedDescriptor(),
                  kind,
                  request.uri(),
                  target.scope())));
      return List.of(item);
    }

    return DefinitionLocator.findSourceFile(element, allRoots(request))
        .map(
            file -> {
              final var pos = definitionLocator.parsePosition(file, element);
              final var pointRange = new Range(pos, pos);
              final var uri = file.toUri().toString();
              final var item =
                  new CallHierarchyItem(displayName, symbolKind, uri, pointRange, pointRange);
              item.setDetail(ownerBinaryName);
              item.setData(
                  CallHierarchyItemDataCodec.encode(
                      new CallHierarchyItemData(
                          ownerBinaryName,
                          target.simpleName(),
                          target.erasedDescriptor(),
                          kind,
                          uri,
                          target.scope())));
              return item;
            })
        .map(List::of)
        .orElseGet(List::of);
  }

  public List<TypeHierarchyItem> typeHierarchySupertypes(
      final TypeHierarchyItem item,
      final WorkspaceTypeIndex typeIndex,
      final List<Path> sourceRoots) {
    final TypeHierarchyItemData data = TypeHierarchyItemDataCodec.decode(item.getData());
    return data != null
        ? typeHierarchyItems(typeIndex.directSupertypes(data.binaryName()), sourceRoots)
        : List.of();
  }

  public List<TypeHierarchyItem> typeHierarchySubtypes(
      final TypeHierarchyItem item,
      final WorkspaceTypeIndex typeIndex,
      final List<Path> sourceRoots) {
    final TypeHierarchyItemData data = TypeHierarchyItemDataCodec.decode(item.getData());
    if (data == null) {
      return List.of();
    }

    final List<TypeIndexEntry> subtypes = typeIndex.directSubtypes(data.binaryName());
    return typeHierarchyItems(subtypes, sourceRoots);
  }

  private List<TypeHierarchyItem> typeHierarchyItems(
      final List<TypeIndexEntry> entries, final List<Path> sourceRoots) {
    return entries.stream()
        .flatMap(entry -> typeHierarchyItem(entry, sourceRoots).stream())
        .toList();
  }

  private Optional<TypeHierarchyItem> typeHierarchyItem(
      final TypeIndexEntry entry, final List<Path> sourceRoots) {
    return TypeSourceLocator.locate(entry, sourceRoots, parser)
        .map(
            location -> {
              final var item =
                  new TypeHierarchyItem(
                      entry.simpleName(),
                      WorkspaceSymbolResolver.toSymbolKind(entry.kind()),
                      location.getUri(),
                      location.getRange(),
                      location.getRange());
              item.setDetail(entry.packageName());
              item.setData(
                  TypeHierarchyItemDataCodec.encode(
                      new TypeHierarchyItemData(entry.binaryName(), location.getUri())));
              return item;
            });
  }

  private static List<Path> typeSourceRoots(final SourceFeatureRequest request) {
    return Stream.of(
            request.sourceRoots().stream(),
            request.manifest().depSourceDirs().stream(),
            request.manifest().jdkModuleSourceDirs().stream())
        .flatMap(stream -> stream)
        .toList();
  }

  public List<Either<Command, CodeAction>> codeAction(
      final String uri,
      final String content,
      final int version,
      final List<CodeActionRequest> requests,
      final WorkspaceTypeIndex typeIndex) {
    final var t = Stopwatch.start();
    LOG.info(() -> "[codeAction] %s diags=%d".formatted(uri, requests.size()));

    final var analysis = ensureAttributedAnalysis(uri, content, version);
    if (analysis == null) {
      return List.of();
    }

    final var importProvider = new ImportQuickFixProvider();
    final var addThrowsProvider = new AddThrowsProvider();
    final var tryCatchProvider = new TryCatchWrapProvider();
    final var declareProvider = new DeclareVariableProvider();
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
            case VARIABLE_REF -> declareProvider.provide(request, analysis, typeIndex);
            case MISSING_METHOD_IMPL -> List.of();
          };

      for (final var action : provided) {
        if (action.isRight() && seen.add(action.getRight().getTitle())) {
          actions.add(action);
        }
      }
    }

    LOG.info(() -> "[codeAction] %s %dms actions=%d".formatted(uri, t.elapsedMs(), actions.size()));
    return actions;
  }

  private AttributedFileAnalysis ensureAttributedAnalysis(
      final String uri, final String content, final int version) {
    return ensureAttributedAnalysis(uri, content, version, () -> {});
  }

  private AttributedFileAnalysis ensureAttributedAnalysis(
      final String uri,
      final String content,
      final int version,
      final CancelChecker cancelChecker) {
    final var existing = currentCache(uri, content);
    if (existing == null || existing.analysis().tree() == null) {
      compile(uri, content, version, CompileMode.OPEN, cancelChecker);
    }

    cancelChecker.checkCanceled();
    final CachedFileAnalysis cached = cache.get(uri);
    return cached != null ? cached.analysis() : null;
  }

  private void enrichWithContext(
      final List<Diagnostic> diags, final AttributedFileAnalysis analysis) {
    for (final var diag : diags) {
      final Either<String, Integer> codeEither = diag.getCode();
      if (codeEither == null || !codeEither.isLeft()) {
        continue;
      }

      final String code = codeEither.getLeft();
      if (code.startsWith("compiler.err.cant.resolve") && diag.getData() instanceof String name) {
        diag.setData(new DiagnosticPayload(resolveKind(diag, analysis), name));
      } else if (code.startsWith("compiler.err.unreported.exception")) {
        final Either<String, MarkupContent> msgEither = diag.getMessage();
        final String name =
            extractExceptionName(
                msgEither != null && msgEither.isLeft() ? msgEither.getLeft() : null);
        if (name != null) {
          diag.setData(new DiagnosticPayload(DiagnosticPayload.Kind.UNREPORTED_EXCEPTION, name));
        }
      } else if (code.equals("compiler.err.does.not.override.abstract")) {
        final Either<String, MarkupContent> msgEither = diag.getMessage();
        final String name =
            extractMissingMethodClassName(
                diag,
                analysis,
                msgEither != null && msgEither.isLeft() ? msgEither.getLeft() : null);
        if (name != null) {
          diag.setData(new DiagnosticPayload(DiagnosticPayload.Kind.MISSING_METHOD_IMPL, name));
        }
      }
    }
  }

  private DiagnosticPayload.Kind resolveKind(
      final Diagnostic diag, final AttributedFileAnalysis analysis) {
    if (analysis.tree() == null) {
      return DiagnosticPayload.Kind.TYPE_REF;
    }

    final long offset =
        SourceLocator.toOffset(
            analysis.tree(),
            diag.getRange().getStart().getLine(),
            diag.getRange().getStart().getCharacter());
    final TreePath path = SourceLocator.pathAt(analysis.trees(), analysis.tree(), offset);
    if (path == null || path.getParentPath() == null) {
      return DiagnosticPayload.Kind.TYPE_REF;
    }

    return switch (path.getParentPath().getLeaf().getKind()) {
      case VARIABLE -> {
        final var varTree = (VariableTree) path.getParentPath().getLeaf();
        yield varTree.getType() == path.getLeaf()
            ? DiagnosticPayload.Kind.TYPE_REF
            : DiagnosticPayload.Kind.VARIABLE_REF;
      }
      case METHOD,
          CLASS,
          INTERFACE,
          ENUM,
          RECORD,
          ANNOTATION_TYPE,
          PARAMETERIZED_TYPE,
          ANNOTATED_TYPE,
          ARRAY_TYPE,
          TYPE_CAST,
          TYPE_PARAMETER,
          INTERSECTION_TYPE,
          UNION_TYPE,
          EXTENDS_WILDCARD,
          SUPER_WILDCARD,
          NEW_CLASS,
          INSTANCE_OF ->
          DiagnosticPayload.Kind.TYPE_REF;
      default -> DiagnosticPayload.Kind.VARIABLE_REF;
    };
  }

  private static String extractExceptionName(final String message) {
    if (message == null) {
      return null;
    }

    final var prefix = "unreported exception ";
    final int start = message.indexOf(prefix);
    if (start < 0) {
      return null;
    }

    final int nameStart = start + prefix.length();
    final int end = message.indexOf(';', nameStart);
    if (end < 0) {
      return null;
    }

    return message.substring(nameStart, end).trim();
  }

  private static String extractMissingMethodClassName(
      final Diagnostic diag, final AttributedFileAnalysis analysis, final String message) {
    final String astName = missingMethodClassNameFromAst(diag, analysis);
    if (astName != null) {
      return astName;
    }

    if (message == null) {
      return null;
    }

    final var suffix = " is not abstract and does not override abstract method ";
    final int end = message.indexOf(suffix);
    return end > 0 ? message.substring(0, end).trim() : null;
  }

  private static String missingMethodClassNameFromAst(
      final Diagnostic diag, final AttributedFileAnalysis analysis) {
    if (analysis.tree() == null) {
      return null;
    }

    final TreePath path =
        CodeActionSupport.pathAt(
            analysis,
            diag.getRange().getStart().getLine(),
            diag.getRange().getStart().getCharacter());
    return missingMethodClassNameFromPath(path);
  }

  private static String missingMethodClassNameFromPath(final TreePath path) {
    if (path == null) {
      return null;
    }

    if (path.getLeaf() instanceof ClassTree classTree) {
      return classTree.getSimpleName().toString();
    }

    return missingMethodClassNameFromPath(path.getParentPath());
  }

  public void close() {
    cache.clear();
    parser.close();
    compiler.close();
  }

  private record CursorContext(AttributedFileAnalysis analysis, TreePath path) {}

  private CachedFileAnalysis currentCache(final String uri, final String content) {
    final CachedFileAnalysis cached = cache.get(uri);
    return cached != null && cached.content().equals(content) ? cached : null;
  }

  private CursorContext resolve(final SourceFeatureRequest request) {
    final var cached = currentCache(request.uri(), request.content());
    final var analysis = cached != null ? cached.analysis() : null;
    if (analysis == null || analysis.tree() == null) {
      return null;
    }

    final long offset =
        SourceLocator.toOffset(
            analysis.tree(), request.pos().getLine(), request.pos().getCharacter());
    return new CursorContext(
        analysis, SourceLocator.pathAt(analysis.trees(), analysis.tree(), offset));
  }

  public static List<Diagnostic> filterAndMap(
      final List<? extends javax.tools.Diagnostic<? extends JavaFileObject>> raw,
      final String content) {
    final var seen = new HashSet<String>();
    return raw.stream()
        .filter(
            d ->
                d.getKind() != javax.tools.Diagnostic.Kind.NOTE
                    || d.getPosition() != javax.tools.Diagnostic.NOPOS)
        .map(d -> toLsp(d, content))
        .filter(d -> notDuplicate(d, seen))
        .toList();
  }

  private static boolean notDuplicate(final Diagnostic d, final Set<String> seen) {
    final Either<String, Integer> codeEither = d.getCode();
    if (codeEither == null || !codeEither.isLeft()) {
      return true;
    }

    final String code = codeEither.getLeft();
    if (!code.startsWith("compiler.err.cant.resolve") || !(d.getData() instanceof String name)) {
      return true;
    }

    return seen.add("%s|%s|%s".formatted(d.getRange().getStart().getLine(), code, name));
  }

  public static Diagnostic toLsp(
      final javax.tools.Diagnostic<? extends JavaFileObject> d, final String content) {
    final var severity =
        switch (d.getKind()) {
          case ERROR -> DiagnosticSeverity.Error;
          case WARNING, MANDATORY_WARNING -> DiagnosticSeverity.Warning;
          case NOTE -> DiagnosticSeverity.Hint;
          default -> DiagnosticSeverity.Information;
        };

    final long startOffset = d.getStartPosition();
    final long endOffset = d.getEndPosition();

    final Position start;
    final Position end;
    if (startOffset != javax.tools.Diagnostic.NOPOS) {
      start = SourceLocator.offsetToPosition(content, startOffset);
      end =
          (endOffset != javax.tools.Diagnostic.NOPOS && endOffset > startOffset)
              ? SourceLocator.offsetToPosition(content, endOffset)
              : new Position(start.getLine(), start.getCharacter() + 1);
    } else {
      final long rawLine = d.getLineNumber();
      final long rawCol = d.getColumnNumber();
      final int line = rawLine == javax.tools.Diagnostic.NOPOS ? 0 : (int) rawLine - 1;
      final int col = rawCol == javax.tools.Diagnostic.NOPOS ? 0 : (int) rawCol - 1;
      start = new Position(line, col);
      end = new Position(line, col + 1);
    }

    final var diagnostic =
        new Diagnostic(new Range(start, end), d.getMessage(Locale.ENGLISH), severity, "lathe");
    if (d.getCode() != null) {
      diagnostic.setCode(d.getCode());
      if (d.getCode().startsWith("compiler.err.cant.resolve")
          && startOffset != javax.tools.Diagnostic.NOPOS
          && endOffset > startOffset
          && endOffset <= content.length()) {
        diagnostic.setData(content.substring((int) startOffset, (int) endOffset));
      }
    }

    return diagnostic;
  }
}
