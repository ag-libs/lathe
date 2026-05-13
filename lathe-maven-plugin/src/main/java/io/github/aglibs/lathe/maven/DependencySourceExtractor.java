package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.ParamStore;
import io.github.aglibs.lathe.core.Stopwatch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.aether.artifact.Artifact;

final class DependencySourceExtractor {

  private static final String SOURCE_MARKER = ".lathe-source.properties";

  private DependencySourceExtractor() {}

  static void extract(final Collection<DependencySource> sources, final Log log) {
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

    CachedZipExtractor.extract(
        sourceJar, targetDir, tempDir -> writeSourceMarker(tempDir, artifact, sourceJar));
    return new SourceExtraction(true);
  }

  private static boolean isSourceCacheCurrent(
      final Path targetDir, final Artifact artifact, final Path sourceJar) throws IOException {
    final var marker = targetDir.resolve(SOURCE_MARKER);
    if (!Files.exists(marker)) {
      return false;
    }

    final var props = ParamStore.load(marker);

    return LatheLayout.SCHEMA_VERSION.equals(props.get("schema"))
        && ReactorProjects.gav(artifact).equals(props.get("gav"))
        && sourceJar.toString().equals(props.get("sourceJar"))
        && Long.toString(Files.size(sourceJar)).equals(props.get("sourceJar.size"))
        && Long.toString(Files.getLastModifiedTime(sourceJar).toMillis())
            .equals(props.get("sourceJar.modified"));
  }

  private static void writeSourceMarker(
      final Path tempDir, final Artifact artifact, final Path sourceJar) throws IOException {
    final var props = new ParamStore();
    props.set("schema", LatheLayout.SCHEMA_VERSION);
    props.set("gav", ReactorProjects.gav(artifact));
    props.set("sourceJar", sourceJar.toString());
    props.set("sourceJar.size", Long.toString(Files.size(sourceJar)));
    props.set("sourceJar.modified", Long.toString(Files.getLastModifiedTime(sourceJar).toMillis()));
    props.store(tempDir.resolve(SOURCE_MARKER));
  }

  private record SourceExtraction(boolean extracted) {}
}
