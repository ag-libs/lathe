package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.LearnedStaticImports;
import io.github.aglibs.lathe.analysis.SourceFeatureRequest;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Logger;
import org.eclipse.lsp4j.Position;

// Routes a request about a file to the worker that analyzes it, and keeps the analyses of the files
// used most recently: a file routed here is touched in the analysis LRU, and the least recent one
// past its bound is dropped from every worker's cache. Event-loop state, like the open documents it
// reads (the session owns and changes those).
final class FeatureRouter {

  private static final Logger LOG = Logger.getLogger(FeatureRouter.class.getName());

  private final ServerEventLoop eventLoop;
  private final WorkspaceState state;
  private final DocumentRegistry docs;
  private final AnalysisLru analysisLru = new AnalysisLru();

  FeatureRouter(
      final ServerEventLoop eventLoop, final WorkspaceState state, final DocumentRegistry docs) {
    this.eventLoop = eventLoop;
    this.state = state;
    this.docs = docs;
  }

  // A closed file's analysis is no longer worth keeping.
  void forget(final String uri) {
    eventLoop.checkOnLoop();
    analysisLru.remove(uri);
  }

  // The request a position feature sends to the module's worker, for the open document.
  SourceFeatureRequest featureRequest(final OpenDocument doc, final Position pos) {
    return new SourceFeatureRequest(
        doc.uri(),
        doc.content(),
        doc.version(),
        pos,
        state.registry().sourceRootsFor(LatheUri.toPath(doc.uri())),
        state.manifest().libraries());
  }

  CompilerRoute routeCompiler(final String uri) {
    eventLoop.checkOnLoop();
    final var path = LatheUri.toPath(uri);
    return state
        .registry()
        .moduleSourceFor(path)
        .<CompilerRoute>map(
            module -> new CompilerRoute.Module(state.registry().workerFor(module), module))
        .orElseGet(
            () -> {
              if (state.manifest().libraries().containsFile(path)) {
                return new CompilerRoute.External(state.registry().externalWorker());
              }

              return new CompilerRoute.Missing(
                  uri,
                  "This file is not under any Lathe module source root, so Lathe cannot analyze it.");
            });
  }

  // The static imports the file's own scope declares: test files learn from test files, main files
  // from main files, and a file outside any module from nothing.
  LearnedStaticImports learnedStaticImports(final CompilerRoute route) {
    eventLoop.checkOnLoop();
    return route instanceof final CompilerRoute.Module module
        ? state.candidateIndex().staticImports(module.config().isTestTree())
        : LearnedStaticImports.empty();
  }

  // An open document with the worker that analyzes it and, for a reactor file, its module: what a
  // feature that runs its own search starts from. Empty when the document is not open or no worker
  // analyzes it; touches the analysis cache.
  Optional<OpenRoute> openRoute(final String uri) {
    eventLoop.checkOnLoop();
    final OpenDocument doc = docs.get(uri);
    if (doc == null) {
      return Optional.empty();
    }

    final CompilerRoute route = routeCompiler(uri);
    final Optional<CompilationWorker> worker = route.analysisWorker();
    if (worker.isEmpty()) {
      return Optional.empty();
    }

    touchAnalysisCache(uri);
    final ModuleSourceConfig config =
        route instanceof final CompilerRoute.Module module ? module.config() : null;
    return Optional.of(new OpenRoute(doc, worker.get(), config));
  }

  // config is null for a file outside the reactor (a dependency's or the JDK's source).
  record OpenRoute(OpenDocument doc, CompilationWorker worker, ModuleSourceConfig config) {
    OpenRoute {
      ValidCheck.check().notNull(doc, "doc").notNull(worker, "worker").validate();
    }
  }

  <T> CompletableFuture<T> openDocFeature(
      final String operation,
      final String uri,
      final T fallback,
      final BiFunction<CompilationWorker, OpenDocument, CompletableFuture<T>> op) {
    eventLoop.checkOnLoop();
    final OpenDocument doc = docs.get(uri);
    if (doc == null) {
      return CompletableFuture.completedFuture(fallback);
    }

    return routeFeature(operation, uri, fallback, worker -> op.apply(worker, doc));
  }

  void touchAnalysisCache(final String uri) {
    eventLoop.checkOnLoop();
    analysisLru
        .touch(uri)
        .ifPresent(
            evictedUri -> {
              LOG.fine(
                  () -> "[evict] %s selected open=%d".formatted(evictedUri, analysisLru.size()));
              state.registry().dropFromAllCaches(evictedUri);
            });
  }

  // A feature on a file, answered by the worker of the module that compiles it. A file no module
  // compiles gets the fallback, and so does a failure, logged under the operation's name.
  <T> CompletableFuture<T> routeFeature(
      final String operation,
      final String uri,
      final T fallback,
      final Function<CompilationWorker, CompletableFuture<T>> op) {
    final Optional<CompilationWorker> worker = routeCompiler(uri).analysisWorker();
    if (worker.isEmpty()) {
      return CompletableFuture.completedFuture(fallback);
    }

    touchAnalysisCache(uri);
    return op.apply(worker.get()).exceptionally(FeatureAnswers.failedFor(operation, uri, fallback));
  }
}
