package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.PackageDirectory;
import io.github.aglibs.lathe.analysis.TextMentions;
import io.github.aglibs.lathe.analysis.TransientSource;
import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import io.github.aglibs.lathe.core.typeindex.ClassFileTypeScanner;
import io.github.aglibs.lathe.server.engine.LatheModuleSummary;
import io.github.aglibs.lathe.server.engine.LatheNotes;
import io.github.aglibs.lathe.server.engine.UnreadSources;
import io.github.aglibs.lathe.server.files.ServerFiles;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.lathe.server.module.WorkspaceModuleGraph;
import io.github.aglibs.lathe.server.run.LaunchReader;
import io.github.aglibs.lathe.server.workspace.UnreadLanguage;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

// What an agent's verify_change and the notes on every MCP answer are built from: which sources
// differ from what the build compiled, which modules their callers are in, which files still fail
// to compile, and which a sync would add. Reads the write pass's state and the module graph; it
// changes nothing but what WritePass's own methods change.
final class ChangeVerifier {

  private final WorkspaceState state;
  private final FeatureRouter router;
  private final WritePass writePass;
  // The compiles verify_change, and a run before it launches, check the changed files and their
  // callers with.
  private final DocumentFeatures features;
  // Generated files a rename left calling the old name, with their content then: verify_change
  // checks them as callers until their generator rewrites them. The referrer search cannot reach
  // every one (JMH's names only a generated subclass of the renamed member's class).
  private final Map<Path, ContentFingerprint> leftByRename = new HashMap<>();

  ChangeVerifier(
      final WorkspaceState state,
      final FeatureRouter router,
      final WritePass writePass,
      final DocumentFeatures features) {
    this.state = state;
    this.router = router;
    this.writePass = writePass;
    this.features = features;
  }

  // A changed file is checked alone, against the classes its write produced, as the build sees it:
  // in a batch its source would be analyzed again instead, and Lombok there reads a lombok.config
  // that may be up to 2 s old. Its callers, unchanged, share a batch per source tree.
  CompletableFuture<Map<String, List<Diagnostic>>> check(
      final Map<String, String> contents,
      final Set<String> changed,
      final List<TransientSource> context,
      final CancelChecker cancel) {
    final Map<Boolean, Map<String, String>> byChanged =
        contents.entrySet().stream()
            .collect(
                Collectors.partitioningBy(
                    file -> changed.contains(file.getKey()),
                    Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)));
    return FeatureAnswers.concatAll(
            byChanged.get(true).entrySet().stream()
                .map(file -> checkedAlone(file, context, cancel)))
        .thenCombine(
            features.diagnosticsTransientFuture(byChanged.get(false), context, cancel),
            (alone, batched) ->
                Stream.concat(alone.stream(), batched.entrySet().stream())
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)));
  }

  // A changed file another changed file's failing write leaves stale classes for is checked with
  // that file's edited source; the failing file itself, and any other file, alone.
  private CompletableFuture<List<Map.Entry<String, List<Diagnostic>>>> checkedAlone(
      final Map.Entry<String, String> file,
      final List<TransientSource> context,
      final CancelChecker cancel) {
    final List<TransientSource> others =
        context.stream().filter(source -> !source.uri().equals(file.getKey())).toList();
    if (others.isEmpty()) {
      return features
          .diagnosticsTransientFuture(file.getKey(), file.getValue())
          .thenApply(diagnostics -> List.of(Map.entry(file.getKey(), diagnostics)));
    }

    return features
        .diagnosticsTransientFuture(Map.of(file.getKey(), file.getValue()), others, cancel)
        .thenApply(checked -> List.copyOf(checked.entrySet()));
  }

  // Where a name may survive as text: the sources whose tokens include it, and the resource roots.
  TextMentions.Sources textMentionSources(final String name) {
    return new TextMentions.Sources(
        state.candidateIndex().candidateUris(name).stream().map(LatheUri::toPath).toList(),
        state.manifest().resourceSourceDirs());
  }

  // A file that cannot be read now (its generator removed it) has nothing left to check.
  void checkUntilRegenerated(final List<Path> generated) {
    for (final Path file : generated) {
      try {
        leftByRename.put(file, FileUtil.fingerprint(file));
      } catch (final IOException e) {
        leftByRename.remove(file);
      }
    }
  }

  // A file its generator rewrote, or removed, is no longer the rename's to check.
  private List<Path> stillLeftByRename() {
    leftByRename
        .entrySet()
        .removeIf(entry -> !ServerFiles.hasContent(entry.getKey(), entry.getValue()));
    return List.copyOf(leftByRename.keySet());
  }

  // Every synced module's source roots: a Java file below none is one the last build left out.
  List<Path> sourceRoots() {
    return state.registry().allSourceRoots();
  }

  // The changed files whose content is not what the last build compiled: a change put back is no
  // change, so verify_change neither checks it nor asks for a sync. A deleted file stays, as its
  // callers break. Each module's stamps are read once.
  List<Path> differingFromBuild(final List<Path> changed) {
    final Map<ModuleSourceConfig, Map<Path, ContentFingerprint>> builtByTree = new HashMap<>();
    final List<Path> differing = new ArrayList<>();
    for (final Path file : changed) {
      if (writePass.lombokChanged(file) || !backToBuilt(file, builtByTree)) {
        differing.add(file);
      }
    }

    return List.copyOf(differing);
  }

  // Every source whose content is not what the last build compiled, however long ago it changed:
  // one the session wrote (its stamp holds other content than the build's), one the build compiled
  // that is gone, and one whose write failed (it keeps its old stamp). verify_change checks them
  // all, so a broken change stays reported until it is fixed or built, not only by the check after
  // it. A tree with no built record (OpenJDK) cannot tell and adds nothing.
  List<Path> changedSinceBuild() {
    return Stream.concat(
            writePass.failedCompiles().keySet().stream(),
            state.registry().allConfigs().stream()
                .flatMap(config -> unbuiltSources(config).stream()))
        .distinct()
        .toList();
  }

  private static List<Path> unbuiltSources(final ModuleSourceConfig config) {
    final Map<Path, ContentFingerprint> built =
        CompiledStamps.loadBuilt(config.moduleDir(), config.sourceTree());
    final List<Path> roots =
        config.sourceRoots().stream().filter(root -> !config.isGenerated(root)).toList();
    if (built.isEmpty() || roots.isEmpty()) {
      return List.of();
    }

    final Stream<Path> rewritten =
        CompiledStamps.load(config.moduleDir(), config.sourceTree()).entrySet().stream()
            .filter(
                stamp ->
                    stamp.getValue().content() != null
                        && !stamp.getValue().content().equals(built.get(stamp.getKey())))
            .map(stamp -> sourceIn(roots, stamp.getKey()));
    final Stream<Path> deleted =
        built.keySet().stream()
            .map(rel -> sourceIn(roots, rel))
            .filter(source -> !Files.exists(source));
    return Stream.concat(rewritten, deleted).toList();
  }

  // The root holding the source, or the first root when none does (the source was deleted).
  private static Path sourceIn(final List<Path> roots, final Path rel) {
    return roots.stream()
        .map(root -> root.resolve(rel))
        .filter(Files::exists)
        .findFirst()
        .orElseGet(() -> roots.get(0).resolve(rel));
  }

  // A deleted file is back to the build only if the build never compiled it either (one created and
  // deleted since); a tree with no built record (OpenJDK, or stamps from before) cannot tell.
  private boolean backToBuilt(
      final Path file, final Map<ModuleSourceConfig, Map<Path, ContentFingerprint>> builtByTree) {
    final Optional<ModuleSourceConfig> config = state.registry().moduleSourceFor(file);
    final Path root = config.flatMap(tree -> tree.sourceRootOf(file)).orElse(null);
    if (root == null) {
      return false;
    }

    final Map<Path, ContentFingerprint> built =
        builtByTree.computeIfAbsent(
            config.get(), tree -> CompiledStamps.loadBuilt(tree.moduleDir(), tree.sourceTree()));
    if (built.isEmpty()) {
      return false;
    }

    final ContentFingerprint compiled = built.get(root.relativize(file));
    return Files.notExists(file) ? compiled == null : ServerFiles.hasContent(file, compiled);
  }

  // Files to diagnose for verify_change, by module: each changed file, then the files that see it,
  // the changed files' modules first, then the generated callers a rename left.
  private Map<String, List<Path>> verifyTargetsByModule(
      final List<Path> changed,
      final Map<Path, List<Path>> referrers,
      final List<Path> leftCallers) {
    final Map<String, Set<Path>> byModule = new LinkedHashMap<>();
    changed.forEach(file -> addVerifyTarget(byModule, file));
    changed.forEach(
        file -> seenBy(file, referrers).forEach(path -> addVerifyTarget(byModule, path)));
    leftCallers.forEach(file -> addVerifyTarget(byModule, file));
    return Collections.unmodifiableMap(
        byModule.entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> List.copyOf(entry.getValue()),
                    (first, ignored) -> first,
                    LinkedHashMap::new)));
  }

  // A deleted file has nothing to diagnose, but its callers still do. Only Java is diagnosed: a
  // Kotlin or Groovy caller, found by its compiled class, is a source javac cannot read, which the
  // answer names as not checked (uncheckedCallers).
  private void addVerifyTarget(final Map<String, Set<Path>> byModule, final Path file) {
    if (Files.notExists(file) || !FileUtil.isJavaFile(file)) {
      return;
    }

    moduleDirOf(file)
        .ifPresent(
            dir ->
                byModule
                    .computeIfAbsent(
                        SourceLayout.moduleRel(state.root(), dir), k -> new LinkedHashSet<>())
                    .add(file));
  }

  // A module descriptor is named by no file, yet decides what every source of its module may read,
  // so all of them see it; the modules reading it are left to the sync the answer suggests.
  private List<Path> seenBy(final Path file, final Map<Path, List<Path>> referrers) {
    return file.getFileName().toString().equals(LatheLayout.MODULE_INFO_JAVA)
        ? moduleSources(file)
        : referrers.getOrDefault(file, List.of());
  }

  // Each changed file's referrers, found once for both the files to check and the modules to sync,
  // as reading the mirrored classes takes a second on a large reactor. A module or package
  // declaration has none: no file names it, and its reach is its module's.
  private Map<Path, List<Path>> referrersOf(final List<Path> changed) {
    final List<Path> files =
        changed.stream().distinct().filter(file -> !isDeclaration(file)).toList();
    final Map<Path, Set<Path>> byBytecode = bytecodeReferrers(files);
    return files.stream()
        .collect(
            Collectors.toUnmodifiableMap(
                file -> file, file -> referrers(file, byBytecode.getOrDefault(file, Set.of()))));
  }

  private static boolean isDeclaration(final Path file) {
    final String name = file.getFileName().toString();
    return name.equals(LatheLayout.MODULE_INFO_JAVA) || name.equals(LatheLayout.PACKAGE_INFO_JAVA);
  }

  private List<Path> moduleSources(final Path descriptor) {
    final Optional<Path> moduleDir = moduleDirOf(descriptor);
    return state.registry().allConfigs().stream()
        .filter(config -> moduleDir.filter(config.moduleDir()::equals).isPresent())
        .flatMap(config -> config.searchRoots().stream())
        .flatMap(
            root -> IOUtil.unchecked(() -> FileUtil.walkFiles(root, FileUtil::isJavaFile)).stream())
        .toList();
  }

  // The files that name the file's type, a token match that may over-select (an extra candidate
  // just compiles clean), and those whose compiled classes use a class of the file, which a caller
  // reaching it through a chain of calls never names.
  private List<Path> referrers(final Path file, final Set<Path> byBytecode) {
    final Set<Path> reach = reach(file);
    return Stream.concat(
            state.candidateIndex().candidateUris(FileUtil.javaTypeName(file)).stream()
                .map(LatheUri::toPath),
            byBytecode.stream())
        .filter(path -> !path.equals(file))
        .filter(path -> moduleDirOf(path).filter(reach::contains).isPresent())
        .distinct()
        .toList();
  }

  private Set<Path> reach(final Path file) {
    return moduleDirOf(file).map(state.moduleGraph()::downstreamModuleDirs).orElse(Set.of());
  }

  // One pass over the mirrored classes of the trees the changed files reach, as the last write left
  // them, read only when verify_change asks: each changed file's referrers by their sources.
  private Map<Path, Set<Path>> bytecodeReferrers(final List<Path> files) {
    final Map<Path, TypeNeedles> needles =
        files.stream().collect(Collectors.toUnmodifiableMap(file -> file, this::typeNeedles));
    final Map<Path, Set<Path>> found = new HashMap<>();
    for (final ModuleSourceConfig config : state.registry().allConfigs()) {
      final List<Path> seeing =
          files.stream().filter(file -> reach(file).contains(config.moduleDir())).toList();
      if (seeing.isEmpty()) {
        continue;
      }

      for (final Path classFile :
          IOUtil.unchecked(
              () -> FileUtil.walkFiles(config.latheClassesDir(), FileUtil::isClassFile))) {
        addReferrer(found, config, classFile, seeing, needles);
      }
    }

    return Map.copyOf(found);
  }

  private static void addReferrer(
      final Map<Path, Set<Path>> found,
      final ModuleSourceConfig config,
      final Path classFile,
      final List<Path> seeing,
      final Map<Path, TypeNeedles> needles) {
    final List<String> constants = ClassFileTypeScanner.constantStrings(classFile);
    final List<Path> using =
        seeing.stream().filter(file -> needles.get(file).foundIn(constants)).toList();
    if (using.isEmpty()) {
      return;
    }

    ClassOutputs.sourceOf(config, classFile)
        .ifPresent(
            source ->
                using.forEach(
                    file -> found.computeIfAbsent(file, k -> new HashSet<>()).add(source)));
  }

  // The file's classes in internal form: those the mirror holds, and its own type, which a deleted
  // or renamed type has no class for any more, with any type nested in it.
  private TypeNeedles typeNeedles(final Path file) {
    final Set<String> types =
        state
            .registry()
            .moduleSourceFor(file)
            .map(
                config ->
                    Stream.concat(
                            config.sourceRootOf(file).stream()
                                .map(root -> FileUtil.slashed(root.relativize(file)))
                                .map(path -> path.substring(0, path.length() - ".java".length())),
                            ClassOutputs.classNamesOf(config, file).stream()
                                .map(name -> name.replace('.', '/')))
                        .collect(Collectors.toUnmodifiableSet()))
            .orElse(Set.of());
    return new TypeNeedles(types);
  }

  // How a class's constant pool names a type: a class entry for it or a type nested in it, and
  // L<type>; inside a descriptor or signature, also as L<type>$ or L<type><.
  private record TypeNeedles(Set<String> types, List<String> nested, List<String> inSignatures) {
    TypeNeedles {
      types = Set.copyOf(types);
      nested = List.copyOf(nested);
      inSignatures = List.copyOf(inSignatures);
    }

    TypeNeedles(final Set<String> types) {
      this(
          types,
          types.stream().map("%s$"::formatted).toList(),
          types.stream()
              .flatMap(
                  type ->
                      Stream.of(
                          "L%s;".formatted(type), "L%s$".formatted(type), "L%s<".formatted(type)))
              .toList());
    }

    boolean foundIn(final List<String> constants) {
      return constants.stream().anyMatch(this::names);
    }

    private boolean names(final String constant) {
      return types.contains(constant)
          || nested.stream().anyMatch(constant::startsWith)
          || inSignatures.stream().anyMatch(constant::contains);
    }
  }

  private Optional<Path> moduleDirOf(final Path file) {
    return state.registry().moduleSourceFor(file).map(ModuleSourceConfig::moduleDir);
  }

  // Placement (module rel + test/production) for each path that maps to a reactor module. Lets
  // analyze_change split references by module and into production vs test.
  Map<Path, ModulePlacement> classifyPaths(final List<Path> paths) {
    final Map<Path, ModulePlacement> placements = new LinkedHashMap<>();
    for (final Path path : paths) {
      state
          .registry()
          .moduleSourceFor(path)
          .ifPresent(
              config ->
                  placements.put(
                      path,
                      new ModulePlacement(state.layout().moduleRel(config), config.isTestTree())));
    }

    return Map.copyOf(placements);
  }

  // The reactor remainder a cross-module change may break: modules that transitively depend on the
  // changed ones. verify_change hands these to `mvn`, not recompile (codegen/AP are Maven's job).
  int reactorModuleCount() {
    return (int)
        state.registry().allConfigs().stream()
            .map(ModuleSourceConfig::moduleDir)
            .distinct()
            .count();
  }

  List<LatheModuleSummary> moduleSummaries() {
    return moduleSummaries(state.registry().allConfigs(), state.root(), state.moduleGraph());
  }

  static List<LatheModuleSummary> moduleSummaries(
      final List<ModuleSourceConfig> configs,
      final Path workspaceRoot,
      final WorkspaceModuleGraph graph) {
    final var launches = new LaunchReader(workspaceRoot);
    return configs.stream()
        .collect(Collectors.groupingBy(ModuleSourceConfig::moduleDir))
        .entrySet()
        .stream()
        .map(
            module -> {
              final String rel = SourceLayout.moduleRel(workspaceRoot, module.getKey());
              return new LatheModuleSummary(
                  rel,
                  graph.upstreamModuleDirs(module.getKey()).stream()
                      .map(dir -> SourceLayout.moduleRel(workspaceRoot, dir))
                      .sorted()
                      .toList(),
                  module.getValue().stream().anyMatch(ModuleSourceConfig::isTestTree),
                  launches.testCaptured(rel));
            })
        .sorted(Comparator.comparing(LatheModuleSummary::module))
        .toList();
  }

  // Only the dependents a change can reach: those with a file naming a changed type or referring to
  // it in its compiled classes. A test class, or a type no dependent uses, reaches none, so no sync
  // is suggested for it.
  private List<String> downstreamModuleRels(
      final List<Path> changed, final Map<Path, List<Path>> referrers) {
    final Set<Path> changedModuleDirs =
        changed.stream()
            .map(this::moduleDirOf)
            .flatMap(Optional::stream)
            .collect(Collectors.toUnmodifiableSet());
    return changed.stream()
        .flatMap(file -> reachedModuleDirs(file, referrers))
        .filter(dir -> !changedModuleDirs.contains(dir))
        .distinct()
        .map(dir -> SourceLayout.moduleRel(state.root(), dir))
        .sorted()
        .toList();
  }

  // A module or package declaration is named by no file, yet changes what every dependent sees.
  private Stream<Path> reachedModuleDirs(final Path file, final Map<Path, List<Path>> referrers) {
    if (isDeclaration(file)) {
      return moduleDirOf(file)
          .map(state.moduleGraph()::downstreamModuleDirs)
          .orElse(Set.of())
          .stream();
    }

    return referrers.getOrDefault(file, List.of()).stream()
        .map(this::moduleDirOf)
        .flatMap(Optional::stream);
  }

  // What an MCP answer cannot vouch for after its pass: files whose write failed and that are
  // unchanged since (fixed in code, retried on their next edit), and what only a sync can fix.
  LatheNotes notes(final List<Path> files, final boolean buildFilesPending) {
    final List<String> notCompiled = stillFailing().map(this::failedLine).sorted().toList();
    final Stream<String> pendingBuildFiles =
        buildFilesPending
            ? Stream.of("build files changed — run `%s`".formatted(LatheLayout.SYNC_SCRIPT_COMMAND))
            : Stream.empty();
    final List<String> notChecked =
        files.stream().filter(this::uncheckable).map(this::notCheckedReason).toList();
    return new LatheNotes(
        notCompiled,
        Stream.concat(pendingBuildFiles, state.manifest().loadProblem().stream()).toList(),
        state.manifest().loadProblem().isPresent(),
        notChecked,
        writePass.reactorBuildInProgress(),
        Arrays.stream(UnreadLanguage.values())
            .map(this::unreadSources)
            .flatMap(Optional::stream)
            .toList());
  }

  private Optional<UnreadSources> unreadSources(final UnreadLanguage language) {
    final List<String> modules =
        state.manifest().unreadModuleDirs(language).stream()
            .map(dir -> SourceLayout.displayed(FileUtil.slashed(state.root().relativize(dir))))
            .sorted()
            .toList();
    return modules.isEmpty()
        ? Optional.empty()
        : Optional.of(new UnreadSources(language.displayName(), modules));
  }

  // The first source that fails to compile in the file's module or one it depends on: code it runs
  // would be the last good classes, not what is on disk.
  Optional<String> failingUnder(final Path file) {
    return moduleDirOf(file).flatMap(this::failingInModule);
  }

  // The same for a module, by its directory under .lathe.
  Optional<String> failingInModule(final Path moduleDir) {
    final Set<Path> reach = moduleReach(moduleDir);
    return stillFailing()
        .filter(entry -> moduleDirOf(entry.getKey()).filter(reach::contains).isPresent())
        .map(this::failedLine)
        .sorted()
        .findFirst();
  }

  // A module and every module it depends on: the code a run of it runs.
  private Set<Path> moduleReach(final Path moduleDir) {
    return Stream.concat(
            Stream.of(moduleDir), state.moduleGraph().upstreamModuleDirs(moduleDir).stream())
        .collect(Collectors.toUnmodifiableSet());
  }

  // Before a run launches: the first error in code it runs, in its module or one it depends on. A
  // failed build compile first; then, as verify_change checks them, every change since the build
  // and the files that use it, compiled now, as a class deleted or changed under an unchanged file
  // (a test, its helper, another test of the package) fails no build compile; and the generated
  // callers a rename left. Only the run's modules: a change outside them cannot reach the code it
  // runs. It reads verify_change's record of what was written, and leaves it for verify_change.
  // Nothing changed: nothing compiled.
  CompletableFuture<Optional<String>> failingForRun(final Path moduleDir) {
    final Optional<String> failed = failingInModule(moduleDir);
    if (failed.isPresent()) {
      return CompletableFuture.completedFuture(failed);
    }

    final Set<Path> reach = moduleReach(moduleDir);
    final List<Path> changed =
        differingFromBuild(
            Stream.concat(
                    writePass.writtenSinceVerify().stream(),
                    state.registry().allConfigs().stream()
                        .filter(config -> reach.contains(config.moduleDir()))
                        .flatMap(config -> unbuiltSources(config).stream()))
                .filter(file -> moduleDirOf(file).filter(reach::contains).isPresent())
                .distinct()
                .toList());
    final List<Path> leftCallers = stillLeftByRename();
    if (changed.isEmpty() && leftCallers.isEmpty()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    final Map<Path, List<Path>> referrers = referrersOf(changed);
    final List<Path> targets =
        Stream.of(
                changed.stream(),
                changed.stream().flatMap(file -> seenBy(file, referrers).stream()),
                leftCallers.stream())
            .flatMap(files -> files)
            .distinct()
            .filter(file -> Files.exists(file) && FileUtil.isJavaFile(file))
            .filter(file -> moduleDirOf(file).filter(reach::contains).isPresent())
            .toList();
    if (targets.isEmpty()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    final Map<String, String> contents =
        targets.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    LatheUri::fromPath, ChangeVerifier::read, (first, sameFile) -> first));
    final Set<String> changedUris =
        changed.stream().map(LatheUri::fromPath).collect(Collectors.toUnmodifiableSet());
    return check(contents, changedUris, List.of(), CompilationWorker.NOT_CANCELLED)
        .thenApply(analyses -> firstErrorIn(targets, analyses));
  }

  // The earliest error by path then line, so the same change answers the same way every run.
  private Optional<String> firstErrorIn(
      final List<Path> targets, final Map<String, List<Diagnostic>> analyses) {
    return targets.stream()
        .flatMap(
            file ->
                firstBuildError(analyses.getOrDefault(LatheUri.fromPath(file), List.of()))
                    .map(
                        diagnostic ->
                            "%s:%s"
                                .formatted(
                                    FileUtil.slashed(state.root().relativize(file)),
                                    WritePass.errorLine(diagnostic)))
                    .stream())
        .sorted()
        .findFirst();
  }

  // The earliest compile error in a file's diagnostics that the build would report too: -Werror's
  // error belongs to no file, and Lathe's own package-directory error is not javac's (the build
  // compiles and runs such a file).
  static Optional<Diagnostic> firstBuildError(final List<Diagnostic> diagnostics) {
    return WritePass.ownDiagnostics(diagnostics).stream()
        .filter(diagnostic -> diagnostic.getSeverity() == DiagnosticSeverity.Error)
        .filter(diagnostic -> !isPackageDirectoryError(diagnostic))
        .min(Comparator.comparingInt(diagnostic -> diagnostic.getRange().getStart().getLine()));
  }

  private static boolean isPackageDirectoryError(final Diagnostic diagnostic) {
    return diagnostic.getCode() != null
        && PackageDirectory.CODE.equals(diagnostic.getCode().getLeft());
  }

  // A source's text, for a check that compiles it as it is on disk.
  static String read(final Path file) {
    return IOUtil.unchecked(() -> Files.readString(file, StandardCharsets.UTF_8));
  }

  private Stream<Map.Entry<Path, WritePass.FailedWrite>> stillFailing() {
    return writePass.failedCompiles().entrySet().stream()
        .filter(
            entry ->
                WritePass.failedUnchanged(entry.getValue(), WritePass.mtimeMillis(entry.getKey())));
  }

  private String failedLine(final Map.Entry<Path, WritePass.FailedWrite> entry) {
    return "%s:%s".formatted(state.root().relativize(entry.getKey()), entry.getValue().error());
  }

  // A module compiles it, or it is among a dependency's or the JDK's extracted sources.
  boolean routed(final Path file) {
    return !(router.routeCompiler(LatheUri.fromPath(file)) instanceof CompilerRoute.Missing);
  }

  // No module compiles it, or it names a file the build never had: a mistyped path, not a deletion.
  private boolean uncheckable(final Path file) {
    return router.routeCompiler(LatheUri.fromPath(file)) instanceof CompilerRoute.Missing
        || (Files.notExists(file) && backToBuilt(file, new HashMap<>()));
  }

  // A sync helps only a file of a module the build compiles that has no mirror yet: in a synced
  // module (a sync writes each module it built a .lathe directory, one without sources too) the
  // file is outside its source roots, and outside the built modules the build left it out.
  private String notCheckedReason(final Path file) {
    if (!file.startsWith(state.root())) {
      return "%s is outside the workspace, so Lathe cannot check it".formatted(file);
    }

    final Path relative = state.root().relativize(file);
    if (Files.notExists(file)) {
      return "%s does not exist, and the last build did not compile it".formatted(relative);
    }

    return state
        .manifest()
        .builtModuleDirOf(file)
        .map(moduleDir -> FileUtil.slashed(state.root().relativize(moduleDir)))
        .map(
            moduleRel ->
                Files.isDirectory(SourceLayout.moduleLatheDir(state.root(), moduleRel))
                    ? "%s is in no source root of module %s, so a sync does not add it"
                        .formatted(relative, SourceLayout.displayed(moduleRel))
                    : "%s is in no synced module — run `%s`"
                        .formatted(relative, LatheLayout.SYNC_SCRIPT_COMMAND))
        .orElseGet(
            () ->
                "%s is outside the modules the last build compiled (a profile may leave its module out), so Lathe cannot check it"
                    .formatted(relative));
  }

  // What verify_change checks after a reconcile: the changed files (those named, or else everything
  // written or changed since the build) and, per module, the files that see them.
  VerifyPlan plan(final ReconcileOutcome outcome, final List<Path> files) {
    if (outcome.deferral() != ReconcileOutcome.DeferReason.NONE) {
      return new VerifyPlan(outcome, List.of(), Map.of(), List.of(), 0, Map.of(), List.of());
    }

    final List<Path> touched =
        files.isEmpty()
            ? Stream.of(outcome.reacted(), writePass.takeWrittenSinceVerify(), changedSinceBuild())
                .flatMap(List::stream)
                .distinct()
                .toList()
            : files;
    final List<Path> changed = differingFromBuild(touched);
    final List<Path> leftCallers = files.isEmpty() ? stillLeftByRename() : List.of();
    if (changed.isEmpty() && leftCallers.isEmpty()) {
      return new VerifyPlan(outcome, List.of(), Map.of(), List.of(), 0, Map.of(), List.of());
    }

    final Map<Path, List<Path>> referrers = referrersOf(changed);
    return new VerifyPlan(
        outcome,
        changed,
        verifyTargetsByModule(changed, referrers, leftCallers),
        downstreamModuleRels(changed, referrers),
        reactorModuleCount(),
        failedWrites(changed),
        uncheckedCallers(referrers));
  }

  // The callers javac cannot read: a Kotlin or Groovy source, found by its compiled class.
  private static List<Path> uncheckedCallers(final Map<Path, List<Path>> referrers) {
    return referrers.values().stream()
        .flatMap(List::stream)
        .filter(file -> !FileUtil.isJavaFile(file) && Files.exists(file))
        .distinct()
        .sorted()
        .toList();
  }

  // The changed files the build's compile fails for a cause of their own, unchanged since, with
  // those causes: their classes are those of their last good compile, so their callers are checked
  // against their edited source.
  private Map<Path, List<Diagnostic>> failedWrites(final List<Path> changed) {
    final Set<Path> changedFiles = Set.copyOf(changed);
    return stillFailing()
        .filter(entry -> changedFiles.contains(entry.getKey()))
        .filter(entry -> !entry.getValue().causes().isEmpty())
        .collect(
            Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().causes()));
  }

  record VerifyPlan(
      ReconcileOutcome outcome,
      List<Path> changed,
      Map<String, List<Path>> targets,
      List<String> downstream,
      int moduleCount,
      Map<Path, List<Diagnostic>> failedWrites,
      List<Path> uncheckedCallers) {

    VerifyPlan {
      ValidCheck.check().notNull(outcome, "outcome").validate();
      changed = List.copyOf(changed);
      // In the order verifyTargetsByModule gives (the changed files' modules first): the answer
      // lists the modules that way.
      targets = Collections.unmodifiableMap(new LinkedHashMap<>(targets));
      downstream = List.copyOf(downstream);
      failedWrites = Map.copyOf(failedWrites);
      uncheckedCallers = List.copyOf(uncheckedCallers);
    }
  }
}
