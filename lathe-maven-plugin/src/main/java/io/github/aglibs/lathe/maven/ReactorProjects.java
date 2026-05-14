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

public final class ReactorProjects {

  private ReactorProjects() {}

  public static List<MavenProject> sorted(final MavenSession session, final Path workspaceRoot) {
    return session.getProjects().stream()
        .sorted(Comparator.comparing(project -> moduleRel(workspaceRoot, project)))
        .toList();
  }

  /**
   * Returns a map from artifact key to the compile classpath of the first reactor module that has
   * that artifact. Used to populate per-dep classpath entries in the workspace manifest.
   *
   * <p>The classpath is the set of compile+provided-scope external JARs from that module. Maven has
   * already resolved version conflicts, so any module's view of a dep's transitive classpath is
   * correct.
   */
  public static Map<String, List<Path>> artifactClasspaths(final List<MavenProject> projects) {
    final var reactorGAs = reactorProjects(projects);
    final Map<String, List<Path>> result = new TreeMap<>();
    for (final MavenProject project : projects) {
      final var projectClasspath =
          project.getArtifacts().stream()
              .filter(a -> a.getFile() != null && a.getFile().getName().endsWith(".jar"))
              .filter(a -> !reactorGAs.contains(ga(a)))
              .filter(a -> isCompileClasspathScope(a.getScope()))
              .map(a -> a.getFile().toPath())
              .toList();
      project.getArtifacts().forEach(a -> result.putIfAbsent(artifactKey(a), projectClasspath));
    }
    return result;
  }

  public static Map<String, Artifact> externalArtifacts(final List<MavenProject> projects) {
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

  public static List<RemoteRepository> remoteRepositories(final List<MavenProject> projects) {
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

  public static String moduleRel(final Path workspaceRoot, final MavenProject project) {
    final String rel = workspaceRoot.relativize(project.getBasedir().toPath()).toString();
    if (rel.isEmpty()) {
      return ".";
    }

    return rel;
  }

  public static String gav(final MavenProject project) {
    return "%s:%s:%s"
        .formatted(project.getGroupId(), project.getArtifactId(), project.getVersion());
  }

  public static String gav(final Artifact artifact) {
    return "%s:%s:%s"
        .formatted(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
  }

  public static String gav(final org.eclipse.aether.artifact.Artifact artifact) {
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

  public static String artifactKey(final Artifact artifact) {
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

  private static boolean isCompileClasspathScope(final String scope) {
    return Artifact.SCOPE_COMPILE.equals(scope) || Artifact.SCOPE_PROVIDED.equals(scope);
  }
}
