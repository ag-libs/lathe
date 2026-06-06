package io.github.aglibs.lathe.server;

import static java.util.logging.Level.SEVERE;

import io.github.aglibs.lathe.core.typeindex.ClassFileTypeScanner;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.ReferenceTarget;
import io.github.aglibs.lathe.server.analysis.SemanticToken;
import io.github.aglibs.lathe.server.analysis.SourceFeatureRequest;
import io.github.aglibs.lathe.server.analysis.TokenScanner;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.server.analysis.completion.CompletionOutcome;
import io.github.aglibs.lathe.server.module.CompileRequest;
import io.github.aglibs.lathe.server.module.CompileResponse;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.lathe.server.module.ModuleSourceWorker;
import io.github.aglibs.lathe.server.module.WorkspaceModuleGraph;
import io.github.aglibs.lathe.server.module.WorkspaceModules;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
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
  private WorkspaceModuleGraph moduleGraph = WorkspaceModuleGraph.build(List.of());
  private ReferenceCandidateIndex candidateIndex = ReferenceCandidateIndex.build(List.of());
  private WorkspaceTypeIndex typeIndex = WorkspaceTypeIndex.empty();
  private final Map<ModuleSourceConfig, List<TypeIndexEntry>> reactorShards = new LinkedHashMap<>();
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
    workspace = WorkspaceModules.scan(root, manifest);
    moduleGraph = WorkspaceModuleGraph.build(workspace.allConfigs());
    candidateIndex = ReferenceCandidateIndex.build(workspace.allConfigs());
    scanReactorShards();
    typeIndex = WorkspaceTypeIndex.build(manifest.typeIndexShardPaths(), reactorShards.values());
    watcher = new WorkspaceWatcher(root);
    worker.scheduleAtFixedRate(2_000L, this::checkForChanges);
  }

  void close() {
    workspace.close();
  }

  void onOpen(final String uri, final String content, final int version) {
    final var snapshot = putOpenFile(uri, content, version);
    LOG.info(() -> "[open] %s".formatted(uri));
    candidateIndex.update(uri, content);
    compileAndPublish(snapshot, CompileMode.OPEN);
  }

  void onChange(final String uri, final String content, final int version) {
    putOpenFile(uri, content, version);
    LOG.fine(() -> "[change] %s".formatted(uri));
    candidateIndex.update(uri, content);
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
    reindexFromDisk(uri);
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
                    refreshReactorShard(module.config());
                  });
          case CompilerRoute.External ignored ->
              publishIfCurrentThen(() -> scheduleAstRefresh(uri));
          case CompilerRoute.Missing ignored -> this::publishIfCurrent;
        };
    submitCompile(route, snapshot, CompileMode.FULL, afterCompile);
  }

  void onDeletedFile(final String uri) {
    LOG.info(() -> "[delete] %s".formatted(uri));
    worker.cancel(uri);
    openDocuments.remove(uri);
    workspace.dropFromAllCaches(uri);
    candidateIndex.remove(uri);
    client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));

    final var deletedFile = toPath(uri);
    workspace
        .moduleSourceFor(deletedFile)
        .ifPresent(
            config -> {
              deleteClassOutputs(config, deletedFile);
              refreshReactorShard(config);
              scheduleOpenFilesInModule(uri, config);
            });
  }

  CompletableFuture<List<Location>> referencesFuture(
      final String uri, final Position pos, final boolean includeDeclaration) {
    final var openFile = openDocuments.get(uri);
    if (openFile == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    final var request =
        new SourceFeatureRequest(
            openFile.uri(), openFile.content(), pos, workspace.allSourceRoots(), manifest);

    final var cursorWorker =
        switch (routeCompiler(uri)) {
          case CompilerRoute.Module m -> m.worker();
          case CompilerRoute.External e -> e.worker();
          case CompilerRoute.Missing ignored -> null;
        };
    if (cursorWorker == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    // Capture declaring module synchronously on the lathe-worker thread before any async hand-off
    final var cursorConfig = workspace.moduleSourceFor(toPath(uri));

    return cursorWorker
        .resolveTarget(request)
        .thenCompose(
            target -> {
              if (target == null) {
                return CompletableFuture.completedFuture(List.of());
              }

              if (target.scope() == ReferenceTarget.SearchScope.DECLARING_FILE) {
                return cursorWorker.searchReferences(
                    openFile.uri(),
                    openFile.content(),
                    openFile.version(),
                    target,
                    includeDeclaration);
              }

              final Path packageRel =
                  target.scope() == ReferenceTarget.SearchScope.DECLARING_MODULE
                      ? declaringPackageRel(toPath(uri), cursorConfig.orElse(null))
                      : null;

              final List<ModuleSourceConfig> configs =
                  target.scope() == ReferenceTarget.SearchScope.REACTOR_MODULES
                      ? cursorConfig.map(moduleGraph::referenceSearchScope).orElse(List.of())
                      : cursorConfig
                          .map(c -> moduleGraph.configsForModule(c.moduleDir()))
                          .orElse(List.of());

              return configs.stream()
                  .flatMap(config -> searchFutures(config, target, includeDeclaration, packageRel))
                  .reduce(
                      CompletableFuture.completedFuture(List.of()),
                      (f1, f2) ->
                          f1.thenCombine(
                              f2, (a, b) -> Stream.concat(a.stream(), b.stream()).toList()));
            })
        .exceptionally(ex -> logAndReturn(ex, "[references] failed for " + uri, List.of()));
  }

  private Stream<CompletableFuture<List<Location>>> searchFutures(
      final ModuleSourceConfig config,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final Path packageRel) {
    final var worker = workspace.workerFor(config);
    final List<OpenDocument> openForConfig =
        openDocuments.values().stream()
            .filter(
                doc ->
                    workspace
                        .moduleSourceFor(toPath(doc.uri()))
                        .map(c -> c.equals(config))
                        .orElse(false))
            .filter(doc -> isInPackageScope(toPath(doc.uri()), config.sourceRoots(), packageRel))
            .toList();
    final Set<String> openUrisForConfig =
        openForConfig.stream().map(OpenDocument::uri).collect(Collectors.toUnmodifiableSet());
    final List<Path> sourceRoots = config.sourceRoots();
    final List<DiskCandidate> diskFiles =
        candidateIndex.candidateUris(target.simpleName()).stream()
            .filter(uri -> !openUrisForConfig.contains(uri))
            .filter(uri -> isInPackageScope(toPath(uri), sourceRoots, packageRel))
            .flatMap(uri -> readDiskCandidate(uri).stream())
            .toList();
    return Stream.concat(
        openForConfig.stream()
            .map(
                doc ->
                    worker.searchReferences(
                        doc.uri(), doc.content(), doc.version(), target, includeDeclaration)),
        diskFiles.stream()
            .map(
                d -> worker.searchReferences(d.uri(), d.content(), 0, target, includeDeclaration)));
  }

  private static Path declaringPackageRel(final Path cursorPath, final ModuleSourceConfig config) {
    if (config == null) {
      return null;
    }

    return config.sourceRoots().stream()
        .filter(cursorPath::startsWith)
        .map(root -> root.relativize(cursorPath.getParent()))
        .findFirst()
        .orElse(null);
  }

  private static boolean isInPackageScope(
      final Path path, final List<Path> sourceRoots, final Path packageRel) {
    if (packageRel == null) {
      return sourceRoots.stream().anyMatch(path::startsWith);
    }

    return sourceRoots.stream().anyMatch(root -> path.startsWith(root.resolve(packageRel)));
  }

  private void reindexFromDisk(final String uri) {
    final var path = toPath(uri);
    if (Files.exists(path)) {
      try {
        candidateIndex.update(uri, Files.readString(path));
      } catch (final IOException e) {
        LOG.log(Level.WARNING, e, () -> "[candidate-index] re-index failed for " + uri);
        candidateIndex.remove(uri);
      }
    } else {
      candidateIndex.remove(uri);
    }
  }

  private static Optional<DiskCandidate> readDiskCandidate(final String uri) {
    try {
      return Optional.of(new DiskCandidate(uri, Files.readString(toPath(uri))));
    } catch (final IOException e) {
      LOG.log(Level.FINE, e, () -> "[references] failed to read candidate: " + uri);
      return Optional.empty();
    }
  }

  private record DiskCandidate(String uri, String content) {}

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

    final var indexSnapshot = typeIndex;
    return routeFeature(
        uri,
        moduleWorker ->
            moduleWorker
                .complete(uri, openFile.content(), openFile.version(), pos, context, indexSnapshot)
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

  private void scanReactorShards() {
    for (final var config : workspace.allConfigs()) {
      reactorShards.put(config, scanReactorDir(config));
    }
  }

  private void refreshReactorShard(final ModuleSourceConfig config) {
    reactorShards.put(config, scanReactorDir(config));
    typeIndex = WorkspaceTypeIndex.build(manifest.typeIndexShardPaths(), reactorShards.values());
  }

  private static List<TypeIndexEntry> scanReactorDir(final ModuleSourceConfig config) {
    try {
      return ClassFileTypeScanner.scanDirectory(config.latheClassesDir());
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING, e, () -> "[type-index] reactor scan failed: " + config.latheClassesDir());
      return List.of();
    }
  }

  static int deleteClassOutputs(final ModuleSourceConfig config, final Path deletedSource) {
    if (!deletedSource.getFileName().toString().endsWith(".java")) {
      return 0;
    }

    final var sourceRoot = sourceRootFor(config, deletedSource);
    if (sourceRoot == null) {
      return 0;
    }

    final var rel = sourceRoot.relativize(deletedSource);
    final var packageRel = rel.getParent();
    final var classDir =
        packageRel != null
            ? config.latheClassesDir().resolve(packageRel)
            : config.latheClassesDir();
    if (!Files.isDirectory(classDir)) {
      return 0;
    }

    final var sourceName = deletedSource.getFileName().toString();
    final var typeName = sourceName.substring(0, sourceName.length() - ".java".length());
    try (final var stream = Files.list(classDir)) {
      final var matchingClassFiles =
          stream.filter(path -> deletedClassFile(typeName, path)).toList();
      for (final var classFile : matchingClassFiles) {
        Files.deleteIfExists(classFile);
      }
      return matchingClassFiles.size();
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING, e, () -> "[delete] class cleanup failed for %s".formatted(deletedSource));
      return 0;
    }
  }

  private static Path sourceRootFor(final ModuleSourceConfig config, final Path file) {
    return config.sourceRoots().stream()
        .filter(file::startsWith)
        .max(Comparator.comparingInt(Path::getNameCount))
        .orElse(null);
  }

  private static boolean deletedClassFile(final String typeName, final Path path) {
    final var name = path.getFileName().toString();
    return name.equals(typeName + ".class")
        || (name.startsWith(typeName + "$") && name.endsWith(".class"));
  }

  private void checkForChanges() {
    if (watcher != null && watcher.poll()) {
      reload();
    }
  }

  private void reload() {
    LOG.info(() -> "[reload] workspace changed, reloading");
    final var newManifest = WorkspaceManifest.load(workspaceRoot);
    final var newWorkspace = WorkspaceModules.scan(workspaceRoot, newManifest);
    final var old = workspace;
    workspace = newWorkspace;
    manifest = newManifest;
    moduleGraph = WorkspaceModuleGraph.build(workspace.allConfigs());
    candidateIndex = ReferenceCandidateIndex.build(workspace.allConfigs());
    reactorShards.clear();
    scanReactorShards();
    typeIndex = WorkspaceTypeIndex.build(newManifest.typeIndexShardPaths(), reactorShards.values());
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
