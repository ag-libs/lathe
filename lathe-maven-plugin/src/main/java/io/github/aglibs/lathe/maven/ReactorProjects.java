package io.github.aglibs.lathe.maven;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.repository.RemoteRepository;

final class ReactorProjects {

  private ReactorProjects() {}

  static List<MavenProject> sorted(final MavenSession session, final Path workspaceRoot) {
    return session.getProjects().stream()
        .sorted(Comparator.comparing(project -> moduleRel(workspaceRoot, project)))
        .toList();
  }

  static Map<String, Artifact> externalArtifacts(final List<MavenProject> projects) {
    final Set<String> reactorProjects = reactorProjects(projects);
    return projects.stream()
        .flatMap(project -> project.getArtifacts().stream())
        .filter(artifact -> artifact.getFile() != null)
        .filter(artifact -> artifact.getFile().getName().endsWith(".jar"))
        .filter(artifact -> !reactorProjects.contains(ga(artifact)))
        .collect(
            Collectors.toMap(
                ReactorProjects::artifactKey,
                artifact -> artifact,
                (first, ignored) -> first,
                TreeMap::new));
  }

  static List<RemoteRepository> remoteRepositories(final List<MavenProject> projects) {
    final Map<String, RemoteRepository> repositories =
        projects.stream()
            .flatMap(project -> project.getRemoteProjectRepositories().stream())
            .collect(
                Collectors.toMap(
                    ReactorProjects::repositoryKey,
                    repository -> repository,
                    (first, ignored) -> first,
                    TreeMap::new));
    return List.copyOf(repositories.values());
  }

  static String moduleRel(final Path workspaceRoot, final MavenProject project) {
    final String rel = workspaceRoot.relativize(project.getBasedir().toPath()).toString();
    if (rel.isEmpty()) {
      return ".";
    }

    return rel;
  }

  static String gav(final MavenProject project) {
    return "%s:%s:%s"
        .formatted(project.getGroupId(), project.getArtifactId(), project.getVersion());
  }

  static String gav(final Artifact artifact) {
    return "%s:%s:%s"
        .formatted(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
  }

  static String gav(final org.eclipse.aether.artifact.Artifact artifact) {
    return "%s:%s:%s"
        .formatted(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
  }

  private static Set<String> reactorProjects(final List<MavenProject> projects) {
    return projects.stream()
        .map(project -> project.getGroupId() + ":" + project.getArtifactId())
        .collect(Collectors.toSet());
  }

  private static String ga(final Artifact artifact) {
    return artifact.getGroupId() + ":" + artifact.getArtifactId();
  }

  private static String artifactKey(final Artifact artifact) {
    return "%s:%s:%s:%s:%s"
        .formatted(
            artifact.getGroupId(),
            artifact.getArtifactId(),
            artifact.getType(),
            artifact.getClassifier(),
            artifact.getVersion());
  }

  private static String repositoryKey(final RemoteRepository repository) {
    return repository.getId() + " " + repository.getUrl();
  }
}
