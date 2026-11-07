package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class CompletenessGate {

  private CompletenessGate() {}

  public static CompletenessResult verify(
      final TestLaunchData data, final Path workspaceRoot, final ReactorRewrite reactorRewrite)
      throws IOException {
    final List<String> paths =
        Stream.concat(
                Stream.concat(data.modulePath().stream(), data.classPath().stream()),
                data.patchModules().values().stream())
            .toList();
    return verify(paths, workspaceRoot, reactorRewrite);
  }

  public static CompletenessResult verify(
      final MainLaunchData data, final Path workspaceRoot, final ReactorRewrite reactorRewrite)
      throws IOException {
    final List<String> paths =
        Stream.concat(data.modulePath().stream(), data.classPath().stream()).toList();
    return verify(paths, workspaceRoot, reactorRewrite);
  }

  private static CompletenessResult verify(
      final List<String> paths, final Path workspaceRoot, final ReactorRewrite reactorRewrite)
      throws IOException {
    final Map<Path, Set<String>> touched = latheTouchedDirs(paths, workspaceRoot, reactorRewrite);
    final Path latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).normalize();
    final var reasons = new ArrayList<>(missingJars(paths, reactorRewrite));
    for (final Map.Entry<Path, Set<String>> entry : touched.entrySet()) {
      final Path moduleDir = entry.getKey();
      final String moduleRel = FileUtil.slashed(latheDir.relativize(moduleDir));
      final Set<String> sourceTrees = entry.getValue();
      reasons.addAll(
          LatheLock.awaitAndRead(
              LatheLayout.moduleLockFile(moduleDir),
              () -> checkModule(moduleDir, moduleRel, sourceTrees)));
    }

    return reasons.isEmpty()
        ? CompletenessResult.open(lastBuildNotes(paths, workspaceRoot, reactorRewrite))
        : CompletenessResult.blocked(reasons);
  }

  // A jar the launch names that no mirror stands for and that is gone: the test JVM would fail to
  // load its classes midway. Develocity's test acceleration starts the tests through a launcher it
  // copies to a temporary directory and deletes after the run; a cache cleanup removes a dependency
  // jar. A missing directory is left alone: builds list output directories never created.
  private static List<String> missingJars(
      final List<String> paths, final ReactorRewrite reactorRewrite) {
    return paths.stream()
        .map(path -> Path.of(path).normalize())
        .filter(path -> path.toString().endsWith(".jar"))
        .filter(path -> !reactorRewrite.mirrors().containsKey(path))
        .filter(path -> !Files.exists(path))
        .distinct()
        .map(
            path ->
                """
                %s no longer exists: capture the launch again (with Develocity's test \
                acceleration off, if that started the tests: it deletes its launcher's jars)\
                """
                    .formatted(path))
        .toList();
  }

  // A directory in a reactor module's build directory that no mirror stands for, such as a copy of
  // the classes the build tests instead of its compile output: the launch runs it as the last build
  // left it, so it may not hold the latest edits.
  private static List<String> lastBuildNotes(
      final List<String> paths, final Path workspaceRoot, final ReactorRewrite reactorRewrite) {
    final Set<Path> buildDirs =
        reactorRewrite.mirrors().keySet().stream()
            .map(Path::getParent)
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());
    final Path root = workspaceRoot.normalize();
    return paths.stream()
        .map(path -> Path.of(path).normalize())
        .filter(path -> !reactorRewrite.mirrors().containsKey(path))
        .filter(path -> buildDirs.stream().anyMatch(path::startsWith))
        .filter(Files::isDirectory)
        .distinct()
        .map(
            path ->
                "%s runs as the last build left it, not from .lathe: it may not hold your latest edits"
                    .formatted(FileUtil.slashed(root.relativize(path))))
        .toList();
  }

  // A tree is incomplete when its compile was never captured (no params) or its classes never
  // reached the mirror; either way a sync of the module builds it.
  private static List<String> checkModule(
      final Path moduleDir, final String moduleRel, final Set<String> sourceTrees)
      throws IOException {
    final var reasons = new ArrayList<String>();
    for (final String sourceTree : sourceTrees) {
      final boolean captured =
          Files.exists(moduleDir.resolve(LatheLayout.paramsFileName(sourceTree)));
      if (!captured || !isNonEmptyDirectory(moduleDir.resolve(sourceTree))) {
        reasons.add(
            "%s %s are not compiled into .lathe yet — run %s"
                .formatted(
                    moduleRel,
                    treeLabel(sourceTree),
                    LatheLayout.scopedSyncCommand(List.of(moduleRel))));
      }
    }

    return reasons;
  }

  private static boolean isNonEmptyDirectory(final Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return false;
    }

    try (final Stream<Path> entries = Files.list(dir)) {
      return entries.findAny().isPresent();
    }
  }

  private static Map<Path, Set<String>> latheTouchedDirs(
      final List<String> paths, final Path workspaceRoot, final ReactorRewrite reactorRewrite) {
    final Path latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).normalize();
    final List<Path> candidates =
        paths.stream()
            .filter(path -> !nothingToBuild(path, reactorRewrite))
            .map(path -> latheDirOf(path, reactorRewrite))
            .filter(candidate -> isLatheSourceTreeDir(candidate, latheDir))
            .toList();

    return candidates.stream()
        .collect(
            Collectors.groupingBy(
                Path::getParent,
                LinkedHashMap::new,
                Collectors.mapping(
                    candidate -> candidate.getFileName().toString(),
                    Collectors.toCollection(LinkedHashSet::new))));
  }

  // Maven lists a module's own output dir even when the tree has no sources (the main tree of a
  // test-only module). One that neither Maven nor the mirror ever produced, with no params, has
  // nothing to wait for.
  private static boolean nothingToBuild(final String path, final ReactorRewrite reactorRewrite) {
    final Path latheDir = latheDirOf(path, reactorRewrite);
    final Path params =
        latheDir.resolveSibling(LatheLayout.paramsFileName(latheDir.getFileName().toString()));
    return !Files.exists(Path.of(path)) && !Files.exists(latheDir) && !Files.exists(params);
  }

  private static Path latheDirOf(final String path, final ReactorRewrite reactorRewrite) {
    return Path.of(reactorRewrite.toLathe(path)).normalize();
  }

  private static boolean isLatheSourceTreeDir(final Path candidate, final Path latheDir) {
    return candidate.startsWith(latheDir)
        && !candidate.equals(latheDir)
        && candidate.getParent() != null
        && isSourceTreeDir(candidate.getFileName().toString());
  }

  private static boolean isSourceTreeDir(final String name) {
    return LatheLayout.CLASSES_DIR.equals(name)
        || LatheLayout.TEST_CLASSES_DIR.equals(name)
        || name.startsWith(LatheLayout.SOURCE_SET_TREE_PREFIX);
  }

  // "test classes", "classes", or a Gradle source set's: "integrationTest classes".
  private static String treeLabel(final String sourceTree) {
    if (sourceTree.startsWith(LatheLayout.SOURCE_SET_TREE_PREFIX)) {
      return "%s classes"
          .formatted(sourceTree.substring(LatheLayout.SOURCE_SET_TREE_PREFIX.length()));
    }

    return LatheLayout.TEST_CLASSES_DIR.equals(sourceTree) ? "test classes" : "classes";
  }
}
