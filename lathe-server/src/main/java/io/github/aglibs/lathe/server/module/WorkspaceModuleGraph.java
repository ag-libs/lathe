package io.github.aglibs.lathe.server.module;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Immutable reactor module dependency graph derived from remapped classpath entries.
 *
 * <p>Each {@link ModuleSourceConfig#latheClassesDir()} value is a unique output directory under
 * {@code .lathe/}. A config directly depends on another if its {@link
 * ModuleSourceConfig#remappedClasspath()} or {@link ModuleSourceConfig#remappedModulepath()}
 * contains the other's {@code latheClassesDir()}.
 */
public final class WorkspaceModuleGraph {

  private static final Logger LOG = Logger.getLogger(WorkspaceModuleGraph.class.getName());

  private final Map<Path, List<ModuleSourceConfig>> configsByModuleDir;
  private final Map<Path, Set<Path>> directDeps;
  private final Map<Path, Set<Path>> downstreamOf;

  private WorkspaceModuleGraph(
      final Map<Path, List<ModuleSourceConfig>> configsByModuleDir,
      final Map<Path, Set<Path>> directDeps,
      final Map<Path, Set<Path>> downstreamOf) {
    this.configsByModuleDir = configsByModuleDir;
    this.directDeps = Map.copyOf(directDeps);
    this.downstreamOf = downstreamOf;
  }

  public static WorkspaceModuleGraph build(final List<ModuleSourceConfig> allConfigs) {
    final Map<Path, List<ModuleSourceConfig>> configsByModuleDir =
        allConfigs.stream().collect(Collectors.groupingBy(ModuleSourceConfig::moduleDir));

    final Map<Path, Path> classDirToModuleDir =
        allConfigs.stream()
            .collect(
                Collectors.toMap(
                    ModuleSourceConfig::latheClassesDir,
                    ModuleSourceConfig::moduleDir,
                    (a, ignored) -> a));

    final Map<Path, Set<Path>> directDeps =
        allConfigs.stream()
            .collect(
                Collectors.groupingBy(
                    ModuleSourceConfig::moduleDir,
                    Collectors.flatMapping(
                        config ->
                            Stream.concat(
                                    config.remappedClasspath().stream(),
                                    config.remappedModulepath().stream())
                                .map(classDirToModuleDir::get)
                                .filter(dep -> dep != null && !dep.equals(config.moduleDir())),
                        Collectors.toUnmodifiableSet())));

    final var directDependents = new HashMap<Path, Set<Path>>();
    for (final var m : configsByModuleDir.keySet()) {
      directDependents.put(m, new HashSet<>());
    }
    for (final var entry : directDeps.entrySet()) {
      for (final var dep : entry.getValue()) {
        directDependents.computeIfAbsent(dep, k -> new HashSet<>()).add(entry.getKey());
      }
    }

    final Map<Path, Set<Path>> downstreamOf =
        configsByModuleDir.keySet().stream()
            .collect(Collectors.toMap(m -> m, m -> transitiveDownstream(m, directDependents)));

    LOG.fine(
        () ->
            "[module-graph] %d module(s): %s"
                .formatted(
                    configsByModuleDir.size(),
                    configsByModuleDir.keySet().stream()
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .sorted()
                        .collect(Collectors.joining(", "))));

    return new WorkspaceModuleGraph(configsByModuleDir, directDeps, downstreamOf);
  }

  public List<ModuleSourceConfig> configsForModule(final Path moduleDir) {
    return configsByModuleDir.getOrDefault(moduleDir, List.of());
  }

  public List<ModuleSourceConfig> referenceSearchScope(final ModuleSourceConfig declaring) {
    return downstreamOf.getOrDefault(declaring.moduleDir(), Set.of(declaring.moduleDir())).stream()
        .flatMap(dir -> configsByModuleDir.getOrDefault(dir, List.of()).stream())
        .toList();
  }

  // The reactor modules on this one's class or module path: with Maven, transitive ones too.
  public Set<Path> upstreamModuleDirs(final Path moduleDir) {
    return directDeps.getOrDefault(moduleDir, Set.of());
  }

  // The module itself plus every module transitively depending on it -- the set whose open files
  // must recompile once this module's mirror bytecode changes.
  public Set<Path> downstreamModuleDirs(final Path moduleDir) {
    return downstreamOf.getOrDefault(moduleDir, Set.of(moduleDir));
  }

  // The given modules ordered upstream-first, so recompiling in this order sees fresh upstream
  // bytecode. Ranking by full downstream-set size is a valid topological order: a dependency's
  // downstream set strictly contains each of its dependents'.
  public List<Path> upstreamFirst(final Set<Path> moduleDirs) {
    return moduleDirs.stream()
        .sorted(Comparator.comparingInt((Path dir) -> downstreamModuleDirs(dir).size()).reversed())
        .toList();
  }

  // Groups trees given upstream-first into levels: a tree lands one level after the latest tree on
  // its class or module path (a test tree's path holds its own main tree), so the trees of one
  // level are independent of each other and can be written together.
  public static List<List<ModuleSourceConfig>> levels(
      final List<ModuleSourceConfig> upstreamFirst) {
    final var levelOf = new HashMap<ModuleSourceConfig, Integer>();
    for (final var tree : upstreamFirst) {
      levelOf.put(
          tree,
          levelOf.entrySet().stream()
              .filter(earlier -> dependsOn(tree, earlier.getKey()))
              .mapToInt(earlier -> earlier.getValue() + 1)
              .max()
              .orElse(0));
    }

    return upstreamFirst.stream()
        .collect(Collectors.groupingBy(levelOf::get, TreeMap::new, Collectors.toList()))
        .values()
        .stream()
        .map(List::copyOf)
        .toList();
  }

  private static boolean dependsOn(final ModuleSourceConfig tree, final ModuleSourceConfig other) {
    return tree.remappedClasspath().contains(other.latheClassesDir())
        || tree.remappedModulepath().contains(other.latheClassesDir());
  }

  private static Set<Path> transitiveDownstream(
      final Path root, final Map<Path, Set<Path>> directDependents) {
    final var visited = new HashSet<Path>();
    visited.add(root);
    final var queue = new ArrayDeque<Path>();
    queue.add(root);
    while (!queue.isEmpty()) {
      for (final var dep : directDependents.getOrDefault(queue.poll(), Set.of())) {
        if (visited.add(dep)) {
          queue.add(dep);
        }
      }
    }
    return Set.copyOf(visited);
  }
}
