package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import io.github.aglibs.lathe.server.analysis.TextMentions;
import io.github.aglibs.lathe.server.engine.LatheModuleSummary;
import io.github.aglibs.lathe.server.engine.LatheNotes;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.lathe.server.module.WorkspaceModuleGraph;
import io.github.aglibs.lathe.server.run.LaunchReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// What an agent's verify_change and the notes on every MCP answer are built from: which sources
// differ from what the build compiled, which modules their callers are in, which files still fail
// to compile, and which a sync would add. Reads the write pass's state and the module graph; it
// changes nothing but what WritePass's own methods change.
final class ChangeVerifier {

  private final ServerEventLoop eventLoop;
  private final WorkspaceState state;
  private final FeatureRouter router;
  private final WritePass writePass;

  ChangeVerifier(
      final ServerEventLoop eventLoop,
      final WorkspaceState state,
      final FeatureRouter router,
      final WritePass writePass) {
    this.eventLoop = eventLoop;
    this.state = state;
    this.router = router;
    this.writePass = writePass;
  }

  // Where a name may survive as text: the sources whose tokens include it, and the resource roots.
  TextMentions.Sources textMentionSources(final String name) {
    return new TextMentions.Sources(
        state.candidateIndex().candidateUris(name).stream().map(LatheUri::toPath).toList(),
        state.manifest().resourceSourceDirs());
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
    return Files.notExists(file) ? compiled == null : FileUtil.hasContent(file, compiled);
  }

  // Files to diagnose for verify_change, by module: each changed file, then the files that mention
  // its type in its module or one downstream of it, the changed files' modules first.
  Map<String, List<Path>> verifyTargetsByModule(final List<Path> changed) {
    final Map<String, Set<Path>> byModule = new LinkedHashMap<>();
    changed.forEach(file -> addVerifyTarget(byModule, file));
    changed.forEach(file -> referrers(file).forEach(path -> addVerifyTarget(byModule, path)));
    return Collections.unmodifiableMap(
        byModule.entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> List.copyOf(entry.getValue()),
                    (first, ignored) -> first,
                    LinkedHashMap::new)));
  }

  // A deleted file has nothing to diagnose, but its callers still do.
  private void addVerifyTarget(final Map<String, Set<Path>> byModule, final Path file) {
    if (Files.notExists(file)) {
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

  // Matched by type-name token: may over-select (a file that only mentions the name) but never
  // misses a direct caller; an extra candidate just compiles clean.
  private List<Path> referrers(final Path file) {
    final Set<Path> reach =
        moduleDirOf(file).map(state.moduleGraph()::downstreamModuleDirs).orElse(Set.of());
    return state.candidateIndex().candidateUris(FileUtil.javaTypeName(file)).stream()
        .map(LatheUri::toPath)
        .filter(path -> !path.equals(file))
        .filter(path -> moduleDirOf(path).filter(reach::contains).isPresent())
        .toList();
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
                      new ModulePlacement(
                          SourceLayout.moduleRel(state.root(), config.moduleDir()),
                          config.isTestTree())));
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

  // Only the dependents a change can reach: those with a file naming a changed type. A test class,
  // or a type no dependent names, reaches none, so no sync is suggested for it.
  List<String> downstreamModuleRels(final List<Path> changed) {
    final Set<Path> changedModuleDirs =
        changed.stream()
            .map(this::moduleDirOf)
            .flatMap(Optional::stream)
            .collect(Collectors.toUnmodifiableSet());
    return changed.stream()
        .flatMap(this::reachedModuleDirs)
        .filter(dir -> !changedModuleDirs.contains(dir))
        .distinct()
        .map(dir -> SourceLayout.moduleRel(state.root(), dir))
        .sorted()
        .toList();
  }

  // A module or package declaration is named by no file, yet changes what every dependent sees.
  private Stream<Path> reachedModuleDirs(final Path file) {
    final String name = file.getFileName().toString();
    if (name.equals(LatheLayout.MODULE_INFO_JAVA) || name.equals(LatheLayout.PACKAGE_INFO_JAVA)) {
      return moduleDirOf(file)
          .map(state.moduleGraph()::downstreamModuleDirs)
          .orElse(Set.of())
          .stream();
    }

    return referrers(file).stream().map(this::moduleDirOf).flatMap(Optional::stream);
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
        notChecked,
        writePass.reactorBuildInProgress());
  }

  // The first source that fails to compile in the file's module or one it depends on: code it runs
  // would be the last good classes, not what is on disk.
  Optional<String> failingUnder(final Path file) {
    final Set<Path> reach =
        moduleDirOf(file)
            .map(
                dir ->
                    Stream.concat(
                        Stream.of(dir), state.moduleGraph().upstreamModuleDirs(dir).stream()))
            .orElse(Stream.empty())
            .collect(Collectors.toUnmodifiableSet());
    return stillFailing()
        .filter(entry -> moduleDirOf(entry.getKey()).filter(reach::contains).isPresent())
        .map(this::failedLine)
        .sorted()
        .findFirst();
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

  // No module compiles it, or it names a file the build never had: a mistyped path, not a deletion.
  private boolean uncheckable(final Path file) {
    return router.routeCompiler(file.toUri().toString()) instanceof CompilerRoute.Missing
        || (Files.notExists(file) && backToBuilt(file, new HashMap<>()));
  }

  // A sync helps only a file of a module the build compiles that has no mirror yet: in a synced
  // module (a sync writes each module it built a .lathe directory, one without sources too) the
  // file is outside its source roots, and outside the built modules the build left it out.
  private String notCheckedReason(final Path file) {
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
                        .formatted(relative, moduleRel.isEmpty() ? "." : moduleRel)
                    : "%s is in no synced module — run `%s`"
                        .formatted(relative, LatheLayout.SYNC_SCRIPT_COMMAND))
        .orElseGet(
            () ->
                "%s is outside the modules the last build compiled (a profile may leave its module out), so Lathe cannot check it"
                    .formatted(relative));
  }
}
