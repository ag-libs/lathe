package io.github.aglibs.lathe.server.run;

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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class CompletenessGate {

  private CompletenessGate() {}

  public static CompletenessResult verify(final TestLaunchData data, final Path workspaceRoot)
      throws IOException {
    final List<String> paths =
        Stream.concat(
                Stream.concat(data.modulePath().stream(), data.classPath().stream()),
                data.patchModules().values().stream())
            .toList();
    return verify(paths, workspaceRoot);
  }

  public static CompletenessResult verify(final MainLaunchData data, final Path workspaceRoot)
      throws IOException {
    final List<String> paths =
        Stream.concat(data.modulePath().stream(), data.classPath().stream()).toList();
    return verify(paths, workspaceRoot);
  }

  private static CompletenessResult verify(final List<String> paths, final Path workspaceRoot)
      throws IOException {
    final Map<Path, Set<String>> touched = latheTouchedDirs(paths, workspaceRoot);
    final Path latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).normalize();
    final var reasons = new ArrayList<String>();
    for (final Map.Entry<Path, Set<String>> entry : touched.entrySet()) {
      final Path moduleDir = entry.getKey();
      final String moduleRel = latheDir.relativize(moduleDir).toString();
      final Set<String> sourceTrees = entry.getValue();
      reasons.addAll(
          LatheLock.awaitAndRead(moduleDir, () -> checkModule(moduleDir, moduleRel, sourceTrees)));
    }

    return reasons.isEmpty() ? CompletenessResult.open() : CompletenessResult.blocked(reasons);
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
                    LatheLayout.TEST_CLASSES_DIR.equals(sourceTree) ? "test classes" : "classes",
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
      final List<String> paths, final Path workspaceRoot) {
    final Path latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).normalize();
    final List<Path> candidates =
        paths.stream()
            .filter(path -> !nothingToBuild(path, workspaceRoot))
            .map(path -> latheDirOf(path, workspaceRoot))
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
  private static boolean nothingToBuild(final String path, final Path workspaceRoot) {
    final Path latheDir = latheDirOf(path, workspaceRoot);
    final Path params =
        latheDir.resolveSibling(LatheLayout.paramsFileName(latheDir.getFileName().toString()));
    return !Files.exists(Path.of(path)) && !Files.exists(latheDir) && !Files.exists(params);
  }

  private static Path latheDirOf(final String path, final Path workspaceRoot) {
    return Path.of(ReactorRewrite.toLathe(path, workspaceRoot)).normalize();
  }

  private static boolean isLatheSourceTreeDir(final Path candidate, final Path latheDir) {
    return candidate.startsWith(latheDir)
        && !candidate.equals(latheDir)
        && candidate.getParent() != null
        && isSourceTreeDir(candidate.getFileName().toString());
  }

  private static boolean isSourceTreeDir(final String name) {
    return LatheLayout.CLASSES_DIR.equals(name) || LatheLayout.TEST_CLASSES_DIR.equals(name);
  }
}
