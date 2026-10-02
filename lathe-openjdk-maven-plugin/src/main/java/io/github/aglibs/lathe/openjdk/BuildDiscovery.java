package io.github.aglibs.lathe.openjdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolves the OpenJDK build output dir to sync. An explicit {@code -Dlathe.buildDir} wins;
 * otherwise the single configured build under {@code <root>/build} is discovered by its {@code
 * spec.gmk} (the marker {@code bash configure} writes). Zero or several configured builds fail
 * loudly, so the caller must disambiguate rather than the goal guessing a conf name.
 */
final class BuildDiscovery {

  private static final String BUILD_DIR = "build";
  private static final String SPEC_FILE = "spec.gmk";
  private static final String HINT = "run `bash configure` and `make`, or pass -Dlathe.buildDir";

  private BuildDiscovery() {}

  static Path resolve(final String explicitBuildDir, final Path workspaceRoot) throws IOException {
    if (explicitBuildDir != null && !explicitBuildDir.isBlank()) {
      return Path.of(explicitBuildDir);
    }

    return discover(workspaceRoot);
  }

  private static Path discover(final Path workspaceRoot) throws IOException {
    final Path buildRoot = workspaceRoot.resolve(BUILD_DIR);
    if (!Files.isDirectory(buildRoot)) {
      throw new IOException("no %s/ under %s — %s".formatted(BUILD_DIR, workspaceRoot, HINT));
    }

    final List<Path> configured = configuredBuilds(buildRoot);
    if (configured.isEmpty()) {
      throw new IOException("no configured build under %s — %s".formatted(buildRoot, HINT));
    }
    if (configured.size() > 1) {
      final String names =
          configured.stream()
              .map(p -> p.getFileName().toString())
              .collect(Collectors.joining(", "));
      throw new IOException(
          "multiple configured builds under %s (%s) — pass -Dlathe.buildDir=%s/<conf>"
              .formatted(buildRoot, names, BUILD_DIR));
    }

    return configured.getFirst();
  }

  private static List<Path> configuredBuilds(final Path buildRoot) throws IOException {
    try (Stream<Path> dirs = Files.list(buildRoot)) {
      return dirs.filter(BuildDiscovery::isConfiguredBuild).sorted().toList();
    }
  }

  private static boolean isConfiguredBuild(final Path confDir) {
    return Files.isRegularFile(confDir.resolve(SPEC_FILE));
  }
}
