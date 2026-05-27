package io.github.aglibs.lathe.server;

import static java.util.logging.Level.SEVERE;

import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.SemanticToken;
import io.github.aglibs.lathe.server.analysis.SourceFeatureRequest;
import io.github.aglibs.lathe.server.analysis.TokenScanner;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.server.analysis.completion.CompletionOutcome;
import io.github.aglibs.lathe.server.module.CompileRequest;
import io.github.aglibs.lathe.server.module.CompileResponse;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.lathe.server.module.ModuleSourceWorker;
import io.github.aglibs.lathe.server.module.WorkspaceModules;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

/** Not thread-safe. All methods must be called from the {@link ServerWorker} thread. */
final class WorkspaceSession {

  private static final Logger LOG = Logger.getLogger(WorkspaceSession.class.getName());

  private final LanguageClient client;
  private final ServerWorker worker;
  private final long debounceMs;
  private Path workspaceRoot;
  private WorkspaceManifest manifest = WorkspaceManifest.empty();
  private WorkspaceModules workspace = WorkspaceModules.empty();
  private WorkspaceWatcher watcher;
  private final Map<String, OpenDocument> openDocuments = new HashMap<>();
  private long nextGeneration;

  WorkspaceSession(final LanguageClient client, final ServerWorker worker, final long debounceMs) {
    this.client = client;
    this.worker = worker;
    this.debounceMs = debounceMs;
  }

  void initialize(final Path root) {
    this.workspaceRoot = root;
    manifest = WorkspaceManifest.load(root);
    final var typeIndex = WorkspaceTypeIndex.build(manifest.typeIndexShardPaths());
    workspace = WorkspaceModules.scan(root, manifest, typeIndex);
    watcher = new WorkspaceWatcher(root);
    worker.scheduleAtFixedRate(2_000L, this::checkForChanges);
  }

  void close() {
    workspace.close();
  }

  void onOpen(final String uri, final String content, final int version) {
    final var snapshot = putOpenFile(uri, content, version);
    LOG.info(() -> "[open] %s".formatted(uri));
    compileAndPublish(snapshot, CompileMode.OPEN);
  }

  void onChange(final String uri, final String content, final int version) {
    putOpenFile(uri, content, version);
    LOG.fine(() -> "[change] %s".formatted(uri));
    client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
    worker.cancel(uri);
    worker.schedule(
        uri,
        debounceMs,
        () -> {
          final var latest = openDocuments.get(uri);
          if (latest != null) {
            compileAndPublish(latest, CompileMode.FAST);
          }
        });
  }

  void onClose(final String uri) {
    openDocuments.remove(uri);
    LOG.info(() -> "[close] %s".formatted(uri));
    worker.cancel(uri);
    workspace.dropFromAllCaches(uri);
    client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
  }

  void onSave(final String uri, final String savedContent) {
    LOG.info(() -> "[save] %s".formatted(uri));
    worker.cancel(uri);

    final var snapshot = snapshotForSave(uri, savedContent);
    if (snapshot == null) {
      return;
    }

    final var route = routeCompiler(uri);
    final AfterCompile afterCompile =
        switch (route) {
          case CompilerRoute.Module module ->
              publishIfCurrentThen(
                  () -> {
                    scheduleAstRefresh(uri);
                    scheduleOpenFilesInModule(uri, module.config());
                  });
          case CompilerRoute.External ignored ->
              publishIfCurrentThen(() -> scheduleAstRefresh(uri));
          case CompilerRoute.Missing ignored -> this::publishIfCurrent;
        };
    submitCompile(route, snapshot, CompileMode.FULL, afterCompile);
  }

  CompletableFuture<Hover> hoverFuture(final String uri, final Position pos) {
    final var openFile = openDocuments.get(uri);
    if (openFile == null) {
      return CompletableFuture.completedFuture(null);
    }

    final var request =
        new SourceFeatureRequest(
            openFile.uri(), openFile.content(), pos, workspace.allSourceRoots(), manifest);
    return routeFeature(
        uri,
        moduleWorker ->
            moduleWorker
                .hover(request)
                .exceptionally(ex -> logAndReturn(ex, "[hover] failed for " + uri, null)),
        null);
  }

  CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      definitionFuture(final String uri, final Position pos) {
    final var openFile = openDocuments.get(uri);
    if (openFile == null) {
      return CompletableFuture.completedFuture(Either.forLeft(List.of()));
    }

    final var request =
        new SourceFeatureRequest(
            openFile.uri(), openFile.content(), pos, workspace.allSourceRoots(), manifest);
    return routeFeature(
        uri,
        moduleWorker ->
            moduleWorker
                .definition(request)
                .thenApply(location -> definitionResult(location.map(List::of).orElseGet(List::of)))
                .exceptionally(
                    ex ->
                        logAndReturn(
                            ex, "[definition] failed for " + uri, Either.forLeft(List.of()))),
        Either.forLeft(List.of()));
  }

  CompletableFuture<CompletionOutcome> completionFuture(
      final String uri, final Position pos, final CompletionContext context) {
    final var openFile = openDocuments.get(uri);
    if (openFile == null) {
      return CompletableFuture.completedFuture(CompletionOutcome.of(List.of()));
    }

    return routeFeature(
        uri,
        moduleWorker ->
            moduleWorker
                .complete(uri, openFile.content(), openFile.version(), pos, context)
                .exceptionally(
                    ex ->
                        logAndReturn(
                            ex, "[completion] failed for " + uri, CompletionOutcome.of(List.of()))),
        CompletionOutcome.of(List.of()));
  }

  CompletableFuture<SemanticTokens> semanticTokensFuture(final String uri) {
    final var openFile = openDocuments.get(uri);
    if (openFile == null) {
      return CompletableFuture.completedFuture(null);
    }

    final int version = openFile.version();
    return routeFeature(
        uri,
        moduleWorker ->
            moduleWorker
                .semanticTokens(uri, version)
                .thenApply(WorkspaceSession::encodeTokensOrNull)
                .exceptionally(ex -> logAndReturn(ex, "[semanticTokens] failed for " + uri, null)),
        null);
  }

  List<? extends TextEdit> format(final String tag, final String uri) {
    LOG.fine(() -> "[%s] %s".formatted(tag, uri));
    final var openFile = openDocuments.get(uri);
    return JavaFormatter.format(openFile != null ? openFile.content() : null);
  }

  private void checkForChanges() {
    if (watcher != null && watcher.poll()) {
      reload();
    }
  }

  private void reload() {
    LOG.info(() -> "[reload] workspace changed, reloading");
    final var newManifest = WorkspaceManifest.load(workspaceRoot);
    final var newTypeIndex = WorkspaceTypeIndex.build(newManifest.typeIndexShardPaths());
    final var newWorkspace = WorkspaceModules.scan(workspaceRoot, newManifest, newTypeIndex);
    final var old = workspace;
    workspace = newWorkspace;
    manifest = newManifest;
    List.copyOf(openDocuments.keySet())
        .forEach(
            uri -> {
              final var f = openDocuments.get(uri);
              putOpenFile(uri, f.content(), f.version());
            });
    old.close();
    scheduleAllOpenFiles();
  }

  private void compileAndPublish(final OpenDocument snapshot, final CompileMode mode) {
    submitCompile(snapshot, mode, this::publishIfCurrent);
  }

  private void submitCompile(
      final OpenDocument snapshot, final CompileMode mode, final AfterCompile afterCompile) {
    submitCompile(routeCompiler(snapshot.uri()), snapshot, mode, afterCompile);
  }

  private void submitCompile(
      final CompilerRoute route,
      final OpenDocument snapshot,
      final CompileMode mode,
      final AfterCompile afterCompile) {
    switch (route) {
      case CompilerRoute.Module module -> submitTo(module.worker(), snapshot, mode, afterCompile);
      case CompilerRoute.External external ->
          submitTo(external.worker(), snapshot, mode, afterCompile);
      case CompilerRoute.Missing missing -> publishMissingDiagnostic(missing);
    }
  }

  private void submitTo(
      final ModuleSourceWorker moduleWorker,
      final OpenDocument snapshot,
      final CompileMode mode,
      final AfterCompile afterCompile) {
    final var request =
        new CompileRequest(
            snapshot.uri(), snapshot.content(), snapshot.version(), snapshot.generation(), mode);
    moduleWorker
        .compile(request)
        .thenAccept(result -> worker.execute(() -> afterCompile.accept(snapshot, result)))
        .exceptionally(
            ex -> {
              worker.execute(() -> publishCompileError(snapshot, mode, ex));
              return null;
            });
  }

  private CompilerRoute routeCompiler(final String uri) {
    final var path = toPath(uri);
    return workspace
        .moduleSourceFor(path)
        .<CompilerRoute>map(module -> new CompilerRoute.Module(workspace.workerFor(module), module))
        .orElseGet(
            () -> {
              if (manifest.containsFile(path)) {
                return new CompilerRoute.External(workspace.externalWorker());
              }

              return new CompilerRoute.Missing(
                  uri, "Run `mvn process-test-classes` to initialize Lathe for this module");
            });
  }

  private <T> CompletableFuture<T> routeFeature(
      final String uri,
      final Function<ModuleSourceWorker, CompletableFuture<T>> operation,
      final T missingFallback) {
    return switch (routeCompiler(uri)) {
      case CompilerRoute.Module module -> operation.apply(module.worker());
      case CompilerRoute.External external -> operation.apply(external.worker());
      case CompilerRoute.Missing ignored -> CompletableFuture.completedFuture(missingFallback);
    };
  }

  private void publishMissingDiagnostic(final CompilerRoute.Missing missing) {
    LOG.warning(() -> "[compile] no module for %s".formatted(missing.uri()));
    client.publishDiagnostics(
        singleDiag(missing.uri(), missing.message(), DiagnosticSeverity.Warning));
  }

  private boolean publishIfCurrent(final OpenDocument snapshot, final CompileResponse result) {
    if (isStale(snapshot, result.generation())) {
      return false;
    }

    client.publishDiagnostics(new PublishDiagnosticsParams(result.uri(), result.diagnostics()));
    client.refreshSemanticTokens();
    return true;
  }

  private boolean refreshTokensIfCurrent(
      final OpenDocument snapshot, final CompileResponse result) {
    if (isStale(snapshot, result.generation())) {
      return false;
    }

    client.refreshSemanticTokens();
    return true;
  }

  private AfterCompile publishIfCurrentThen(final Runnable followUp) {
    return (snapshot, result) -> {
      if (publishIfCurrent(snapshot, result)) {
        followUp.run();
      }
    };
  }

  private boolean isStale(final OpenDocument snapshot, final long generation) {
    final var current = openDocuments.get(snapshot.uri());
    return current == null || current.generation() != generation;
  }

  private void scheduleOpenFilesInModule(
      final String savedUri, final ModuleSourceConfig savedModule) {
    LOG.fine(
        () ->
            "[save] checking %d open file(s) for dependents of %s"
                .formatted(openDocuments.size(), savedUri));
    openDocuments.values().stream()
        .map(OpenDocument::uri)
        .filter(uri -> !uri.equals(savedUri))
        .filter(
            uri ->
                workspace
                    .moduleSourceFor(toPath(uri))
                    .map(m -> m.moduleDir().equals(savedModule.moduleDir()))
                    .orElse(false))
        .forEach(this::scheduleOpenFile);
  }

  private void scheduleAllOpenFiles() {
    openDocuments.values().stream().map(OpenDocument::uri).toList().forEach(this::scheduleOpenFile);
  }

  private void scheduleOpenFile(final String uri) {
    worker.schedule(
        uri,
        0L,
        () -> {
          final var openFile = openDocuments.get(uri);
          if (openFile != null) {
            compileAndPublish(openFile, CompileMode.OPEN);
          }
        });
  }

  private void scheduleAstRefresh(final String uri) {
    worker.schedule(
        uri,
        0L,
        () -> {
          final var openFile = openDocuments.get(uri);
          if (openFile != null) {
            submitCompile(openFile, CompileMode.FAST, this::refreshTokensIfCurrent);
          }
        });
  }

  private OpenDocument snapshotForSave(final String uri, final String savedContent) {
    final var openFile = openDocuments.get(uri);
    if (openFile == null) {
      return null;
    }

    return savedContent != null ? putOpenFile(uri, savedContent, openFile.version()) : openFile;
  }

  private OpenDocument putOpenFile(final String uri, final String content, final int version) {
    final var openFile = new OpenDocument(uri, content, version, nextGeneration());
    openDocuments.put(uri, openFile);
    return openFile;
  }

  private long nextGeneration() {
    return ++nextGeneration;
  }

  private void publishCompileError(
      final OpenDocument snapshot, final CompileMode mode, final Throwable ex) {
    if (isStale(snapshot, snapshot.generation())) {
      return;
    }

    LOG.log(SEVERE, ex, () -> "[compile:%s] failed for %s".formatted(mode.tag, snapshot.uri()));
    final var msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    client.publishDiagnostics(
        singleDiag(snapshot.uri(), "Lathe: " + msg, DiagnosticSeverity.Error));
  }

  private static <T> T logAndReturn(final Throwable ex, final String msg, final T fallback) {
    LOG.log(SEVERE, ex, () -> msg);
    return fallback;
  }

  private static Either<List<? extends Location>, List<? extends LocationLink>> definitionResult(
      final List<? extends Location> locations) {
    return Either.forLeft(locations);
  }

  private static SemanticTokens encodeTokensOrNull(final List<SemanticToken> tokens) {
    if (tokens == null) {
      return null;
    }

    final var encoded = TokenScanner.encode(tokens);
    return new SemanticTokens(IntStream.of(encoded).boxed().toList());
  }

  private static Path toPath(final String uri) {
    return Path.of(URI.create(uri));
  }

  private static PublishDiagnosticsParams singleDiag(
      final String uri, final String message, final DiagnosticSeverity severity) {
    return new PublishDiagnosticsParams(
        uri,
        List.of(
            new Diagnostic(
                new Range(new Position(0, 0), new Position(0, 1)), message, severity, "lathe")));
  }

  private sealed interface CompilerRoute {
    record Module(ModuleSourceWorker worker, ModuleSourceConfig config) implements CompilerRoute {}

    record External(ModuleSourceWorker worker) implements CompilerRoute {}

    record Missing(String uri, String message) implements CompilerRoute {}
  }

  @FunctionalInterface
  private interface AfterCompile {
    void accept(OpenDocument snapshot, CompileResponse result);
  }

  private record OpenDocument(String uri, String content, int version, long generation) {}
}
