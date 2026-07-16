package io.github.aglibs.lathe.server.workspace;

import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import io.github.aglibs.lathe.core.schema.ResourceRootData;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// Maps a changed resource file to its .lathe/ destination from the roots lathe:sync captured;
// output dirs go target/ -> .lathe/ via ReactorRewrite (the rewrite replay uses), not rebuilt here.
public final class ResourceRootIndex {

  private record Mapping(Path sourceDir, Path latheOutputDir, String targetPath) {}

  private final List<Mapping> mappings;

  private ResourceRootIndex(final List<Mapping> mappings) {
    this.mappings = mappings;
  }

  public static ResourceRootIndex empty() {
    return new ResourceRootIndex(List.of());
  }

  public static ResourceRootIndex build(
      final Path workspaceRoot, final List<ResourceRootData> resourceRoots) {
    return new ResourceRootIndex(
        resourceRoots.stream().map(root -> toMapping(workspaceRoot, root)).toList());
  }

  private static Mapping toMapping(final Path workspaceRoot, final ResourceRootData root) {
    final Path sourceDir = workspaceRoot.resolve(root.directory()).normalize();
    final var latheOutput =
        ReactorRewrite.toLathe(workspaceRoot.resolve(root.outputDir()).toString(), workspaceRoot);
    return new Mapping(sourceDir, Path.of(latheOutput), root.targetPath());
  }

  // Empty if the file is under no resource root; the longest matching root wins (nested roots).
  public Optional<Path> destinationFor(final Path file) {
    final Path normalized = file.normalize();
    return mappings.stream()
        .filter(mapping -> normalized.startsWith(mapping.sourceDir()))
        .max(Comparator.comparingInt(mapping -> mapping.sourceDir().getNameCount()))
        .map(mapping -> destination(mapping, normalized));
  }

  private static Path destination(final Mapping mapping, final Path file) {
    return mapping
        .latheOutputDir()
        .resolve(mapping.targetPath())
        .resolve(mapping.sourceDir().relativize(file));
  }
}
