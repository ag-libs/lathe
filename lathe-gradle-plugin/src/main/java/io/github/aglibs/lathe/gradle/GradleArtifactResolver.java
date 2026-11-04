package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.install.ArtifactResolver;
import io.github.aglibs.lathe.install.SyncException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

// Gradle resolves only what a build declared before its tasks run, so the sync declares each
// coordinate it needs as a configuration and hands this resolver the resolved jars: transitive for
// resolveTransitive, the artifact alone for resolve.
final class GradleArtifactResolver implements ArtifactResolver {

  private final Map<String, List<Path>> resolved;

  GradleArtifactResolver(final Map<String, List<Path>> resolved) {
    this.resolved = Map.copyOf(resolved);
  }

  @Override
  public Path resolve(final String coordinates) throws SyncException {
    final List<Path> jars = resolveTransitive(coordinates);
    if (jars.size() != 1) {
      throw new SyncException(
          "[sync] %s resolved to %d jars, expected one".formatted(coordinates, jars.size()));
    }

    return jars.get(0);
  }

  @Override
  public List<Path> resolveTransitive(final String coordinates) throws SyncException {
    final List<Path> jars = resolved.get(coordinates);
    if (jars == null || jars.isEmpty()) {
      throw new SyncException("[sync] %s was not resolved by this build".formatted(coordinates));
    }

    return jars;
  }
}
