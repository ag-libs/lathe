package io.github.aglibs.lathe.server;

import static java.util.logging.Level.SEVERE;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.typeindex.ClassFileTypeScanner;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.analysis.CodeActionRequest;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.DiagnosticPayload;
import io.github.aglibs.lathe.server.analysis.ReferenceMatch;
import io.github.aglibs.lathe.server.analysis.ReferenceTarget;
import io.github.aglibs.lathe.server.analysis.SemanticToken;
import io.github.aglibs.lathe.server.analysis.SourceFeatureRequest;
import io.github.aglibs.lathe.server.analysis.TokenScanner;
import io.github.aglibs.lathe.server.analysis.WorkspaceSymbolResolver;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.server.analysis.completion.CompletionOutcome;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import io.github.aglibs.lathe.server.module.CompileRequest;
import io.github.aglibs.lathe.server.module.CompileResponse;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.lathe.server.module.WorkspaceModuleGraph;
import io.github.aglibs.lathe.server.module.WorkspaceModuleRegistry;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
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
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

/** Not thread-safe. All methods must be called from the {@link ServerEventLoop} thread. */
final class WorkspaceSession {

  private static final Logger LOG = Logger.getLogger(WorkspaceSession.class.getName());

  private final LanguageClient client;
  private final ServerEventLoop worker;
  private final long debounceMs;
  private Path workspaceRoot;
  private WorkspaceManifest manifest = WorkspaceManifest.empty();
  private WorkspaceModuleRegistry workspace = WorkspaceModuleRegistry.empty();
  private WorkspaceModuleGraph moduleGraph = WorkspaceModuleGraph.build(List.of());
  private ReferenceCandidateIndex candidateIndex = ReferenceCandidateIndex.build(List.of());
  private WorkspaceTypeIndex typeIndex = WorkspaceTypeIndex.empty();
  private final Map<ModuleSourceConfig, List<TypeIndexEntry>> reactorShards = new LinkedHashMap<>();
  private WorkspaceWatcher watcher;
  private boolean pomNotificationPending;
  private final DocumentRegistry docs = new DocumentRegistry();
  private final DiagnosticPublisher publisher;

  WorkspaceSession(
      final LanguageClient client, final ServerEventLoop worker, final long debounceMs) {
    this.client = client;
    this.worker = worker;
    this.debounceMs = debounceMs;
    this.publisher = new DiagnosticPublisher(client, docs);
  }

  void initialize(final Path root) {
    this.workspaceRoot = root;
    final boolean configured = Files.isDirectory(root.resolve(LatheLayout.LATHE_DIR));
    manifest = WorkspaceManifest.load(root);
    workspace = WorkspaceModuleRegistry.scan(root, manifest);
    moduleGraph = WorkspaceModuleGraph.build(workspace.allConfigs());
    candidateIndex = ReferenceCandidateIndex.build(workspace.allConfigs());
    scanReactorShards();
    typeIndex = WorkspaceTypeIndex.build(manifest.typeIndexShardPaths(), reactorShards.values());
    watcher = new WorkspaceWatcher(root);
    watcher.updatePomPaths(manifest.pomPaths());
    worker.scheduleAtFixedRate(2_000L, this::checkForChanges);
    if (configured) {
      client.showMessage(new MessageParams(MessageType.Info, "Lathe: workspace ready."));
    } else {
      client.showMessage(
          new MessageParams(
              MessageType.Warning,
              "Lathe: not configured — run `mvn process-test-classes` to set up this project."));
    }
  }

  void close() {
    workspace.close();
  }

  void onOpen(final String uri, final String content, final int version) {
    final var snapshot = docs.put(uri, content, version);
    LOG.info(() -> "[open] %s".formatted(uri));
    candidateIndex.update(uri, content);
    compileAndPublish(snapshot, CompileMode.OPEN);
  }

  void onChange(final String uri, final String content, final int version) {
    docs.put(uri, content, version);
    LOG.fine(() -> "[change] %s".formatted(uri));
    candidateIndex.update(uri, content);
    publisher.publishEmpty(uri);
    worker.cancel(uri);
    worker.schedule(
        uri,
        debounceMs,
        () -> {
          final var latest = docs.get(uri);
          if (latest != null) {
            compileAndPublish(latest, CompileMode.FAST);
          }
        });
  }

  void onClose(final String uri) {
    docs.remove(uri);
    LOG.info(() -> "[close] %s".formatted(uri));
    worker.cancel(uri);
    workspace.dropFromAllCaches(uri);
    publisher.publishEmpty(uri);
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
          case CompilerRoute.Missing ignored -> publisher::publishIfCurrent;
        };
    submitCompile(route, snapshot, CompileMode.FULL, afterCompile);
  }

  void onDeletedFile(final String uri) {
    LOG.info(() -> "[delete] %s".formatted(uri));
    worker.cancel(uri);
    docs.remove(uri);
    workspace.dropFromAllCaches(uri);
    candidateIndex.remove(uri);
    publisher.publishEmpty(uri);

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
    final var openFile = docs.get(uri);
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

    final Stopwatch t = Stopwatch.start();
    final var targetName = new AtomicReference<String>();
    return cursorWorker
        .resolveTarget(request)
        .thenCompose(
            target -> {
              if (target == null) {
                return CompletableFuture.completedFuture(List.of());
              }

              targetName.set(target.simpleName());

              if (target.scope() == ReferenceTarget.SearchScope.DECLARING_FILE) {
                return cursorWorker
                    .searchReferences(
                        openFile.uri(),
                        openFile.content(),
                        openFile.version(),
                        target,
                        includeDeclaration)
                    .thenApply(WorkspaceSession::toLocations);
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
        .thenApply(
            locations -> {
              final var name = targetName.get();
              if (name != null) {
                LOG.info(
                    () ->
                        "[references] %s %dms target=%s hits=%d"
                            .formatted(uri, t.elapsedMs(), name, locations.size()));
              }
              return locations;
            })
        .exceptionally(
            ex -> logAndReturn(ex, "[references] failed for %s".formatted(uri), List.of()));
  }

  private Stream<CompletableFuture<List<Location>>> searchFutures(
      final ModuleSourceConfig config,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final Path packageRel) {
    final var worker = workspace.workerFor(config);
    final List<OpenDocument> openForConfig =
        docs.all().stream()
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
    final var planner = new ReferenceCandidatePlanner(candidateIndex);
    final List<DiskCandidate> diskFiles =
        planner.planCandidates(config, target).stream()
            .filter(uri -> !openUrisForConfig.contains(uri))
            .filter(uri -> isInPackageScope(toPath(uri), sourceRoots, packageRel))
            .flatMap(uri -> readDiskCandidate(uri).stream())
            .toList();
    return Stream.concat(
        openForConfig.stream()
            .map(
                doc ->
                    worker
                        .searchReferences(
                            doc.uri(), doc.content(), doc.version(), target, includeDeclaration)
                        .thenApply(WorkspaceSession::toLocations)),
        diskFiles.stream()
            .map(
                d ->
                    worker
                        .searchReferences(d.uri(), d.content(), 0, target, includeDeclaration)
                        .thenApply(WorkspaceSession::toLocations)));
  }

  private static List<Location> toLocations(final List<ReferenceMatch> matches) {
    return matches.stream().map(ReferenceMatch::toLocation).toList();
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
        LOG.log(Level.WARNING, e, () -> "[candidate-index] re-index failed for %s".formatted(uri));
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
      LOG.log(Level.FINE, e, () -> "[references] failed to read candidate: %s".formatted(uri));
      return Optional.empty();
    }
  }

  private record DiskCandidate(String uri, String content) {}

  CompletableFuture<Hover> hoverFuture(final String uri, final Position pos) {
    final var openFile = docs.get(uri);
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
                .exceptionally(
                    ex -> logAndReturn(ex, "[hover] failed for %s".formatted(uri), null)),
        null);
  }

  CompletableFuture<SignatureHelp> signatureHelpFuture(final String uri, final Position pos) {
    final var openFile = docs.get(uri);
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
                .signatureHelp(request)
                .exceptionally(
                    ex -> logAndReturn(ex, "[signatureHelp] failed for %s".formatted(uri), null)),
        null);
  }

  CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      definitionFuture(final String uri, final Position pos) {
    final var openFile = docs.get(uri);
    if (openFile == null) {
      return CompletableFuture.completedFuture(Either.forLeft(List.of()));
    }

    LOG.info(
        () ->
            "[definition] %s line=%d character=%d"
                .formatted(uri, pos.getLine(), pos.getCharacter()));
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
                            ex,
                            "[definition] failed for %s".formatted(uri),
                            Either.forLeft(List.of()))),
        Either.forLeft(List.of()));
  }

  CompletableFuture<CompletionOutcome> completionFuture(
      final String uri, final Position pos, final CompletionContext context) {
    final var openFile = docs.get(uri);
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
                            ex,
                            "[completion] failed for %s".formatted(uri),
                            CompletionOutcome.of(List.of()))),
        CompletionOutcome.of(List.of()));
  }

  CompletableFuture<List<Either<Command, CodeAction>>> codeActionFuture(
      final String uri, final CodeActionContext context) {
    final var openFile = docs.get(uri);
    if (openFile == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    final List<CodeActionRequest> requests =
        context.getDiagnostics().stream()
            .map(diag -> toCodeActionRequest(uri, diag))
            .filter(Objects::nonNull)
            .toList();
    if (requests.isEmpty()) {
      return CompletableFuture.completedFuture(List.of());
    }

    final var indexSnapshot = typeIndex;
    return routeFeature(
        uri,
        moduleWorker ->
            moduleWorker
                .codeAction(uri, openFile.content(), openFile.version(), requests, indexSnapshot)
                .exceptionally(
                    ex -> logAndReturn(ex, "[codeAction] failed for %s".formatted(uri), List.of())),
        List.of());
  }

  private static CodeActionRequest toCodeActionRequest(final String uri, final Diagnostic diag) {
    final DiagnosticPayload payload = DiagnosticPayloadCodec.extractPayload(diag.getData());
    return payload != null ? new CodeActionRequest(uri, diag, payload) : null;
  }

  CompletableFuture<SemanticTokens> semanticTokensFuture(final String uri) {
    final var openFile = docs.get(uri);
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
                .exceptionally(
                    ex -> logAndReturn(ex, "[semanticTokens] failed for %s".formatted(uri), null)),
        null);
  }

  CompletableFuture<List<DocumentSymbol>> documentSymbolFuture(final String uri) {
    final var openFile = docs.get(uri);
    if (openFile == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    return routeFeature(
        uri,
        moduleWorker ->
            moduleWorker
                .documentSymbol(uri, openFile.content())
                .exceptionally(
                    ex ->
                        logAndReturn(
                            ex, "[documentSymbol] failed for %s".formatted(uri), List.of())),
        List.of());
  }

  CompletableFuture<List<FoldingRange>> foldingRangeFuture(final String uri) {
    final var openFile = docs.get(uri);
    if (openFile == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    return routeFeature(
        uri,
        moduleWorker ->
            moduleWorker
                .foldingRange(uri, openFile.content())
                .exceptionally(
                    ex ->
                        logAndReturn(ex, "[foldingRange] failed for %s".formatted(uri), List.of())),
        List.of());
  }

  List<? extends TextEdit> format(final String tag, final String uri) {
    final var t = Stopwatch.start();
    final var openFile = docs.get(uri);
    final var result = JavaFormatter.format(openFile != null ? openFile.content() : null);
    LOG.info(() -> "[%s] %s %dms edits=%d".formatted(tag, uri, t.elapsedMs(), result.size()));
    return result;
  }

  List<SymbolInformation> workspaceSymbol(final String query) {
    final var t = Stopwatch.start();
    final List<Path> sourceDirs =
        Stream.of(
                workspace.allSourceRoots().stream(),
                manifest.jdkModuleSourceDirs().stream(),
                manifest.depSourceDirs().stream())
            .flatMap(s -> s)
            .toList();
    final List<SymbolInformation> results =
        WorkspaceSymbolResolver.resolve(query, typeIndex, sourceDirs);
    LOG.info(
        () -> "[symbol] query=%s hits=%d %dms".formatted(query, results.size(), t.elapsedMs()));
    return results;
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
          Level.WARNING,
          e,
          () -> "[type-index] reactor scan failed: %s".formatted(config.latheClassesDir()));
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
    if (watcher == null) {
      return;
    }

    switch (watcher.poll()) {
      case WORKSPACE_CHANGED -> reload();
      case POM_CHANGED -> {
        if (!pomNotificationPending) {
          pomNotificationPending = true;
          final var request =
              new ShowMessageRequestParams(
                  List.of(new MessageActionItem("Sync"), new MessageActionItem("Later")));
          request.setMessage(
              "Maven project changed. Run 'mvn process-test-classes' to refresh Lathe.");
          request.setType(MessageType.Warning);
          client
              .showMessageRequest(request)
              .thenAccept(action -> worker.execute(() -> pomNotificationPending = false));
        }
      }
      case NO_CHANGE -> {}
    }
  }

  private void reload() {
    LOG.info(() -> "[reload] workspace changed, reloading");
    final var newManifest = WorkspaceManifest.load(workspaceRoot);
    if (watcher != null) {
      watcher.updatePomPaths(newManifest.pomPaths());
      pomNotificationPending = false;
    }

    final var newWorkspace = WorkspaceModuleRegistry.scan(workspaceRoot, newManifest);
    final var old = workspace;
    workspace = newWorkspace;
    manifest = newManifest;
    moduleGraph = WorkspaceModuleGraph.build(workspace.allConfigs());
    candidateIndex = ReferenceCandidateIndex.build(workspace.allConfigs());
    reactorShards.clear();
    scanReactorShards();
    typeIndex = WorkspaceTypeIndex.build(newManifest.typeIndexShardPaths(), reactorShards.values());
    List.copyOf(docs.uris())
        .forEach(
            uri -> {
              final var f = docs.get(uri);
              docs.put(uri, f.content(), f.version());
            });
    old.close();
    scheduleAllOpenFiles();
    client.showMessage(new MessageParams(MessageType.Info, "Lathe: workspace reloaded."));
  }

  private void compileAndPublish(final OpenDocument snapshot, final CompileMode mode) {
    submitCompile(snapshot, mode, publisher::publishIfCurrent);
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
      case CompilerRoute.Missing missing ->
          publisher.publishMissing(missing.uri(), missing.message());
    }
  }

  private void submitTo(
      final CompilationWorker moduleWorker,
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
              worker.execute(() -> publisher.publishError(snapshot, mode, ex));
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
      final Function<CompilationWorker, CompletableFuture<T>> operation,
      final T missingFallback) {
    return switch (routeCompiler(uri)) {
      case CompilerRoute.Module module -> operation.apply(module.worker());
      case CompilerRoute.External external -> operation.apply(external.worker());
      case CompilerRoute.Missing ignored -> CompletableFuture.completedFuture(missingFallback);
    };
  }

  private AfterCompile publishIfCurrentThen(final Runnable followUp) {
    return (snapshot, result) -> {
      if (publisher.publishIfCurrent(snapshot, result)) {
        followUp.run();
      }
    };
  }

  private void scheduleOpenFilesInModule(
      final String savedUri, final ModuleSourceConfig savedModule) {
    LOG.fine(
        () ->
            "[save] checking %d open file(s) for dependents of %s"
                .formatted(docs.all().size(), savedUri));
    docs.all().stream()
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
    docs.all().stream().map(OpenDocument::uri).toList().forEach(this::scheduleOpenFile);
  }

  private void scheduleOpenFile(final String uri) {
    worker.schedule(
        uri,
        0L,
        () -> {
          final var openFile = docs.get(uri);
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
          final var openFile = docs.get(uri);
          if (openFile != null) {
            submitCompile(openFile, CompileMode.FAST, publisher::refreshTokensIfCurrent);
          }
        });
  }

  private OpenDocument snapshotForSave(final String uri, final String savedContent) {
    final var openFile = docs.get(uri);
    if (openFile == null) {
      return null;
    }

    return savedContent != null ? docs.put(uri, savedContent, openFile.version()) : openFile;
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

  private sealed interface CompilerRoute {
    record Module(CompilationWorker worker, ModuleSourceConfig config) implements CompilerRoute {}

    record External(CompilationWorker worker) implements CompilerRoute {}

    record Missing(String uri, String message) implements CompilerRoute {}
  }

  @FunctionalInterface
  private interface AfterCompile {
    void accept(OpenDocument snapshot, CompileResponse result);
  }
}
