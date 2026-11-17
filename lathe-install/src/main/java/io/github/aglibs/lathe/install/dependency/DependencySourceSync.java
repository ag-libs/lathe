package io.github.aglibs.lathe.install.dependency;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.install.ResolvedJar;
import io.github.aglibs.lathe.install.SyncException;
import io.github.aglibs.lathe.install.ZipCache;
import io.github.aglibs.lathe.install.typeindex.DependencyTypeIndexSync;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DependencySourceSync {

  private static final Logger LOG = LoggerFactory.getLogger(DependencySourceSync.class);

  private DependencySourceSync() {}

  // A sync's whole dependency-source step, shared by every build tool: extracts each -sources jar,
  // indexes every external jar's types, and gives each source its jar's type index. A dependency
  // whose -sources jar cannot be read is one without sources.
  public static List<DependencySource> prepare(
      final List<DependencySource> sources, final List<ResolvedJar> externalJars) {
    final Set<String> unreadable = extract(DependencySource.withSources(sources));
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
                unreadable.contains(source.gav())
                    ? DependencySource.missing(source.gav(), source.jar(), source.classpath())
                    : source)
        .map(
            source ->
                Optional.ofNullable(jarToTypeIndex.get(source.jar()))
                    .map(source::withTypeIndex)
                    .orElse(source))
        .toList();
  }

  // Answers the dependencies whose -sources jar is not a readable zip. Such a jar is skipped, not
  // fatal: the DB2 driver publishes a text file as its sources jar, and sources serve only
  // navigation. Any other failure, writing the cache say, still fails the sync.
  public static Set<String> extract(final Collection<DependencySource> sources) {
    final var t = Stopwatch.start();
    int extracted = 0;
    int cached = 0;
    final var unreadable = new LinkedHashSet<String>();
    for (final DependencySource source : sources) {
      final Path sourceJar = source.sourceArtifact().jar();
      try {
        if (extract(source)) {
          extracted++;
        } else {
          cached++;
        }
      } catch (final ZipException e) {
        LOG.warn(
            "[sync] %s sources jar %s is not a readable zip (%s); using no sources for it"
                .formatted(source.gav(), sourceJar, e.getMessage()));
        unreadable.add(source.gav());
      } catch (final IOException e) {
        throw new SyncException(
            "Lathe sync failed to extract the sources of %s from %s"
                .formatted(source.gav(), sourceJar),
            e);
      }
    }

    LOG.info(
        "[sync] extracted %d source artifacts, %d already cached, %d unreadable in %dms"
            .formatted(extracted, cached, unreadable.size(), t.elapsedMs()));
    return Set.copyOf(unreadable);
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
