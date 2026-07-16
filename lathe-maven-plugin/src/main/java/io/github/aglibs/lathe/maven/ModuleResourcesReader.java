package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.schema.ResourceRootData;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.maven.model.Build;
import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;

// Real resource roots from the effective Maven model, not the src/main/resources convention, so the
// server can refresh .lathe/ on a resource edit without assuming the layout.
final class ModuleResourcesReader {

  private ModuleResourcesReader() {}

  static List<ResourceRootData> read(final Path workspaceRoot, final List<MavenProject> projects) {
    return projects.stream().flatMap(project -> projectRoots(workspaceRoot, project)).toList();
  }

  private static Stream<ResourceRootData> projectRoots(
      final Path workspaceRoot, final MavenProject project) {
    final Build build = project.getBuild();
    return Stream.concat(
        roots(workspaceRoot, project, build.getResources(), build.getOutputDirectory()),
        roots(workspaceRoot, project, build.getTestResources(), build.getTestOutputDirectory()));
  }

  private static Stream<ResourceRootData> roots(
      final Path workspaceRoot,
      final MavenProject project,
      final List<Resource> resources,
      final String outputDirectory) {
    final var output = workspaceRoot.relativize(Path.of(outputDirectory)).toString();
    return resources.stream().map(resource -> toRoot(workspaceRoot, project, resource, output));
  }

  private static ResourceRootData toRoot(
      final Path workspaceRoot,
      final MavenProject project,
      final Resource resource,
      final String output) {
    final Path directory = project.getBasedir().toPath().resolve(resource.getDirectory());
    final var relative = workspaceRoot.relativize(directory).toString();
    final String targetPath = resource.getTargetPath() != null ? resource.getTargetPath() : "";
    return new ResourceRootData(relative, output, targetPath, resource.isFiltering());
  }
}
