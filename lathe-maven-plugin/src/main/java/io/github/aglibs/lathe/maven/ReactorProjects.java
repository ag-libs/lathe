package io.github.aglibs.lathe.maven;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.repository.RemoteRepository;

final class ReactorProjects {

  private ReactorProjects() {}

  static List<MavenProject> sorted(final MavenSession session, final Path workspaceRoot) {
    return session.getProjects().stream()
        .sorted(Comparator.comparing(project -> moduleRel(workspaceRoot, project)))
        .toList();
  }

  static Map<String, Dependency> externalDependencies(final List<MavenProject> projects) {
    final Set<String> reactorProjects = reactorProjects(projects);
    return projects.stream()
        .flatMap(project -> project.getDependencies().stream())
        .filter(dependency -> !reactorProjects.contains(ga(dependency)))
        .collect(
            Collectors.toMap(
                ReactorProjects::dependencyKey,
                dependency -> dependency,
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

  static String dependencySourceGav(final Dependency dependency) {
    return "%s:%s:%s"
        .formatted(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
  }

  static String gav(final Dependency dependency) {
    return "%s:%s:%s:%s"
        .formatted(
            dependency.getGroupId(),
            dependency.getArtifactId(),
            dependency.getType(),
            dependency.getVersion());
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

  private static String ga(final Dependency dependency) {
    return dependency.getGroupId() + ":" + dependency.getArtifactId();
  }

  private static String dependencyKey(final Dependency dependency) {
    return "%s:%s:%s:%s:%s"
        .formatted(
            dependency.getGroupId(),
            dependency.getArtifactId(),
            dependency.getType(),
            dependency.getClassifier(),
            dependency.getVersion());
  }

  private static String repositoryKey(final RemoteRepository repository) {
    return repository.getId() + " " + repository.getUrl();
  }
}
