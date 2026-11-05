package io.github.aglibs.lathe.server;

import static java.util.logging.Level.SEVERE;

import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.CallHierarchyItemData;
import io.github.aglibs.lathe.analysis.CallHierarchyItemDataCodec;
import io.github.aglibs.lathe.analysis.MethodImplementationLocator;
import io.github.aglibs.lathe.analysis.ReferenceMatch;
import io.github.aglibs.lathe.analysis.ReferenceTarget;
import io.github.aglibs.lathe.analysis.SourceFeatureRequest;
import io.github.aglibs.lathe.analysis.TransientSource;
import io.github.aglibs.lathe.analysis.TypeSourceLocator;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.core.CollectionUtil;
import io.github.aglibs.lathe.core.ExceptionUtil;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// Finds what refers to a symbol across the workspace: references, instantiations, highlights,
// implementations and callers. A search plans on the event loop from the workspace and its indexes,
// reads the candidate files off it, and searches each module's candidates on that module's worker,
// open documents from their editor content.
final class ReferenceSearch {

  private static final Logger LOG = Logger.getLogger(ReferenceSearch.class.getName());

  private static final int REFERENCE_BATCH_SIZE = 8;

  private final ServerEventLoop eventLoop;
  private final WorkspaceState state;
  private final ReactorTypeIndex reactorIndex;
  private final FeatureRouter router;
  private final DocumentRegistry docs;

  ReferenceSearch(
      final ServerEventLoop eventLoop,
      final WorkspaceState state,
      final ReactorTypeIndex reactorIndex,
      final FeatureRouter router,
      final DocumentRegistry docs) {
    this.eventLoop = eventLoop;
    this.state = state;
    this.reactorIndex = reactorIndex;
    this.router = router;
    this.docs = docs;
  }

  CompletableFuture<List<Location>> referencesFuture(
      final String uri,
      final Position pos,
      final boolean includeDeclaration,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    final Optional<FeatureRouter.OpenRoute> route = router.openRoute(uri);
    if (route.isEmpty()) {
      return CompletableFuture.completedFuture(List.of());
    }

    final OpenDocument openFile = route.get().doc();
    final CompilationWorker cursorWorker = route.get().worker();
    final ModuleSourceConfig cursorConfig = route.get().config();
    final var request = router.featureRequest(openFile, pos);

    final var t = Stopwatch.start();
    final var targetName = new AtomicReference<String>();
    return cursorWorker
        .resolveTarget(request, cancelChecker)
        .thenCompose(
            target -> {
              cancelChecker.checkCanceled();
              if (target == null) {
                return CompletableFuture.completedFuture(List.of());
              }

              return referenceSearchTarget(cursorWorker, request, target, cancelChecker)
                  .thenCompose(
                      searchTarget -> {
                        targetName.set(searchTarget.simpleName());
                        return searchReferencesForTarget(
                                openFile,
                                cursorWorker,
                                cursorConfig,
                                searchTarget,
                                includeDeclaration,
                                cancelChecker,
                                progress,
                                "Finding references to %s".formatted(searchTarget.simpleName()))
                            .thenCompose(
                                matches ->
                                    withGeneratedAccessors(
                                        matches,
                                        openFile,
                                        cursorWorker,
                                        cursorConfig,
                                        request,
                                        cancelChecker,
                                        progress));
                      });
            })
        .thenApply(
            matches -> {
              cancelChecker.checkCanceled();
              final var name = targetName.get();
              if (name != null) {
                LOG.info(
                    () ->
                        "[references] %s %dms target=%s hits=%d"
                            .formatted(uri, t.elapsedMs(), name, matches.size()));
              }
              return toLocations(matches);
            })
        .exceptionally(
            ex -> {
              if (ExceptionUtil.completionCause(ex) instanceof CancellationException cancellation) {
                throw cancellation;
              }
              LOG.log(
                  SEVERE,
                  ex,
                  () ->
                      "[references] %s target=%s %dms failed"
                          .formatted(uri, targetName.get(), t.elapsedMs()));
              throw ExceptionUtil.asCompletion(ex);
            });
  }

  CompletableFuture<List<Location>> instantiationsFuture(
      final String uri,
      final Position pos,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    final Optional<FeatureRouter.OpenRoute> route = router.openRoute(uri);
    if (route.isEmpty()) {
      return CompletableFuture.completedFuture(List.of());
    }

    final OpenDocument openFile = route.get().doc();
    final CompilationWorker cursorWorker = route.get().worker();
    final ModuleSourceConfig cursorConfig = route.get().config();
    final var request = router.featureRequest(openFile, pos);
    final var t = Stopwatch.start();

    return cursorWorker
        .instantiationTargets(request, cancelChecker)
        .thenCompose(
            targets -> {
              cancelChecker.checkCanceled();
              if (targets.isEmpty()) {
                return CompletableFuture.completedFuture(List.<Location>of());
              }

              // One reference search per constructor overload, unioned -- each match sits at the
              // `new XXX` identifier, so these are exactly the instantiation sites. All overloads
              // share the one progress task the caller finishes, so a multi-ctor type shows one
              // bar.
              final List<CompletableFuture<List<ReferenceMatch>>> searches =
                  targets.stream()
                      .map(
                          target ->
                              searchReferencesForTarget(
                                  openFile,
                                  cursorWorker,
                                  cursorConfig,
                                  target,
                                  false,
                                  cancelChecker,
                                  progress,
                                  "Finding instantiation sites"))
                      .toList();
              return joinCandidateResults(searches, cancelChecker)
                  .thenApply(ReferenceSearch::toLocations);
            })
        .thenApply(
            locations -> {
              cancelChecker.checkCanceled();
              LOG.info(
                  () ->
                      "[instantiations] %s %dms hits=%d"
                          .formatted(uri, t.elapsedMs(), locations.size()));
              return locations;
            });
  }

  CompletableFuture<ReferenceTarget> referenceSearchTarget(
      final CompilationWorker cursorWorker,
      final SourceFeatureRequest request,
      final ReferenceTarget target,
      final CancelChecker cancelChecker) {
    if (target.kind() != ElementKind.METHOD) {
      return CompletableFuture.completedFuture(target);
    }

    return cursorWorker
        .resolveContractTarget(request, cancelChecker)
        .thenApply(contract -> contract != null ? contract : target);
  }

  // A field Lombok generated accessors from is used through them too: their calls join its own
  // matches. The progress already ended with the field's search; the accessors' run quietly.
  private CompletableFuture<List<ReferenceMatch>> withGeneratedAccessors(
      final List<ReferenceMatch> fieldMatches,
      final OpenDocument openFile,
      final CompilationWorker cursorWorker,
      final ModuleSourceConfig cursorConfig,
      final SourceFeatureRequest request,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    return cursorWorker
        .generatedAccessorTargets(request)
        .thenCompose(
            accessors -> {
              CompletableFuture<List<ReferenceMatch>> all =
                  CompletableFuture.completedFuture(fieldMatches);
              for (final ReferenceTarget accessor : accessors) {
                all =
                    all.thenCompose(
                        matches ->
                            searchReferencesForTarget(
                                    openFile,
                                    cursorWorker,
                                    cursorConfig,
                                    accessor,
                                    false,
                                    cancelChecker,
                                    progress,
                                    accessor.simpleName())
                                .thenApply(
                                    more ->
                                        Stream.concat(matches.stream(), more.stream())
                                            .distinct()
                                            .toList()));
              }

              return all;
            });
  }

  CompletableFuture<List<ReferenceMatch>> searchReferencesForTarget(
      final OpenDocument openFile,
      final CompilationWorker cursorWorker,
      final ModuleSourceConfig cursorConfig,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress,
      final String progressTitle) {
    if (target.scope() == ReferenceTarget.SearchScope.DECLARING_FILE) {
      progress.begin(progressTitle, 1);
      return searchOpenDocument(
          cursorWorker, openFile, target, includeDeclaration, cancelChecker, progress);
    }

    final var planningTimer = Stopwatch.start();
    return readSearchInputs(planSearch(cursorConfig, target))
        .thenCompose(
            searchInputs ->
                runSearch(
                    searchInputs,
                    openFile.uri(),
                    planningTimer,
                    target,
                    includeDeclaration,
                    cancelChecker,
                    progress,
                    progressTitle));
  }

  private List<ModuleSearchPlan> planSearch(
      final ModuleSourceConfig cursorConfig, final ReferenceTarget target) {
    final Path packageRel =
        target.scope() == ReferenceTarget.SearchScope.DECLARING_MODULE
            ? ReferenceCandidatePlanner.packageRelForQualifiedName(target.qualifiedName())
            : null;
    return planSearchScope(target, declaringConfigFor(target, cursorConfig)).stream()
        .map(config -> planModuleSearch(config, target, packageRel))
        .toList();
  }

  // Planning reads session state, so the caller plans on the event loop; reading the candidate
  // files does not, so it runs off it, where a search over thousands of files cannot stall the
  // loop. The search then routes open documents, which is loop state again, so it rejoins the loop.
  private CompletableFuture<List<ModuleSearchInputs>> readSearchInputs(
      final List<ModuleSearchPlan> plans) {
    return eventLoop.rejoin(
        CompletableFuture.supplyAsync(
            () -> plans.stream().map(ReferenceSearch::readCandidates).toList()));
  }

  private static ModuleSearchInputs readCandidates(final ModuleSearchPlan plan) {
    return new ModuleSearchInputs(
        plan.worker(),
        new WorkspaceSearchInputs(
            plan.openDocuments(),
            plan.candidateUris().stream()
                .flatMap(uri -> readDiskCandidate(uri).stream())
                .toList()));
  }

  private CompletableFuture<List<ReferenceMatch>> runSearch(
      final List<ModuleSearchInputs> searchInputs,
      final String uri,
      final Stopwatch planningTimer,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress,
      final String progressTitle) {
    final int openCount =
        searchInputs.stream()
            .map(ModuleSearchInputs::inputs)
            .mapToInt(inputs -> inputs.openDocuments().size())
            .sum();
    final int diskCount =
        searchInputs.stream()
            .map(ModuleSearchInputs::inputs)
            .mapToInt(inputs -> inputs.diskCandidates().size())
            .sum();
    final int maxModuleCandidates =
        searchInputs.stream()
            .map(ModuleSearchInputs::inputs)
            .mapToInt(WorkspaceSearchInputs::size)
            .max()
            .orElse(0);
    LOG.fine(
        () ->
            "[references:plan] %s planned modules=%d open=%d disk=%d maxModule=%d ready %dms"
                .formatted(
                    uri,
                    searchInputs.size(),
                    openCount,
                    diskCount,
                    maxModuleCandidates,
                    planningTimer.elapsedMs()));
    progress.begin(
        progressTitle,
        searchInputs.stream()
            .map(ModuleSearchInputs::inputs)
            .mapToInt(WorkspaceSearchInputs::size)
            .sum());

    final List<CompletableFuture<List<ReferenceMatch>>> searches =
        searchInputs.stream()
            .flatMap(
                inputs ->
                    searchFutures(inputs, target, includeDeclaration, cancelChecker, progress))
            .toList();
    return joinCandidateResults(searches, cancelChecker);
  }

  private Stream<CompletableFuture<List<ReferenceMatch>>> searchFutures(
      final ModuleSearchInputs searchInputs,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    final CompilationWorker worker = searchInputs.worker();
    final WorkspaceSearchInputs inputs = searchInputs.inputs();
    final Stream<CompletableFuture<List<ReferenceMatch>>> openSearches =
        inputs.openDocuments().stream()
            .map(
                doc ->
                    searchOpenDocument(
                        worker, doc, target, includeDeclaration, cancelChecker, progress));
    final Stream<CompletableFuture<List<ReferenceMatch>>> diskSearches =
        CollectionUtil.partition(inputs.diskCandidates(), REFERENCE_BATCH_SIZE).stream()
            .map(
                chunk ->
                    searchDiskChunk(
                        worker, chunk, target, includeDeclaration, cancelChecker, progress));
    return Stream.concat(openSearches, diskSearches);
  }

  private CompletableFuture<List<ReferenceMatch>> searchOpenDocument(
      final CompilationWorker worker,
      final OpenDocument doc,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    router.touchAnalysisCache(doc.uri());
    return worker
        .searchReferences(
            doc.uri(), doc.content(), doc.version(), target, includeDeclaration, cancelChecker)
        .whenComplete(advancing(progress, false));
  }

  private CompletableFuture<List<ReferenceMatch>> searchDiskChunk(
      final CompilationWorker worker,
      final List<DiskCandidate> chunk,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    final List<TransientSource> sources =
        chunk.stream().map(d -> new TransientSource(d.uri(), d.content())).toList();
    return worker.searchReferencesTransient(
        sources, target, includeDeclaration, hits -> progress.advance(true, hits), cancelChecker);
  }

  List<ModuleSourceConfig> planSearchScope(
      final ReferenceTarget target, final ModuleSourceConfig cursorConfig) {
    return switch (target.scope()) {
      case DECLARING_FILE -> List.of();
      case DECLARING_MODULE ->
          cursorConfig != null
              ? state.moduleGraph().configsForModule(cursorConfig.moduleDir())
              : List.of();
      case REACTOR_MODULES ->
          cursorConfig != null
              ? state.moduleGraph().referenceSearchScope(cursorConfig)
              : state.registry().allConfigs();
    };
  }

  ModuleSourceConfig declaringConfigFor(
      final ReferenceTarget target, final ModuleSourceConfig fallback) {
    return reactorIndex.declaringConfig(target.qualifiedName()).orElse(fallback);
  }

  // The candidates' answers in one list; a cancelled search stops as each one arrives.
  private static <T> CompletableFuture<List<T>> joinCandidateResults(
      final List<CompletableFuture<List<T>>> futures, final CancelChecker cancelChecker) {
    return FeatureAnswers.concatAll(
        futures.stream()
            .map(
                future ->
                    future.thenApply(
                        results -> {
                          cancelChecker.checkCanceled();
                          return results;
                        })));
  }

  static List<Location> toLocations(final List<ReferenceMatch> matches) {
    return matches.stream().map(ReferenceMatch::toLocation).toList();
  }

  // Document highlight is references narrowed to the current buffer: resolve the symbol under the
  // cursor, then reuse the single-file reference search. No workspace/candidate index is touched,
  // so it stays cheap even for fields and methods.
  CompletableFuture<List<DocumentHighlight>> documentHighlightFuture(
      final String uri, final Position pos) {
    return router.openDocFeature(
        "documentHighlight",
        uri,
        List.of(),
        (worker, doc) -> {
          final var request = router.featureRequest(doc, pos);
          final var t = Stopwatch.start();
          return worker
              .resolveTarget(request)
              .thenCompose(target -> documentHighlightsForTarget(worker, doc, target))
              .thenApply(FeatureAnswers.logged(LOG, "documentHighlight", uri, t, "hits"));
        });
  }

  private CompletableFuture<List<DocumentHighlight>> documentHighlightsForTarget(
      final CompilationWorker worker, final OpenDocument doc, final ReferenceTarget target) {
    if (target == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    return worker
        .searchReferences(doc.uri(), doc.content(), doc.version(), target, true, () -> {})
        .thenApply(ReferenceSearch::toHighlights);
  }

  private static List<DocumentHighlight> toHighlights(final List<ReferenceMatch> matches) {
    return matches.stream().map(ReferenceMatch::toHighlight).toList();
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

  static boolean isInPackageScope(
      final Path path, final List<Path> sourceRoots, final Path packageRel) {
    if (packageRel == null) {
      return sourceRoots.stream().anyMatch(path::startsWith);
    }

    return sourceRoots.stream().anyMatch(root -> path.startsWith(root.resolve(packageRel)));
  }

  // Counts a file's matches into the search's progress once its search succeeds.
  private static <T> BiConsumer<List<T>, Throwable> advancing(
      final ProgressReporter.Task progress, final boolean transientFile) {
    return (matches, failure) -> {
      if (failure == null) {
        progress.advance(transientFile, matches.size());
      }
    };
  }

  private static Optional<DiskCandidate> readDiskCandidate(final String uri) {
    try {
      return Optional.of(new DiskCandidate(uri, Files.readString(LatheUri.toPath(uri))));
    } catch (final IOException e) {
      LOG.log(Level.FINE, e, () -> "[references] failed to read candidate: %s".formatted(uri));
      return Optional.empty();
    }
  }

  private record DiskCandidate(String uri, String content) {

    DiskCandidate {
      ValidCheck.check().notNull(uri, "uri").notNull(content, "content").validate();
    }
  }

  private record WorkspaceSearchInputs(
      List<OpenDocument> openDocuments, List<DiskCandidate> diskCandidates) {

    WorkspaceSearchInputs {
      openDocuments = List.copyOf(openDocuments);
      diskCandidates = List.copyOf(diskCandidates);
    }

    private int size() {
      return openDocuments.size() + diskCandidates.size();
    }
  }

  private record ModuleSearchInputs(CompilationWorker worker, WorkspaceSearchInputs inputs) {

    ModuleSearchInputs {
      ValidCheck.check().notNull(worker, "worker").notNull(inputs, "inputs").validate();
    }
  }

  // A module's search as planned from session state: its open documents, and the files on disk
  // that may reference the target, not read yet.
  private record ModuleSearchPlan(
      CompilationWorker worker, List<OpenDocument> openDocuments, List<String> candidateUris) {

    ModuleSearchPlan {
      ValidCheck.check().notNull(worker, "worker").validate();
      openDocuments = List.copyOf(openDocuments);
      candidateUris = List.copyOf(candidateUris);
    }
  }

  private ModuleSearchPlan planModuleSearch(
      final ModuleSourceConfig config, final ReferenceTarget target, final Path packageRel) {
    // Include the generated-sources root so a module's annotation-processor output (a record's
    // @Builder) is in scope; it references the record by simple name and never lives under a
    // regular source root (FR-012/FR-013).
    final List<Path> searchRoots = config.searchRoots();
    final List<OpenDocument> openForConfig =
        docs.all().stream()
            .filter(
                doc ->
                    state
                        .registry()
                        .moduleSourceFor(LatheUri.toPath(doc.uri()))
                        .map(c -> c.equals(config))
                        .orElse(false))
            .filter(doc -> isInPackageScope(LatheUri.toPath(doc.uri()), searchRoots, packageRel))
            .toList();
    final Set<String> openUris =
        openForConfig.stream().map(OpenDocument::uri).collect(Collectors.toUnmodifiableSet());
    final var planner =
        new ReferenceCandidatePlanner(state.candidateIndex(), reactorIndex.current());
    final List<String> candidateUris =
        planner.planCandidates(config, target).stream()
            .filter(uri -> !openUris.contains(uri))
            .filter(uri -> isInPackageScope(LatheUri.toPath(uri), searchRoots, packageRel))
            .toList();
    return new ModuleSearchPlan(state.registry().workerFor(config), openForConfig, candidateUris);
  }

  CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      implementationFuture(final String uri, final Position pos) {
    final Optional<FeatureRouter.OpenRoute> route = router.openRoute(uri);
    if (route.isEmpty()) {
      return CompletableFuture.completedFuture(Either.forLeft(List.of()));
    }

    final CompilationWorker cursorWorker = route.get().worker();
    final var request = router.featureRequest(route.get().doc(), pos);
    final var indexSnapshot = reactorIndex.current();
    final var lambdaSnapshot = reactorIndex.lambdaImplementors();

    final var t = Stopwatch.start();
    return cursorWorker
        .resolveTarget(request)
        .thenCompose(
            target ->
                implementationForTarget(
                    target, request, cursorWorker, indexSnapshot, lambdaSnapshot))
        .thenApply(
            locations -> {
              LOG.fine(
                  () ->
                      "[implementation] %s %dms hits=%d"
                          .formatted(uri, t.elapsedMs(), locations.size()));
              return FeatureAnswers.definitionResult(locations);
            });
  }

  private CompletableFuture<List<Location>> implementationForTarget(
      final ReferenceTarget target,
      final SourceFeatureRequest request,
      final CompilationWorker cursorWorker,
      final WorkspaceTypeIndex indexSnapshot,
      final Map<String, List<String>> lambdaSnapshot) {
    if (target == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    if (!target.isType() && target.kind() != ElementKind.METHOD) {
      return CompletableFuture.completedFuture(List.of());
    }

    // Only an interface, or its method, can be implemented by lambdas.
    final CompletableFuture<Optional<String>> samInterface =
        target.kind().isInterface() || target.kind() == ElementKind.METHOD
            ? cursorWorker.functionalInterfaceName(request)
            : CompletableFuture.completedFuture(Optional.empty());
    // Reading the workspace to plan the search runs on the event loop: the worker delivers there.
    return samInterface.thenCompose(
        sam ->
            target.isType()
                ? implementationsOfType(
                    target, request, cursorWorker, sam, indexSnapshot, lambdaSnapshot)
                : implementationsIn(
                    target, sam, indexSnapshot, lambdaSnapshot, entry -> true, uri -> true));
  }

  // A type's named subtypes come from the type index alone; its local and anonymous subclasses, and
  // for a functional interface its lambdas and method references, need sources attributed: the
  // bytecode lambda index and the open documents, not every file naming the interface. A failure
  // there leaves the named subtypes.
  private CompletableFuture<List<Location>> implementationsOfType(
      final ReferenceTarget target,
      final SourceFeatureRequest request,
      final CompilationWorker cursorWorker,
      final Optional<String> samInterface,
      final WorkspaceTypeIndex indexSnapshot,
      final Map<String, List<String>> lambdaSnapshot) {
    return FeatureAnswers.concatAll(
        Stream.of(
            cursorWorker.typeImplementations(request, indexSnapshot),
            implementationsIn(
                    target,
                    samInterface,
                    indexSnapshot,
                    lambdaSnapshot,
                    entry -> !TypeSourceLocator.isNamedDeclaration(entry),
                    uri -> docs.get(uri) != null)
                .exceptionally(
                    failure -> {
                      LOG.log(
                          Level.WARNING,
                          failure,
                          () ->
                              "[implementation:type] %s attributed search failed, named subtypes only"
                                  .formatted(request.uri()));
                      return List.of();
                    })));
  }

  // The implementations found by attributing the files that may hold them: the subtypes the filter
  // keeps (local and anonymous ones among them, matched from the tree), and the files with lambdas
  // and method references when the target is a functional interface or its method: those the
  // bytecode lambda index names, and of the files naming the interface those the filter keeps.
  private CompletableFuture<List<Location>> implementationsIn(
      final ReferenceTarget target,
      final Optional<String> samInterface,
      final WorkspaceTypeIndex indexSnapshot,
      final Map<String, List<String>> lambdaSnapshot,
      final Predicate<TypeIndexEntry> subtypes,
      final Predicate<String> namingFiles) {
    final Map<Path, Set<String>> candidatesByFile =
        Stream.of(
                subtypeImplementationFiles(target, indexSnapshot, subtypes),
                lambdaImplementationFiles(samInterface, namingFiles),
                bytecodeLambdaImplementationFiles(
                    target, samInterface, indexSnapshot, lambdaSnapshot))
            .flatMap(stream -> stream)
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey, Map.Entry::getValue, ReferenceSearch::unionNames));
    return FeatureAnswers.concatAll(
        candidatesByFile.entrySet().stream()
            .map(
                entry ->
                    fileQueryFuture(
                        entry.getKey(),
                        analysis ->
                            MethodImplementationLocator.locate(
                                analysis,
                                target,
                                entry.getValue(),
                                LatheUri.fromPath(entry.getKey())))));
  }

  // The files of the target's subtypes the filter keeps; an anonymous `Outer$1` maps to Outer's
  // file.
  Stream<Map.Entry<Path, Set<String>>> subtypeImplementationFiles(
      final ReferenceTarget target,
      final WorkspaceTypeIndex indexSnapshot,
      final Predicate<TypeIndexEntry> subtypes) {
    return indexSnapshot.transitiveSubtypes(target.qualifiedName()).stream()
        .filter(subtypes)
        .flatMap(
            entry ->
                TypeSourceLocator.findSourceFile(entry, state.registry().allSourceRoots()).stream()
                    .map(path -> Map.entry(path, Set.of(entry.binaryName()))));
  }

  // Files that spell the functional interface (via the identifier index) — the freshest path for
  // lambdas in open/edited files. Empty when the target method is not a functional-interface SAM.
  // Argument-position lambdas, which never spell the interface, are covered instead by
  // bytecodeLambdaImplementationFiles.
  private Stream<Map.Entry<Path, Set<String>>> lambdaImplementationFiles(
      final Optional<String> samInterface, final Predicate<String> namingFiles) {
    return samInterface.stream()
        .flatMap(name -> state.candidateIndex().candidateUris(name).stream())
        .filter(namingFiles)
        .map(LatheUri::toPath)
        .map(path -> Map.entry(path, Set.<String>of()));
  }

  // Classes the compiled bytecode records as converting a lambda / method reference to the target
  // SAM interface or a functional sub-interface of it — including argument-position lambdas whose
  // source never names the interface. Empty when the target method is not a functional-interface
  // SAM.
  private Stream<Map.Entry<Path, Set<String>>> bytecodeLambdaImplementationFiles(
      final ReferenceTarget target,
      final Optional<String> samInterface,
      final WorkspaceTypeIndex indexSnapshot,
      final Map<String, List<String>> lambdaSnapshot) {
    if (samInterface.isEmpty()) {
      return Stream.empty();
    }

    return functionalInterfaceFamily(target.qualifiedName(), indexSnapshot).stream()
        .flatMap(iface -> lambdaSnapshot.getOrDefault(iface, List.of()).stream())
        .distinct()
        .flatMap(
            binaryName ->
                TypeSourceLocator.findSourceFile(binaryName, state.registry().allSourceRoots())
                    .stream())
        .map(path -> Map.entry(path, Set.<String>of()));
  }

  // The interface itself plus its functional sub-interfaces: a lambda converted to a sub-interface
  // compiles to that sub-interface's descriptor, so the index must be queried for each.
  private static Set<String> functionalInterfaceFamily(
      final String interfaceBinaryName, final WorkspaceTypeIndex indexSnapshot) {
    return Stream.concat(
            Stream.of(interfaceBinaryName),
            indexSnapshot.transitiveSubtypes(interfaceBinaryName).stream()
                .filter(
                    entry ->
                        entry.kind() == io.github.aglibs.lathe.core.typeindex.TypeKind.INTERFACE)
                .map(TypeIndexEntry::binaryName))
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Set<String> unionNames(final Set<String> left, final Set<String> right) {
    return Stream.concat(left.stream(), right.stream()).collect(Collectors.toUnmodifiableSet());
  }

  // A query over a source file's analysis in its own module: the open document's, or else the
  // file on disk, read off the loop and attributed once.
  <T> CompletableFuture<List<T>> fileQueryFuture(
      final Path sourceFile, final Function<AttributedFileAnalysis, List<T>> query) {
    final Optional<CompilationWorker> worker = state.registry().moduleWorkerFor(sourceFile);
    if (worker.isEmpty()) {
      return CompletableFuture.completedFuture(List.of());
    }

    final var uri = LatheUri.fromPath(sourceFile);
    final OpenDocument openFile = docs.get(uri);
    if (openFile != null) {
      router.touchAnalysisCache(openFile.uri());
      return worker.get().queryFile(uri, openFile.content(), openFile.version(), query);
    }

    return eventLoop
        .rejoin(CompletableFuture.supplyAsync(() -> readDiskCandidate(uri)))
        .thenCompose(
            disk ->
                disk.flatMap(
                        file ->
                            state
                                .registry()
                                .moduleWorkerFor(sourceFile)
                                .map(w -> w.queryFileTransient(uri, file.content(), query)))
                    .orElseGet(() -> CompletableFuture.completedFuture(List.of())));
  }

  CompletableFuture<List<CallHierarchyOutgoingCall>> outgoingCallsFuture(
      final CallHierarchyItem item) {
    final CallHierarchyItemData data = CallHierarchyItemDataCodec.decode(item.getData());
    if (data == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    final var t = Stopwatch.start();
    return router
        .openDocFeature(
            "callHierarchy:outgoing",
            data.routingUri(),
            List.of(),
            (worker, doc) ->
                worker.outgoingCalls(
                    item, doc.uri(), doc.content(), doc.version(), state.typeSourceDirs()))
        .thenApply(
            FeatureAnswers.logged(LOG, "callHierarchy:outgoing", data.routingUri(), t, "calls"));
  }

  CompletableFuture<List<CallHierarchyIncomingCall>> incomingCallsFuture(
      final CallHierarchyItem item,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    final CallHierarchyItemData data = CallHierarchyItemDataCodec.decode(item.getData());
    if (data == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    final var target =
        new ReferenceTarget(
            data.kind(),
            data.ownerBinaryName(),
            data.methodName(),
            data.erasedDescriptor(),
            data.scope(),
            List.of(),
            false,
            -1);
    final var progressTitle = "Finding callers of %s".formatted(target.simpleName());

    final var declaringPath = LatheUri.toPath(data.routingUri());
    final var declaringConfig = state.registry().moduleSourceFor(declaringPath);

    if (target.scope() == ReferenceTarget.SearchScope.DECLARING_FILE) {
      final Optional<CompilationWorker> routedWorker =
          router.routeCompiler(data.routingUri()).analysisWorker();
      if (routedWorker.isEmpty()) {
        return CompletableFuture.completedFuture(List.of());
      }

      progress.begin(progressTitle, 1);
      final OpenDocument declaringDoc = docs.get(data.routingUri());
      final var plan =
          declaringDoc != null
              ? new ModuleSearchPlan(routedWorker.get(), List.of(declaringDoc), List.of())
              : new ModuleSearchPlan(routedWorker.get(), List.of(), List.of(data.routingUri()));
      return readSearchInputs(List.of(plan))
          .thenCompose(
              inputs ->
                  FeatureAnswers.concatAll(
                      incomingCallFutures(inputs.get(0), target, cancelChecker, progress)));
    }

    final Path packageRel =
        target.scope() == ReferenceTarget.SearchScope.DECLARING_MODULE
            ? declaringPackageRel(declaringPath, declaringConfig.orElse(null))
            : null;

    final List<ModuleSourceConfig> configs = planSearchScope(target, declaringConfig.orElse(null));

    final var t = Stopwatch.start();
    return readSearchInputs(
            configs.stream().map(config -> planModuleSearch(config, target, packageRel)).toList())
        .thenCompose(
            searchInputs -> {
              final List<CompletableFuture<List<CallHierarchyIncomingCall>>> searches =
                  searchInputs.stream()
                      .flatMap(
                          inputs -> incomingCallFutures(inputs, target, cancelChecker, progress))
                      .toList();
              progress.begin(progressTitle, searches.size());
              return joinCandidateResults(searches, cancelChecker);
            })
        .thenApply(
            FeatureAnswers.logged(LOG, "callHierarchy:incoming", data.routingUri(), t, "calls"));
  }

  private Stream<CompletableFuture<List<CallHierarchyIncomingCall>>> incomingCallFutures(
      final ModuleSearchInputs planned,
      final ReferenceTarget target,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    final var worker = planned.worker();
    final var inputs = planned.inputs();
    return Stream.concat(
        inputs.openDocuments().stream()
            .map(
                doc -> {
                  cancelChecker.checkCanceled();
                  router.touchAnalysisCache(doc.uri());
                  return worker
                      .searchIncomingCalls(
                          doc.uri(), doc.content(), doc.version(), target, cancelChecker)
                      .whenComplete(advancing(progress, false));
                }),
        inputs.diskCandidates().stream()
            .map(
                d -> {
                  cancelChecker.checkCanceled();
                  return worker
                      .searchIncomingCallsTransient(d.uri(), d.content(), target, cancelChecker)
                      .whenComplete(advancing(progress, true));
                }));
  }

  CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchyFuture(
      final String uri, final Position pos) {
    final var t = Stopwatch.start();
    return router
        .openDocFeature(
            "callHierarchy:prepare",
            uri,
            List.of(),
            (worker, doc) -> {
              final var request = router.featureRequest(doc, pos);
              return worker.prepareCallHierarchy(request);
            })
        .thenApply(FeatureAnswers.logged(LOG, "callHierarchy:prepare", uri, t, "items"));
  }
}
