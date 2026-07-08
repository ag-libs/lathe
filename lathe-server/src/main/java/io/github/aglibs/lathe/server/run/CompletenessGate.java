package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
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
    final Map<Path, Set<String>> touched = latheTouchedDirs(data, workspaceRoot);
    final var reasons = new ArrayList<String>();
    for (final Map.Entry<Path, Set<String>> entry : touched.entrySet()) {
      final Path moduleDir = entry.getKey();
      final Set<String> sourceTrees = entry.getValue();
      reasons.addAll(LatheLock.awaitAndRead(moduleDir, () -> checkModule(moduleDir, sourceTrees)));
    }

    return reasons.isEmpty() ? CompletenessResult.open() : CompletenessResult.blocked(reasons);
  }

  private static List<String> checkModule(final Path moduleDir, final Set<String> sourceTrees)
      throws IOException {
    final var reasons = new ArrayList<String>();
    for (final String sourceTree : sourceTrees) {
      final Path paramsFile = moduleDir.resolve(LatheLayout.paramsFileName(sourceTree));
      if (!Files.exists(paramsFile)) {
        reasons.add("%s: %s missing".formatted(moduleDir, paramsFile.getFileName()));
        continue;
      }

      final Path outputDir = moduleDir.resolve(sourceTree);
      if (!isNonEmptyDirectory(outputDir)) {
        reasons.add("%s: %s missing or empty".formatted(moduleDir, sourceTree));
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
      final TestLaunchData data, final Path workspaceRoot) {
    final Path latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).normalize();
    final List<Path> candidates =
        Stream.concat(
                Stream.concat(data.modulePath().stream(), data.classPath().stream()),
                data.patchModules().values().stream())
            .map(path -> Path.of(ReactorRewrite.toLathe(path, workspaceRoot)).normalize())
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
