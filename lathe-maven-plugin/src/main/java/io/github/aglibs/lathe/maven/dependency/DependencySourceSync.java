package io.github.aglibs.lathe.maven.dependency;

import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.ZipCache;
import io.github.aglibs.lathe.maven.ReactorProjects;
import io.github.aglibs.lathe.maven.SyncException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.aether.artifact.Artifact;

public final class DependencySourceSync {

  private static final String SOURCE_MARKER = ".lathe-source.json";

  private DependencySourceSync() {}

  public static void extract(final Collection<DependencySource> sources, final Log log) {
    final var t = Stopwatch.start();
    try {
      final Map<Boolean, Long> counts =
          sources.stream()
              .map(source -> IOUtil.unchecked(() -> extract(source)))
              .collect(
                  Collectors.partitioningBy(SourceExtraction::extracted, Collectors.counting()));
      log.info(
          "[sync] extracted %d source artifacts, %d already cached in %dms"
              .formatted(
                  counts.getOrDefault(true, 0L), counts.getOrDefault(false, 0L), t.elapsedMs()));
    } catch (final UncheckedIOException e) {
      throw new SyncException("lathe:sync failed to extract source artifacts", e);
    }
  }

  private static SourceExtraction extract(final DependencySource source) throws IOException {
    final var artifact = source.sourceArtifact();
    final var sourceJar = artifact.getFile().toPath();
    final var targetDir = source.dir();
    if (isSourceCacheCurrent(targetDir, artifact, sourceJar)) {
      return new SourceExtraction(false);
    }

    ZipCache.extract(
        sourceJar, targetDir, tempDir -> writeSourceMarker(tempDir, artifact, sourceJar));
    return new SourceExtraction(true);
  }

  private static boolean isSourceCacheCurrent(
      final Path targetDir, final Artifact artifact, final Path sourceJar) throws IOException {
    final var marker = targetDir.resolve(SOURCE_MARKER);
    if (!Files.exists(marker)) {
      return false;
    }
    try {
      final var m = Json.read(marker, SourceMarker.class);
      return LatheLayout.SCHEMA_VERSION.equals(m.schema())
          && ReactorProjects.gav(artifact).equals(m.gav())
          && sourceJar.toString().equals(m.sourceJar())
          && Files.size(sourceJar) == m.sourceJarSize()
          && Files.getLastModifiedTime(sourceJar).toMillis() == m.sourceJarModified();
    } catch (final IOException e) {
      return false;
    }
  }

  private static void writeSourceMarker(
      final Path tempDir, final Artifact artifact, final Path sourceJar) throws IOException {
    final var marker =
        new SourceMarker(
            LatheLayout.SCHEMA_VERSION,
            ReactorProjects.gav(artifact),
            sourceJar.toString(),
            Files.size(sourceJar),
            Files.getLastModifiedTime(sourceJar).toMillis());
    Json.write(marker, tempDir.resolve(SOURCE_MARKER));
  }

  private record SourceMarker(
      String schema, String gav, String sourceJar, long sourceJarSize, long sourceJarModified) {}

  private record SourceExtraction(boolean extracted) {}
}
