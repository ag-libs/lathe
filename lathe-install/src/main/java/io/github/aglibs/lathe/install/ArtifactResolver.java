package io.github.aglibs.lathe.install;

import java.nio.file.Path;
import java.util.List;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.util.artifact.JavaScopes;

// Resolves jars through the build's own repository session, so sync reuses Maven's local
// repository, mirrors, and credentials. Coordinates are groupId:artifactId:version.
public final class ArtifactResolver {

  private final RepositorySystem repositorySystem;
  private final RepositorySystemSession repoSession;

  public ArtifactResolver(
      final RepositorySystem repositorySystem, final RepositorySystemSession repoSession) {
    this.repositorySystem = repositorySystem;
    this.repoSession = repoSession;
  }

  public Path resolve(final String coordinates, final List<RemoteRepository> repositories)
      throws SyncException {
    final var request = new ArtifactRequest(new DefaultArtifact(coordinates), repositories, null);
    try {
      return repositorySystem
          .resolveArtifact(repoSession, request)
          .getArtifact()
          .getFile()
          .toPath();
    } catch (final ArtifactResolutionException e) {
      throw failure(coordinates, e);
    }
  }

  // The artifact with its runtime dependency closure, resolved as a project's dependency would be.
  // Not as the collection root: the root keeps its own optional dependencies (palantir-java-format
  // declares a jar-less optional parent), a dependency does not.
  public List<Path> resolveTransitive(
      final String coordinates, final List<RemoteRepository> repositories) throws SyncException {
    final var dependency = new Dependency(new DefaultArtifact(coordinates), JavaScopes.RUNTIME);
    final var request =
        new DependencyRequest(new CollectRequest(List.of(dependency), null, repositories), null);
    try {
      return repositorySystem
          .resolveDependencies(repoSession, request)
          .getArtifactResults()
          .stream()
          .filter(ArtifactResult::isResolved)
          .map(result -> result.getArtifact().getFile().toPath())
          .toList();
    } catch (final DependencyResolutionException e) {
      throw failure(coordinates, e);
    }
  }

  // The artifact's declared dependencies, read from its POM without resolving any of them.
  public List<Dependency> directDependencies(
      final String coordinates, final List<RemoteRepository> repositories) throws SyncException {
    final var request =
        new ArtifactDescriptorRequest(new DefaultArtifact(coordinates), repositories, null);
    try {
      return List.copyOf(
          repositorySystem.readArtifactDescriptor(repoSession, request).getDependencies());
    } catch (final ArtifactDescriptorException e) {
      throw failure(coordinates, e);
    }
  }

  private static SyncException failure(final String coordinates, final Exception cause) {
    return new SyncException("lathe:sync failed to resolve %s".formatted(coordinates), cause);
  }
}
