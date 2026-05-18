package io.github.aglibs.lathe.server;

import static java.util.logging.Level.SEVERE;

import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.SemanticToken;
import io.github.aglibs.lathe.server.analysis.TokenScanner;
import io.github.aglibs.lathe.server.module.CompileRequest;
import io.github.aglibs.lathe.server.module.CompileResult;
import io.github.aglibs.lathe.server.module.ModuleConfig;
import io.github.aglibs.lathe.server.module.ModuleWorker;
import io.github.aglibs.lathe.server.module.ModuleWorkspace;
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
import org.eclipse.lsp4j.*;
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
  private ModuleWorkspace workspace = ModuleWorkspace.empty();
  private WorkspaceWatcher watcher;
  private final Map<String, OpenFile> openFiles = new HashMap<>();
  private long nextGeneration;

  WorkspaceSession(final LanguageClient client, final ServerWorker worker, final long debounceMs) {
    this.client = client;
    this.worker = worker;
    this.debounceMs = debounceMs;
  }

  void initialize(final Path root) {
    this.workspaceRoot = root;
    manifest = WorkspaceManifest.load(root);
    workspace = ModuleWorkspace.scan(root, manifest);
    watcher = new WorkspaceWatcher(root);
    worker.scheduleAtFixedRate(2_000L, this::checkForChanges);
  }

  void close() {
    workspace.close();
  }

  void onOpen(final String uri, final String content) {
    final var snapshot = putOpenFile(uri, content);
    LOG.fine(() -> "[open] %s".formatted(uri));
    compileAndPublish(snapshot, CompileMode.OPEN);
  }

  void onChange(final String uri, final String content) {
    putOpenFile(uri, content);
    LOG.fine(() -> "[change] %s".formatted(uri));
    client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
    worker.cancel(uri);
    worker.schedule(
        uri,
        debounceMs,
        () -> {
          final var latest = openFiles.get(uri);
          if (latest != null) {
            compileAndPublish(latest, CompileMode.FAST);
          }
        });
  }

  void onClose(final String uri) {
    openFiles.remove(uri);
    worker.cancel(uri);
    workspace.dropFromAllCaches(uri);
    client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
  }

  void onSave(final String uri, final String savedContent) {
    LOG.fine(() -> "[save] %s".formatted(uri));
    worker.cancel(uri);

    final var snapshot = snapshotForSave(uri, savedContent);
    if (snapshot == null) {
      return;
    }

    final var route = routeCompiler(uri);
    final AfterCompile afterCompile =
        switch (route) {
          case CompilerRoute.Module module ->
              publishIfCurrentThen(() -> scheduleOpenFilesInModule(uri, module.config()));
          case CompilerRoute.External ignored -> this::publishIfCurrent;
          case CompilerRoute.Missing ignored -> this::publishIfCurrent;
        };
    submitCompile(route, snapshot, CompileMode.FULL, afterCompile);
  }

  CompletableFuture<Hover> hoverFuture(final String uri, final Position pos) {
    final var sourceRoots = workspace.allSourceRoots();
    final var manifestSnapshot = manifest;
    return routeFeature(
        uri,
        moduleWorker ->
            moduleWorker
                .hover(uri, pos, sourceRoots, manifestSnapshot)
                .exceptionally(ex -> logAndReturn(ex, "[hover] failed for " + uri, null)),
        null);
  }

  CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      definitionFuture(final String uri, final Position pos) {
    final var sourceRoots = workspace.allSourceRoots();
    final var manifestSnapshot = manifest;
    return routeFeature(
        uri,
        moduleWorker ->
            moduleWorker
                .definition(uri, pos, sourceRoots, manifestSnapshot)
                .thenApply(location -> definitionResult(location.map(List::of).orElseGet(List::of)))
                .exceptionally(
                    ex ->
                        logAndReturn(
                            ex, "[definition] failed for " + uri, Either.forLeft(List.of()))),
        Either.forLeft(List.of()));
  }

  CompletableFuture<List<CompletionItem>> completionFuture(
      final String uri, final Position pos, final CompletionContext context) {
    final var openFile = openFiles.get(uri);
    if (openFile == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    return routeFeature(
        uri,
        moduleWorker ->
            moduleWorker
                .complete(uri, openFile.content(), pos, context)
                .exceptionally(ex -> logAndReturn(ex, "[completion] failed for " + uri, List.of())),
        List.of());
  }

  CompletableFuture<SemanticTokens> semanticTokensFuture(final String uri) {
    return routeFeature(
        uri,
        moduleWorker ->
            moduleWorker
                .semanticTokens(uri)
                .thenApply(WorkspaceSession::encodeTokensOrNull)
                .exceptionally(ex -> logAndReturn(ex, "[semanticTokens] failed for " + uri, null)),
        null);
  }

  List<? extends TextEdit> format(final String tag, final String uri) {
    LOG.fine(() -> "[%s] %s".formatted(tag, uri));
    final var openFile = openFiles.get(uri);
    return JavaFormatter.format(openFile != null ? openFile.content() : null);
  }

  private void checkForChanges() {
    if (watcher != null && watcher.poll()) {
      reload();
    }
  }

  private void reload() {
    final var newManifest = WorkspaceManifest.load(workspaceRoot);
    final var newWorkspace = ModuleWorkspace.scan(workspaceRoot, newManifest);
    final var old = workspace;
    workspace = newWorkspace;
    manifest = newManifest;
    refreshOpenFileGenerations();
    old.close();
    scheduleAllOpenFiles();
  }

  private void compileAndPublish(final OpenFile snapshot, final CompileMode mode) {
    submitCompile(snapshot, mode, this::publishIfCurrent);
  }

  private void submitCompile(
      final OpenFile snapshot, final CompileMode mode, final AfterCompile afterCompile) {
    submitCompile(routeCompiler(snapshot.uri()), snapshot, mode, afterCompile);
  }

  private void submitCompile(
      final CompilerRoute route,
      final OpenFile snapshot,
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
      final ModuleWorker moduleWorker,
      final OpenFile snapshot,
      final CompileMode mode,
      final AfterCompile afterCompile) {
    final var request =
        new CompileRequest(snapshot.uri(), snapshot.content(), snapshot.generation(), mode);
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
        .moduleFor(path)
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
      final Function<ModuleWorker, CompletableFuture<T>> operation,
      final T missingFallback) {
    return switch (routeCompiler(uri)) {
      case CompilerRoute.Module module -> operation.apply(module.worker());
      case CompilerRoute.External external -> operation.apply(external.worker());
      case CompilerRoute.Missing ignored -> CompletableFuture.completedFuture(missingFallback);
    };
  }

  private void publishMissingDiagnostic(final CompilerRoute.Missing missing) {
    LOG.fine(() -> "[compile] no module for %s".formatted(missing.uri()));
    client.publishDiagnostics(
        singleDiag(missing.uri(), missing.message(), DiagnosticSeverity.Warning));
  }

  private boolean publishIfCurrent(final OpenFile snapshot, final CompileResult result) {
    if (isStale(snapshot, result.generation())) {
      return false;
    }

    client.publishDiagnostics(new PublishDiagnosticsParams(result.uri(), result.diagnostics()));
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

  private boolean isStale(final OpenFile snapshot, final long generation) {
    final var current = openFiles.get(snapshot.uri());
    return current == null || current.generation() != generation;
  }

  private void scheduleOpenFilesInModule(final String savedUri, final ModuleConfig savedModule) {
    LOG.fine(
        () ->
            "[save] checking %d open file(s) for dependents of %s"
                .formatted(openFiles.size(), savedUri));
    openFiles.values().stream()
        .map(OpenFile::uri)
        .filter(uri -> !uri.equals(savedUri))
        .filter(
            uri ->
                workspace
                    .moduleFor(toPath(uri))
                    .map(m -> m.moduleDir().equals(savedModule.moduleDir()))
                    .orElse(false))
        .forEach(this::scheduleOpenFile);
  }

  private void scheduleAllOpenFiles() {
    openFiles.values().stream().map(OpenFile::uri).toList().forEach(this::scheduleOpenFile);
  }

  private void scheduleOpenFile(final String uri) {
    worker.schedule(
        uri,
        0L,
        () -> {
          final var openFile = openFiles.get(uri);
          if (openFile != null) {
            compileAndPublish(openFile, CompileMode.OPEN);
          }
        });
  }

  private OpenFile snapshotForSave(final String uri, final String savedContent) {
    final var openFile = openFiles.get(uri);
    if (openFile == null) {
      return null;
    }

    return savedContent != null ? putOpenFile(uri, savedContent) : openFile;
  }

  private OpenFile putOpenFile(final String uri, final String content) {
    final var openFile = new OpenFile(uri, content, nextGeneration());
    openFiles.put(uri, openFile);
    return openFile;
  }

  private void refreshOpenFileGenerations() {
    openFiles.replaceAll(
        (uri, openFile) -> new OpenFile(uri, openFile.content(), nextGeneration()));
  }

  private long nextGeneration() {
    return ++nextGeneration;
  }

  private void publishCompileError(
      final OpenFile snapshot, final CompileMode mode, final Throwable ex) {
    if (isStale(snapshot, snapshot.generation())) {
      return;
    }

    LOG.log(SEVERE, ex, () -> "[%s] failed for %s".formatted(mode.tag, snapshot.uri()));
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
    record Module(ModuleWorker worker, ModuleConfig config) implements CompilerRoute {}

    record External(ModuleWorker worker) implements CompilerRoute {}

    record Missing(String uri, String message) implements CompilerRoute {}
  }

  @FunctionalInterface
  private interface AfterCompile {
    void accept(OpenFile snapshot, CompileResult result);
  }

  private record OpenFile(String uri, String content, long generation) {}
}
