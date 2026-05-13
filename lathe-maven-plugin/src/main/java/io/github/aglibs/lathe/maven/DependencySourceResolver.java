package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

final class DependencySourceResolver {

  private final RepositorySystem repositorySystem;
  private final MavenSession session;
  private final Log log;

  DependencySourceResolver(
      final RepositorySystem repositorySystem, final MavenSession session, final Log log) {
    this.repositorySystem = repositorySystem;
    this.session = session;
    this.log = log;
  }

  List<DependencySource> resolve(
      final Map<String, Dependency> dependencies, final List<RemoteRepository> repositories)
      throws MojoExecutionException {
    log.info("[sync] dependencies " + dependencies.size() + " unique external artifacts");
    final List<DependencySource> dependencySources = new ArrayList<>();
    for (final Dependency dependency : dependencies.values()) {
      dependencySources.add(resolve(dependency, repositories));
    }

    log.info(
        "[sync] sources %d dependency entries, %d with sources"
            .formatted(
                dependencySources.size(), DependencySource.present(dependencySources).size()));
    return List.copyOf(dependencySources);
  }

  private DependencySource resolve(
      final Dependency dependency, final List<RemoteRepository> repositories)
      throws MojoExecutionException {
    if (dependency.getVersion() == null || dependency.getVersion().isBlank()) {
      return DependencySource.skipped(ReactorProjects.dependencySourceGav(dependency));
    }

    final Path binaryJar = resolveBinaryArtifact(dependency, repositories).getFile().toPath();
    final var sourceArtifact =
        new DefaultArtifact(
            dependency.getGroupId(),
            dependency.getArtifactId(),
            "sources",
            "jar",
            dependency.getVersion());
    final var request = new ArtifactRequest(sourceArtifact, repositories, null);
    try {
      final Artifact resolvedArtifact =
          repositorySystem.resolveArtifact(session.getRepositorySession(), request).getArtifact();
      return DependencySource.present(
          ReactorProjects.gav(resolvedArtifact),
          binaryJar,
          sourceCacheDir(
              LatheLayout.userCacheRoot().resolve(LatheLayout.CACHE_DEPS_DIR), resolvedArtifact),
          resolvedArtifact);
    } catch (final ArtifactResolutionException e) {
      log.debug("[sync] source missing " + ReactorProjects.gav(dependency), e);
      return DependencySource.missing(ReactorProjects.dependencySourceGav(dependency), binaryJar);
    } catch (final RuntimeException e) {
      throw new MojoExecutionException("lathe:sync failed to resolve source artifacts", e);
    }
  }

  private Artifact resolveBinaryArtifact(
      final Dependency dependency, final List<RemoteRepository> repositories)
      throws MojoExecutionException {
    final String classifier = dependency.getClassifier() != null ? dependency.getClassifier() : "";
    final String extension = dependency.getType() != null ? dependency.getType() : "jar";
    final var binaryArtifact =
        new DefaultArtifact(
            dependency.getGroupId(),
            dependency.getArtifactId(),
            classifier,
            extension,
            dependency.getVersion());
    final var request = new ArtifactRequest(binaryArtifact, repositories, null);
    try {
      return repositorySystem
          .resolveArtifact(session.getRepositorySession(), request)
          .getArtifact();
    } catch (final ArtifactResolutionException e) {
      throw new MojoExecutionException("lathe:sync failed to resolve dependency artifact", e);
    }
  }

  private static Path sourceCacheDir(final Path cacheRoot, final Artifact artifact) {
    return cacheRoot
        .resolve(artifact.getGroupId().replace('.', '/'))
        .resolve(artifact.getArtifactId())
        .resolve(artifact.getVersion());
  }
}
