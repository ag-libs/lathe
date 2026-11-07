package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.CodeActionRequest;
import io.github.aglibs.lathe.analysis.DiagnosticPayload;
import io.github.aglibs.lathe.analysis.HierarchyFeatures;
import io.github.aglibs.lathe.analysis.LearnedStaticImports;
import io.github.aglibs.lathe.analysis.MissingImportsResult;
import io.github.aglibs.lathe.analysis.NavigationFeatures;
import io.github.aglibs.lathe.analysis.RunTarget;
import io.github.aglibs.lathe.analysis.SemanticToken;
import io.github.aglibs.lathe.analysis.SourceFeatureRequest;
import io.github.aglibs.lathe.analysis.TokenScanner;
import io.github.aglibs.lathe.analysis.TypeHierarchyExplorerResult;
import io.github.aglibs.lathe.analysis.TypeHierarchyItemDataCodec;
import io.github.aglibs.lathe.analysis.WorkspaceSymbolResolver;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.analysis.completion.CompletionOutcome;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.module.ModuleNameDiscovery;
import io.github.aglibs.lathe.server.run.TestCounterparts;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// What an editor or agent asks about the code: the open document's features (hover, navigation,
// completion, code actions, tokens, symbols, folds), type hierarchies, workspace symbols, runnable
// targets and a file's test counterparts. Event-loop state: it reads the open documents, the router
// and the reactor index, each of which checks the loop.
final class DocumentFeatures {

  private static final Logger LOG = Logger.getLogger(DocumentFeatures.class.getName());

  // Upper bound on transitive subtypes returned by the type-hierarchy explorer, so a near-Object or
  // huge marker-interface anchor cannot flood the picker or the source-locating pass. Beyond it the
  // result is flagged truncated.
  private static final int TYPE_HIERARCHY_NODE_CAP = 2000;

  private final WorkspaceState state;
  private final ReactorTypeIndex reactorIndex;
  private final FeatureRouter router;
  private final DocumentRegistry docs;

  DocumentFeatures(
      final WorkspaceState state,
      final ReactorTypeIndex reactorIndex,
      final FeatureRouter router,
      final DocumentRegistry docs) {
    this.state = state;
    this.reactorIndex = reactorIndex;
    this.router = router;
    this.docs = docs;
  }

  // The tests of the file's type, or from a test the type it tests.
  TestCounterparts testCounterparts(final String uri) {
    final Path file = LatheUri.toPath(uri);
    final ContextInfo context = state.layout().resolveContext(file);
    return TestCounterparts.find(
        file,
        context == null ? "" : context.pkg(),
        state.registry()::moduleSourceFor,
        reactorIndex.current(),
        state.registry().allSourceRoots());
  }

  CompletableFuture<List<RunTarget>> runnablesFuture(final String uri) {
    // Only an open document has editor content to analyze. The neotest client discovers against the
    // server-confirmed open (its first publishDiagnostics), so a miss here means the file genuinely
    // isn't open — answer empty rather than speculatively compile from disk.
    final OpenDocument doc = docs.get(uri);
    if (doc == null) {
      LOG.fine(() -> "[runnables] %s no open doc → empty".formatted(uri));
      return CompletableFuture.completedFuture(List.of());
    }

    final var t = Stopwatch.start();
    if (router.routeCompiler(uri) instanceof final CompilerRoute.Module module) {
      router.touchAnalysisCache(uri);
      final String moduleRel = state.layout().moduleRel(module.config());
      return module
          .worker()
          .submit(
              session -> session.files().runnables(uri, doc.content(), doc.version(), moduleRel))
          .thenApply(FeatureAnswers.logged(LOG, "runnables", uri, t, "targets"));
    }

    return CompletableFuture.completedFuture(List.of());
  }

  // A reactor file checked without opening it: no open document, no analysis kept. verify_change
  // checks a change's files and their callers this way, many at once.
  CompletableFuture<List<Diagnostic>> diagnosticsTransientFuture(
      final String uri, final String content) {
    return router.routeCompiler(uri) instanceof final CompilerRoute.Module module
        ? module.worker().submit(session -> session.diagnosticsTransient(uri, content))
        : CompletableFuture.completedFuture(List.of());
  }

  CompletableFuture<Hover> hoverFuture(final String uri, final Position pos) {
    return router.openDocFeature(
        "hover",
        uri,
        null,
        (worker, doc) -> {
          final SourceFeatureRequest request = router.featureRequest(doc, pos);
          return worker.submit(session -> session.navigation().hover(request));
        });
  }

  // Whether the position is on a name. What cannot be checked (a file no module compiles, a failed
  // check) passes, and the tool gives its own answer.
  CompletableFuture<Boolean> onNameFuture(final String uri, final Position pos) {
    return router.openDocFeature(
        "onName",
        uri,
        true,
        (worker, doc) -> {
          final SourceFeatureRequest request = router.featureRequest(doc, pos);
          return worker.submit(session -> session.navigation().onName(request));
        });
  }

  CompletableFuture<SignatureHelp> signatureHelpFuture(final String uri, final Position pos) {
    return router.openDocFeature(
        "signatureHelp",
        uri,
        null,
        (worker, doc) -> {
          final SourceFeatureRequest request = router.featureRequest(doc, pos);
          return worker.submit(session -> session.navigation().signatureHelp(request));
        });
  }

  CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      definitionFuture(final String uri, final Position pos) {
    return locationFuture("definition", uri, pos, NavigationFeatures::definition);
  }

  CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      declarationFuture(final String uri, final Position pos) {
    return locationFuture("declaration", uri, pos, NavigationFeatures::declaration);
  }

  CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      typeDefinitionFuture(final String uri, final Position pos) {
    return locationFuture("typeDefinition", uri, pos, NavigationFeatures::typeDefinition);
  }

  // One location for the open document's position, found by the document's module worker.
  private CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      locationFuture(
          final String operation,
          final String uri,
          final Position pos,
          final BiFunction<NavigationFeatures, SourceFeatureRequest, Optional<Location>> find) {
    LOG.info(
        () ->
            "[%s] %s line=%d character=%d"
                .formatted(operation, uri, pos.getLine(), pos.getCharacter()));
    return router.openDocFeature(
        operation,
        uri,
        Either.forLeft(List.of()),
        (worker, doc) -> {
          final SourceFeatureRequest request = router.featureRequest(doc, pos);
          return worker
              .submit(session -> find.apply(session.navigation(), request))
              .thenApply(
                  location ->
                      FeatureAnswers.definitionResult(location.map(List::of).orElseGet(List::of)));
        });
  }

  CompletableFuture<List<TypeHierarchyItem>> prepareTypeHierarchyFuture(
      final String uri, final Position pos) {
    final var indexSnapshot = reactorIndex.current();
    final var t = Stopwatch.start();
    return router
        .openDocFeature(
            "typeHierarchy:prepare",
            uri,
            List.of(),
            (worker, doc) -> {
              final var request = router.featureRequest(doc, pos);
              return worker.<List<TypeHierarchyItem>>submit(
                  session -> session.hierarchy().prepareTypeHierarchy(request, indexSnapshot));
            })
        .thenApply(FeatureAnswers.logged(LOG, "typeHierarchy:prepare", uri, t, "items"));
  }

  CompletableFuture<List<TypeHierarchyItem>> typeHierarchySupertypesFuture(
      final TypeHierarchyItem item) {
    return typeHierarchyLevel(
        "typeHierarchy:supertypes", item, HierarchyFeatures::typeHierarchySupertypes);
  }

  CompletableFuture<List<TypeHierarchyItem>> typeHierarchySubtypesFuture(
      final TypeHierarchyItem item) {
    return typeHierarchyLevel(
        "typeHierarchy:subtypes", item, HierarchyFeatures::typeHierarchySubtypes);
  }

  // One level of a type's hierarchy, answered by the worker of the module that holds it.
  private CompletableFuture<List<TypeHierarchyItem>> typeHierarchyLevel(
      final String operation, final TypeHierarchyItem item, final TypeHierarchyStep step) {
    final var data = TypeHierarchyItemDataCodec.decode(item.getData());
    if (data == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    final var indexSnapshot = reactorIndex.current();
    final List<Path> sourceDirs = state.typeSourceDirs();
    final var t = Stopwatch.start();
    return router
        .routeFeature(
            operation,
            data.routingUri(),
            List.of(),
            worker ->
                worker.<List<TypeHierarchyItem>>submit(
                    session -> step.apply(session.hierarchy(), item, indexSnapshot, sourceDirs)))
        .thenApply(FeatureAnswers.logged(LOG, operation, data.binaryName(), t, "items"));
  }

  @FunctionalInterface
  private interface TypeHierarchyStep {
    List<TypeHierarchyItem> apply(
        HierarchyFeatures hierarchy,
        TypeHierarchyItem item,
        WorkspaceTypeIndex index,
        List<Path> sourceDirs);
  }

  CompletableFuture<TypeHierarchyExplorerResult> typeHierarchyExplorerFuture(
      final String uri, final Position pos) {
    final var t = Stopwatch.start();
    return prepareTypeHierarchyFuture(uri, pos)
        .thenCompose(
            items -> {
              if (items.isEmpty()) {
                return CompletableFuture.completedFuture(TypeHierarchyExplorerResult.empty());
              }

              final var self = items.get(0);
              final var data = TypeHierarchyItemDataCodec.decode(self.getData());
              final var indexSnapshot = reactorIndex.current();
              final List<Path> sourceDirs = state.typeSourceDirs();
              return router.routeFeature(
                  "typeHierarchy:explore",
                  data.routingUri(),
                  TypeHierarchyExplorerResult.empty(),
                  worker ->
                      worker.submit(
                          session ->
                              session
                                  .hierarchy()
                                  .typeHierarchyExplore(
                                      self, indexSnapshot, sourceDirs, TYPE_HIERARCHY_NODE_CAP)));
            })
        .thenApply(
            result -> {
              LOG.info(
                  () ->
                      "[typeHierarchy:explore] %s %dms supertypes=%d subtypes=%d%s"
                          .formatted(
                              uri,
                              t.elapsedMs(),
                              result.supertypes().size(),
                              result.subtypes().size(),
                              result.truncated() ? " truncated" : ""));
              return result;
            });
  }

  CompletableFuture<CompletionOutcome> completionFuture(
      final String uri, final Position pos, final CompletionContext context) {
    final var indexSnapshot = reactorIndex.current();
    final var route = router.routeCompiler(uri);
    final LearnedStaticImports staticImports = router.learnedStaticImports(route);
    return router.openDocFeature(
        "completion",
        uri,
        CompletionOutcome.of(List.of()),
        (worker, doc) -> {
          // On the loop, and only for an open, routed document: a module-info's names scan the
          // module path.
          final List<String> moduleNames = completionModuleNames(route, uri);
          return worker.submit(
              session ->
                  session.complete(
                      uri,
                      doc.content(),
                      doc.version(),
                      pos,
                      context,
                      indexSnapshot,
                      moduleNames,
                      staticImports));
        });
  }

  CompletableFuture<List<Either<Command, CodeAction>>> codeActionFuture(
      final String uri,
      final Range range,
      final CodeActionContext context,
      final boolean resolveSupported) {
    final OpenDocument openFile = docs.get(uri);
    if (openFile == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    final CompilerRoute route = router.routeCompiler(uri);
    if (route instanceof CompilerRoute.External) {
      return CompletableFuture.completedFuture(List.of());
    }

    final List<CodeActionRequest> requests =
        context.getDiagnostics().stream()
            .map(diag -> toCodeActionRequest(uri, diag))
            .filter(Objects::nonNull)
            .toList();

    final Set<String> neededTypeNames =
        requests.stream()
            .filter(r -> r.payload().kind().offersTypeImport())
            .map(r -> r.payload().name())
            .collect(Collectors.toUnmodifiableSet());
    final LearnedStaticImports staticImports = router.learnedStaticImports(route);
    return router.routeFeature(
        "codeAction",
        uri,
        List.of(),
        moduleWorker ->
            moduleWorker
                .submit(session -> session.cachedTypeEntries(neededTypeNames))
                .thenCompose(reactorIndex::withOpenEntries)
                .thenCompose(
                    enriched ->
                        moduleWorker.submit(
                            session ->
                                session
                                    .files()
                                    .codeAction(
                                        uri,
                                        openFile.content(),
                                        openFile.version(),
                                        range,
                                        requests,
                                        enriched,
                                        staticImports)))
                .thenApply(
                    actions ->
                        resolveSupported
                            ? CompileCheckedActions.defer(uri, openFile.version(), actions)
                            : actions));
  }

  // The check may compile twice (the rewritten text, then the original to compare errors); the
  // second starts from the first one's result, which the worker delivers on the loop.
  CompletableFuture<CodeAction> resolveCodeActionFuture(final CodeAction action) {
    return CompileCheckedActions.resolve(action, docs::get, this::diagnosticsTransientFuture);
  }

  CompletableFuture<MissingImportsResult> missingImportsFuture(final String uri) {
    final OpenDocument openFile = docs.get(uri);
    final CompilerRoute route = router.routeCompiler(uri);
    if (openFile == null || route instanceof CompilerRoute.External) {
      return CompletableFuture.completedFuture(MissingImportsResult.empty());
    }

    final var indexSnapshot = reactorIndex.current();
    final LearnedStaticImports staticImports = router.learnedStaticImports(route);
    return router.routeFeature(
        "missingImports",
        uri,
        MissingImportsResult.empty(),
        moduleWorker ->
            moduleWorker.submit(
                session ->
                    session
                        .files()
                        .missingImports(
                            uri,
                            openFile.content(),
                            openFile.version(),
                            indexSnapshot,
                            staticImports)));
  }

  private static CodeActionRequest toCodeActionRequest(final String uri, final Diagnostic diag) {
    final DiagnosticPayload payload = DiagnosticPayloadCodec.extractPayload(diag.getData());
    return payload != null ? new CodeActionRequest(uri, diag, payload) : null;
  }

  CompletableFuture<SemanticTokens> semanticTokensFuture(final String uri) {
    return router.openDocFeature(
        "semanticTokens",
        uri,
        null,
        (worker, doc) ->
            worker
                .submit(
                    session -> session.files().semanticTokens(uri, doc.content(), doc.version()))
                .thenApply(DocumentFeatures::encodeTokensOrNull));
  }

  CompletableFuture<List<DocumentSymbol>> documentSymbolFuture(final String uri) {
    return router.openDocFeature(
        "documentSymbol",
        uri,
        List.of(),
        (worker, doc) ->
            worker.submit(session -> session.files().documentSymbol(uri, doc.content())));
  }

  CompletableFuture<List<FoldingRange>> foldingRangeFuture(final String uri) {
    final OpenDocument doc = docs.get(uri);
    if (doc == null) {
      LOG.warning(() -> "[foldingRange] %s no folds document not open".formatted(uri));
      return CompletableFuture.completedFuture(List.of());
    }

    return router.routeFeature(
        "foldingRange",
        uri,
        List.of(),
        worker -> worker.submit(session -> session.files().foldingRange(uri, doc.content())));
  }

  List<SymbolInformation> workspaceSymbol(final String query, final boolean reactorOnly) {
    final var t = Stopwatch.start();
    final List<Path> sourceDirs = state.typeSourceDirs();
    final List<SymbolInformation> results =
        WorkspaceSymbolResolver.resolve(
            query,
            reactorIndex.current(),
            sourceDirs,
            entry -> !reactorOnly || reactorIndex.current().isReactorType(entry.binaryName()));
    LOG.info(
        () -> "[symbol] query=%s %dms hits=%d".formatted(query, t.elapsedMs(), results.size()));
    return results;
  }

  private static List<String> completionModuleNames(final CompilerRoute route, final String uri) {
    if (!LatheLayout.MODULE_INFO_JAVA.equals(LatheUri.toPath(uri).getFileName().toString())) {
      return List.of();
    }

    if (route instanceof final CompilerRoute.Module module) {
      return ModuleNameDiscovery.observableModuleNames(module.config());
    }

    return List.of();
  }

  private static SemanticTokens encodeTokensOrNull(final List<SemanticToken> tokens) {
    if (tokens == null) {
      return null;
    }

    final int[] encoded = TokenScanner.encode(tokens);
    return new SemanticTokens(IntStream.of(encoded).boxed().toList());
  }
}
