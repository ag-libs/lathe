package io.github.aglibs.lathe.maven.dependency;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.install.SyncException;
import io.github.aglibs.lathe.install.dependency.DependencySource;
import io.github.aglibs.lathe.maven.ReactorProjects;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

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
    log.info("[sync] %d unique external artifacts".formatted(artifacts.size()));
    final List<Artifact> binaries = List.copyOf(artifacts.values());
    final List<ArtifactResult> results =
        resolveAll(binaries.stream().map(binary -> sourcesRequest(binary, repositories)).toList());
    final List<DependencySource> dependencySources =
        IntStream.range(0, binaries.size())
            .mapToObj(
                i ->
                    toSource(
                        binaries.get(i),
                        results.get(i),
                        artifactClasspaths.getOrDefault(
                            ReactorProjects.artifactKey(binaries.get(i)), List.of()),
                        log))
            .toList();

    log.info(
        "[sync] sources %d dependency entries, %d with sources"
            .formatted(
                dependencySources.size(), DependencySource.withSources(dependencySources).size()));
    return dependencySources;
  }

  // One batch, so Maven Resolver downloads each repository's share in parallel. A missing sources
  // jar is normal: the batch then throws, carrying every request's result, in request order.
  private List<ArtifactResult> resolveAll(final List<ArtifactRequest> requests) {
    try {
      return repositorySystem.resolveArtifacts(session.getRepositorySession(), requests);
    } catch (final ArtifactResolutionException e) {
      return e.getResults();
    } catch (final RuntimeException e) {
      throw new SyncException("lathe:sync failed to resolve source artifacts", e);
    }
  }

  private static ArtifactRequest sourcesRequest(
      final Artifact binary, final List<RemoteRepository> repositories) {
    final var sourceArtifact =
        new DefaultArtifact(
            binary.getGroupId(), binary.getArtifactId(), "sources", "jar", binary.getVersion());
    return new ArtifactRequest(sourceArtifact, repositories, null);
  }

  static DependencySource toSource(
      final Artifact binary,
      final ArtifactResult result,
      final List<Path> classpath,
      final Log log) {
    final var binaryJar = binary.getFile().toPath();
    if (!result.isResolved()) {
      log.debug(
          "[sync] source missing %s".formatted(ReactorProjects.gav(binary)),
          result.getExceptions().stream().findFirst().orElse(null));
      return DependencySource.missing(ReactorProjects.gav(binary), binaryJar, classpath);
    }

    final org.eclipse.aether.artifact.Artifact resolved = result.getArtifact();
    return DependencySource.present(
        ReactorProjects.gav(resolved),
        binaryJar,
        LatheLayout.artifactDir(
            LatheLayout.userCacheRoot().resolve(LatheLayout.CACHE_DEPS_DIR),
            resolved.getGroupId(),
            resolved.getArtifactId(),
            resolved.getVersion()),
        ReactorProjects.resolvedJar(resolved),
        classpath);
  }
}
