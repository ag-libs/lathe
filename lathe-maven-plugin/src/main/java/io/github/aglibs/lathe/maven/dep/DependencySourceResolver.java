package io.github.aglibs.lathe.maven.dep;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.maven.ReactorProjects;
import io.github.aglibs.lathe.maven.SyncException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

public final class DependencySourceResolver {

  private final RepositorySystem repositorySystem;
  private final MavenSession session;
  private final Log log;

  public DependencySourceResolver(
      final RepositorySystem repositorySystem, final MavenSession session, final Log log) {
    this.repositorySystem = repositorySystem;
    this.session = session;
    this.log = log;
  }

  public List<DependencySource> resolve(
      final Map<String, Artifact> artifacts,
      final Map<String, List<Path>> artifactClasspaths,
      final List<RemoteRepository> repositories) {
    log.info("[sync] dependencies " + artifacts.size() + " unique external artifacts");
    final List<DependencySource> dependencySources =
        artifacts.values().stream()
            .map(artifact -> resolve(artifact, artifactClasspaths, repositories))
            .toList();

    log.info(
        "[sync] sources %d dependency entries, %d with sources"
            .formatted(
                dependencySources.size(), DependencySource.present(dependencySources).size()));
    return dependencySources;
  }

  private DependencySource resolve(
      final Artifact artifact,
      final Map<String, List<Path>> artifactClasspaths,
      final List<RemoteRepository> repositories) {
    final Path binaryJar = artifact.getFile().toPath();
    final var classpath =
        artifactClasspaths.getOrDefault(ReactorProjects.artifactKey(artifact), List.of());
    final var sourceArtifact =
        new DefaultArtifact(
            artifact.getGroupId(),
            artifact.getArtifactId(),
            "sources",
            "jar",
            artifact.getVersion());
    final var request = new ArtifactRequest(sourceArtifact, repositories, null);
    try {
      final org.eclipse.aether.artifact.Artifact resolvedArtifact =
          repositorySystem.resolveArtifact(session.getRepositorySession(), request).getArtifact();
      return DependencySource.present(
          ReactorProjects.gav(resolvedArtifact),
          binaryJar,
          sourceCacheDir(
              LatheLayout.userCacheRoot().resolve(LatheLayout.CACHE_DEPS_DIR), resolvedArtifact),
          resolvedArtifact,
          classpath);
    } catch (final ArtifactResolutionException e) {
      log.debug("[sync] source missing " + ReactorProjects.gav(artifact), e);
      return DependencySource.missing(ReactorProjects.gav(artifact), binaryJar, classpath);
    } catch (final RuntimeException e) {
      throw new SyncException("lathe:sync failed to resolve source artifacts", e);
    }
  }

  private static Path sourceCacheDir(
      final Path cacheRoot, final org.eclipse.aether.artifact.Artifact artifact) {
    return cacheRoot.resolve(ReactorProjects.gav(artifact));
  }
}
