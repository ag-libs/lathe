package io.github.aglibs.lathe.install.dependency;

import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.ZipCache;
import io.github.aglibs.lathe.install.ResolvedJar;
import io.github.aglibs.lathe.install.SyncException;
import io.github.aglibs.lathe.install.typeindex.DependencyTypeIndexSync;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DependencySourceSync {

  private static final Logger LOG = LoggerFactory.getLogger(DependencySourceSync.class);

  private DependencySourceSync() {}

  // A sync's whole dependency-source step, shared by every build tool: extracts each -sources jar,
  // indexes every external jar's types, and gives each source its jar's type index.
  public static List<DependencySource> prepare(
      final List<DependencySource> sources, final List<ResolvedJar> externalJars) {
    extract(DependencySource.withSources(sources));
    DependencyTypeIndexSync.index(externalJars);
    final Map<Path, Path> jarToTypeIndex =
        externalJars.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    ResolvedJar::jar,
                    DependencyTypeIndexSync::indexPath,
                    (first, ignored) -> first));
    return sources.stream()
        .map(
            source ->
                Optional.ofNullable(jarToTypeIndex.get(source.jar()))
                    .map(source::withTypeIndex)
                    .orElse(source))
        .toList();
  }

  public static void extract(final Collection<DependencySource> sources) {
    final var t = Stopwatch.start();
    try {
      final Map<Boolean, Long> counts =
          sources.stream()
              .map(source -> IOUtil.unchecked(() -> extract(source)))
              .collect(Collectors.partitioningBy(b -> b, Collectors.counting()));
      LOG.info(
          "[sync] extracted %d source artifacts, %d already cached in %dms"
              .formatted(
                  counts.getOrDefault(true, 0L), counts.getOrDefault(false, 0L), t.elapsedMs()));
    } catch (final UncheckedIOException e) {
      throw new SyncException("lathe:sync failed to extract source artifacts", e);
    }
  }

  private static boolean extract(final DependencySource source) throws IOException {
    final var artifact = source.sourceArtifact();
    final var sourceJar = artifact.jar();
    final var targetDir = source.dir();
    if (isSourceCacheCurrent(targetDir, artifact, sourceJar)) {
      return false;
    }

    final int skipped =
        ZipCache.extract(
            sourceJar, targetDir, tempDir -> writeSourceMarker(tempDir, artifact, sourceJar));
    if (skipped > 0) {
      LOG.warn("[sync] %s skipped %d unsafe source entries".formatted(artifact.gav(), skipped));
    }

    return true;
  }

  private static boolean isSourceCacheCurrent(
      final Path targetDir, final ResolvedJar artifact, final Path sourceJar) throws IOException {
    final var marker = targetDir.resolve(LatheLayout.DEPENDENCY_SOURCE_FILENAME);
    if (!Files.exists(marker)) {
      return false;
    }

    try {
      final var m = Json.read(marker, SourceMarker.class);
      return LatheLayout.CACHE_SCHEMA_VERSION.equals(m.schema())
          && artifact.gav().equals(m.gav())
          && sourceJar.toString().equals(m.sourceJar())
          && Files.size(sourceJar) == m.sourceJarSize()
          && Files.getLastModifiedTime(sourceJar).toMillis() == m.sourceJarModified();
    } catch (final IOException e) {
      return false;
    }
  }

  private static void writeSourceMarker(
      final Path tempDir, final ResolvedJar artifact, final Path sourceJar) throws IOException {
    final var marker =
        new SourceMarker(
            LatheLayout.CACHE_SCHEMA_VERSION,
            artifact.gav(),
            sourceJar.toString(),
            Files.size(sourceJar),
            Files.getLastModifiedTime(sourceJar).toMillis());
    Json.write(marker, tempDir.resolve(LatheLayout.DEPENDENCY_SOURCE_FILENAME));
  }

  private record SourceMarker(
      String schema, String gav, String sourceJar, long sourceJarSize, long sourceJarModified) {}
}
