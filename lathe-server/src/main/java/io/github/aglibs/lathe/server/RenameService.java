package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.MethodImplementationLocator;
import io.github.aglibs.lathe.analysis.ReferenceMatch;
import io.github.aglibs.lathe.analysis.ReferenceRole;
import io.github.aglibs.lathe.analysis.ReferenceTarget;
import io.github.aglibs.lathe.analysis.RenameBindingCheck;
import io.github.aglibs.lathe.analysis.RenameProvider;
import io.github.aglibs.lathe.analysis.TypeSourceLocator;
import io.github.aglibs.lathe.core.ExceptionUtil;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;

// Decides whether a symbol can be renamed and computes the edit: the declaration, every override
// and implementation, and every use the reference search finds, refused when a use would then mean
// something else or a use the search cannot see would be left behind. Applying the edit, and the
// saves that follow it, is the session's rename transaction.
final class RenameService {

  private static final Logger LOG = Logger.getLogger(RenameService.class.getName());

  private final ServerEventLoop eventLoop;
  private final WorkspaceState state;
  private final ReactorTypeIndex reactorIndex;
  private final FeatureRouter router;
  private final ReferenceSearch search;
  private final DocumentRegistry docs;

  RenameService(
      final ServerEventLoop eventLoop,
      final WorkspaceState state,
      final ReactorTypeIndex reactorIndex,
      final FeatureRouter router,
      final ReferenceSearch search,
      final DocumentRegistry docs) {
    this.eventLoop = eventLoop;
    this.state = state;
    this.reactorIndex = reactorIndex;
    this.router = router;
    this.search = search;
    this.docs = docs;
  }

  CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>>
      prepareRenameFuture(final String uri, final Position pos, final boolean fileRenameSupported) {
    final OpenDocument openFile = docs.get(uri);
    if (openFile == null
        || !(router.routeCompiler(uri) instanceof final CompilerRoute.Module module)) {
      return CompletableFuture.completedFuture(null);
    }

    final var request = router.featureRequest(openFile, pos);
    final var t = Stopwatch.start();
    return module
        .worker()
        .resolveRenameTarget(request)
        .thenApply(
            target -> {
              if (target == null) {
                return null;
              }

              requireRenameable(target);
              // Refuse a type whose declaring file must move when the client cannot apply the file
              // rename, rather than returning a half-rename that leaves a public type in a
              // mismatched file.
              if (!fileRenameSupported && renameDeclaringFile(target).isPresent()) {
                return null;
              }

              final Range range = RenameProvider.identifierRange(openFile.content(), pos);
              return range == null
                  ? null
                  : Either3.<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>forFirst(
                      range);
            })
        .whenComplete((range, failure) -> logRefusal("prepareRename", uri, t, failure));
  }

  // Renaming the uses of a declaration the rename cannot edit breaks every one of them: one outside
  // the reactor (JDK or a dependency), one a processor regenerates, or a method overriding either.
  private void requireRenameable(final ReferenceTarget target) {
    final List<Path> roots = state.registry().allSourceRoots();
    final Map<String, Optional<Path>> declarations =
        Stream.concat(Stream.of(target.qualifiedName()), target.overriddenDeclarers().stream())
            .distinct()
            .collect(
                Collectors.toUnmodifiableMap(
                    Function.identity(),
                    binaryName -> TypeSourceLocator.findSourceFile(binaryName, roots)));
    // A secondary top-level type has no file of its own name, so the index vouches for it; the file
    // lookup still covers a type too new for the index.
    if (declarations.entrySet().stream()
        .anyMatch(
            declaration ->
                declaration.getValue().isEmpty()
                    && !reactorIndex.current().isReactorType(declaration.getKey()))) {
      throw FeatureAnswers.refused(
          "`%s` is declared, or overrides a method declared, outside the project (JDK or a dependency), so it cannot be renamed"
              .formatted(target.simpleName()));
    }

    if (declarations.values().stream()
        .flatMap(Optional::stream)
        .anyMatch(this::isGeneratedSource)) {
      throw FeatureAnswers.refused(
          "`%s` is declared, or overrides a method declared, in generated source; rename the source it is generated from instead"
              .formatted(target.simpleName()));
    }
  }

  // What a rename's edits would leave behind: an override that also overrides a method outside the
  // renamed family, or a static import that also names members outside it -- kept for them, it
  // leaves the renamed member's calls in that file without one.
  private static void requireNothingLeftBehind(
      final ReferenceTarget target, final List<ReferenceMatch> matches) {
    refuseIfPresent(
        matches.stream()
            .map(match -> leftBehind(target, match))
            .flatMap(Optional::stream)
            .findFirst());
  }

  private static Optional<String> leftBehind(
      final ReferenceTarget target, final ReferenceMatch match) {
    final Path file = LatheUri.toPath(match.uri()).getFileName();
    if (!match.alsoOverrides().isEmpty()) {
      return Optional.of(
          "`%s` cannot be renamed: its override in %s also overrides %s, which the rename would leave behind"
              .formatted(target.simpleName(), file, String.join(", ", match.alsoOverrides())));
    }

    if (match.role() == ReferenceRole.SHARED_STATIC_IMPORT) {
      return Optional.of(
          "`%s` cannot be renamed: %s imports it statically by a name other members share, so the import must stay and the renamed calls there would lose theirs; rename those members together, or qualify the calls first"
              .formatted(target.simpleName(), file));
    }

    return Optional.empty();
  }

  // A subtype implementing the method through a superclass outside the override family keeps that
  // superclass's method, which then implements nothing; the rename cannot change it. Only an
  // interface method can be implemented that way: a class method is what its subtypes inherit.
  private CompletableFuture<ReferenceTarget> requireImplementationsRenamed(
      final ReferenceTarget target) {
    if (target.kind() != ElementKind.METHOD
        || target.scope() == ReferenceTarget.SearchScope.DECLARING_FILE) {
      return CompletableFuture.completedFuture(target);
    }

    final boolean onInterface =
        reactorIndex
            .current()
            .findType(target.qualifiedName())
            .filter(type -> type.kind() == TypeKind.INTERFACE)
            .isPresent();
    final CompletableFuture<List<String>> inheritedOutside =
        onInterface
            ? FeatureAnswers.concatAll(
                search
                    .subtypeImplementationFiles(
                        target, reactorIndex.current(), TypeSourceLocator::isNamedDeclaration)
                    .map(
                        entry ->
                            search.fileQueryFuture(
                                entry.getKey(),
                                analysis ->
                                    MethodImplementationLocator.inheritedOutsideFamily(
                                        analysis, target, entry.getValue()))))
            : CompletableFuture.completedFuture(List.of());
    return inheritedOutside.thenApply(
        reasons -> {
          refuseIfPresent(reasons.stream().findFirst());
          return target;
        });
  }

  // A rename that would change what a name means, a use the new name captures or a renamed site
  // that would then mean something else, is refused: the edited text is attributed to check.
  private CompletableFuture<Void> requireMeaningKept(
      final ReferenceTarget target,
      final List<ReferenceMatch> matches,
      final String newName,
      final String cursorUri,
      final ModuleSourceConfig cursorConfig,
      final CancelChecker cancelChecker) {
    return meaningConflict(target, matches, newName, cursorUri, cursorConfig, cancelChecker)
        .thenAccept(RenameService::refuseIfPresent);
  }

  // The files whose meaning the rename can change, checked in every module that sees the symbol or
  // has an edit: the edited ones and, unless nothing outside the declaring file sees the symbol,
  // those already using the new name, where a use can be captured. Each module's files are
  // attributed with every edited file, so the renamed declarations are what they see. Only names
  // and ranges are gathered here; the module workers read the files.
  private CompletableFuture<Optional<String>> meaningConflict(
      final ReferenceTarget target,
      final List<ReferenceMatch> matches,
      final String newName,
      final String cursorUri,
      final ModuleSourceConfig cursorConfig,
      final CancelChecker cancelChecker) {
    final Map<String, List<Range>> renamed =
        matches.stream()
            .filter(match -> !isGeneratedSource(LatheUri.toPath(match.uri())))
            .collect(
                Collectors.groupingBy(
                    ReferenceMatch::uri,
                    Collectors.mapping(ReferenceMatch::range, Collectors.toList())));
    final boolean fileScoped = target.scope() == ReferenceTarget.SearchScope.DECLARING_FILE;
    final ModuleSourceConfig declaring = search.declaringConfigFor(target, cursorConfig);
    final Set<ModuleSourceConfig> seeing =
        Stream.of(
                Stream.ofNullable(declaring),
                fileScoped
                    ? Stream.<ModuleSourceConfig>empty()
                    : search.planSearchScope(target, declaring).stream(),
                renamed.keySet().stream().flatMap(uri -> compilingConfig(uri).stream()))
            .flatMap(configs -> configs)
            .collect(Collectors.toUnmodifiableSet());
    // A type whose file the rename moves is attributed under its new file name, which a public
    // type must match; its text is still read from where it is.
    final Map<String, String> moved =
        renameDeclaringFile(target)
            .map(
                file ->
                    Map.of(LatheUri.fromPath(file), LatheUri.fromPath(movedFile(file, newName))))
            .orElse(Map.of());
    final Stream<String> usingNewName =
        fileScoped
            ? Stream.empty()
            : state.candidateIndex().candidateUris(newName).stream()
                .filter(uri -> !moved.containsValue(uri));
    final Map<ModuleSourceConfig, List<RenameBindingCheck.CheckedFile>> byConfig =
        Stream.concat(renamed.keySet().stream(), usingNewName)
            .distinct()
            .flatMap(
                uri ->
                    compilingConfig(uri)
                        .filter(seeing::contains)
                        .map(
                            config ->
                                Map.entry(
                                    config,
                                    new RenameBindingCheck.CheckedFile(
                                        uri,
                                        moved.getOrDefault(uri, uri),
                                        renamed.getOrDefault(uri, List.of()))))
                        .stream())
            .collect(
                Collectors.groupingBy(
                    Map.Entry::getKey,
                    Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    final var check =
        new RenameBindingCheck.RenameCheck(
            target,
            newName,
            cursorUri,
            byConfig.values().stream()
                .flatMap(List::stream)
                .map(file -> docs.get(file.uri()))
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableMap(OpenDocument::uri, OpenDocument::content)));
    return FeatureAnswers.concatAll(
            byConfig.entrySet().stream()
                .map(
                    entry ->
                        state
                            .registry()
                            .workerFor(entry.getKey())
                            .renameBindingConflict(
                                check,
                                visibleEdits(byConfig, entry.getKey()),
                                entry.getValue(),
                                cancelChecker)
                            .thenApply(conflict -> conflict.stream().toList())))
        .thenApply(conflicts -> conflicts.stream().findFirst());
  }

  // The edited files a tree's compile sees: its own, and those of the trees on its class or module
  // path (test fixtures another project compiles against among them), so the renamed declarations
  // are what its files see.
  private static List<RenameBindingCheck.CheckedFile> visibleEdits(
      final Map<ModuleSourceConfig, List<RenameBindingCheck.CheckedFile>> byConfig,
      final ModuleSourceConfig config) {
    return byConfig.entrySet().stream()
        .filter(entry -> entry.getKey().equals(config) || config.sees(entry.getKey()))
        .flatMap(entry -> entry.getValue().stream())
        .filter(RenameBindingCheck.CheckedFile::edited)
        .toList();
  }

  // The module source config that compiles the file, unless it is generated.
  private Optional<ModuleSourceConfig> compilingConfig(final String uri) {
    final Path file = LatheUri.toPath(uri);
    return isGeneratedSource(file) ? Optional.empty() : state.registry().moduleSourceFor(file);
  }

  private static void refuseIfPresent(final Optional<String> reason) {
    if (reason.isPresent()) {
      throw FeatureAnswers.refused(reason.get());
    }
  }

  // The source file to rename when the target is the file-defining type for its file (so `Foo.java`
  // must become `Bar.java`); empty for members, nested types, and secondary top-level types that
  // stay in place.
  private Optional<Path> renameDeclaringFile(final ReferenceTarget target) {
    if (!(target.kind().isClass() || target.kind().isInterface())) {
      return Optional.empty();
    }

    return TypeSourceLocator.findSourceFile(
            target.qualifiedName(), state.registry().allSourceRoots())
        .filter(file -> file.getFileName().toString().equals(target.simpleName() + ".java"));
  }

  private static Path movedFile(final Path file, final String newName) {
    return file.resolveSibling("%s.java".formatted(newName));
  }

  private WorkspaceEdit renameEdit(
      final ReferenceTarget target,
      final List<Location> occurrences,
      final String newName,
      final boolean fileRenameSupported) {
    // Generated sources are left alone: their processors regenerate them from the renamed sources,
    // and an edited copy written alongside would collide with the regenerated one.
    final List<Location> editable =
        occurrences.stream()
            .filter(location -> !isGeneratedSource(LatheUri.toPath(location.getUri())))
            .toList();
    if (editable.isEmpty()) {
      return null;
    }

    final Optional<Path> declaringFile = renameDeclaringFile(target);
    if (declaringFile.isEmpty()) {
      return RenameProvider.toWorkspaceEdit(editable, newName);
    }

    if (!fileRenameSupported) {
      return null;
    }

    final var file = declaringFile.get();
    final var oldUri = LatheUri.fromPath(file);
    final var newUri = LatheUri.fromPath(movedFile(file, newName));
    return RenameProvider.toWorkspaceEditWithFileRename(editable, newName, oldUri, newUri);
  }

  private boolean isGeneratedSource(final Path file) {
    return state.registry().allConfigs().stream().anyMatch(config -> config.isGenerated(file));
  }

  CompletableFuture<WorkspaceEdit> renameFuture(
      final String uri,
      final Position pos,
      final String newName,
      final boolean fileRenameSupported,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    cancelChecker.checkCanceled();
    final var t = Stopwatch.start();
    if (!RenameProvider.isValidName(newName)) {
      final var invalid =
          FeatureAnswers.refused(
              "`%s` is not a valid name: it must be one Java identifier, not a keyword"
                  .formatted(newName));
      logRefusal("rename", uri, t, invalid);
      return CompletableFuture.failedFuture(invalid);
    }

    final OpenDocument openFile = docs.get(uri);
    if (openFile == null
        || !(router.routeCompiler(uri) instanceof final CompilerRoute.Module module)) {
      return CompletableFuture.completedFuture(null);
    }

    final var cursorWorker = module.worker();
    final var request = router.featureRequest(openFile, pos);
    final ModuleSourceConfig cursorConfig = module.config();
    return cursorWorker
        .renameConflict(request, newName)
        .thenCompose(
            conflict -> {
              refuseIfPresent(conflict);
              return cursorWorker.resolveRenameTarget(request);
            })
        .thenCompose(
            target -> {
              cancelChecker.checkCanceled();
              if (target == null) {
                return CompletableFuture.completedFuture((WorkspaceEdit) null);
              }

              requireRenameable(target);
              return search
                  .referenceSearchTarget(cursorWorker, request, target, cancelChecker)
                  .thenCompose(this::requireImplementationsRenamed)
                  .thenCompose(
                      searchTarget ->
                          search
                              .searchReferencesForTarget(
                                  openFile,
                                  cursorWorker,
                                  cursorConfig,
                                  searchTarget,
                                  true,
                                  cancelChecker,
                                  progress,
                                  "Renaming %s".formatted(searchTarget.simpleName()))
                              .thenApply(
                                  matches -> {
                                    LOG.info(
                                        () ->
                                            "[rename] %s %dms target=%s edits=%d"
                                                .formatted(
                                                    uri,
                                                    t.elapsedMs(),
                                                    searchTarget.simpleName(),
                                                    matches.size()));
                                    requireNothingLeftBehind(searchTarget, matches);
                                    return matches;
                                  })
                              .thenCompose(
                                  matches ->
                                      requireMeaningKept(
                                              searchTarget,
                                              matches,
                                              newName,
                                              uri,
                                              cursorConfig,
                                              cancelChecker)
                                          .thenApply(
                                              kept ->
                                                  renameEdit(
                                                      searchTarget,
                                                      ReferenceSearch.toLocations(matches),
                                                      newName,
                                                      fileRenameSupported))));
            })
        .whenComplete((edit, failure) -> logRefusal("rename", uri, t, failure));
  }

  // A refusal reaches the client as the request's error; the log keeps why it was refused.
  private static void logRefusal(
      final String operation, final String uri, final Stopwatch t, final Throwable failure) {
    final Throwable cause = ExceptionUtil.completionCause(failure);
    if (cause instanceof final ResponseErrorException refused) {
      LOG.info(
          () ->
              "[%s] %s %dms refused: %s"
                  .formatted(
                      operation, uri, t.elapsedMs(), refused.getResponseError().getMessage()));
    }
  }
}
