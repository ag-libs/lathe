package io.github.aglibs.lathe.maven.typeindex;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.typeindex.DependencyTypeIndexOrigin;
import io.github.aglibs.lathe.core.typeindex.TypeIndexFile;
import io.github.aglibs.lathe.core.typeindex.TypeIndexOrigin;
import io.github.aglibs.lathe.core.typeindex.TypeIndexOriginKind;
import io.github.aglibs.lathe.maven.ReactorProjects;
import io.github.aglibs.lathe.maven.SyncException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;

public final class DependencyTypeIndexSync {

  private static final String INDEX_JSON = "index.json";

  private DependencyTypeIndexSync() {}

  public static void index(final Collection<Artifact> artifacts, final Log log) {
    final Stopwatch t = Stopwatch.start();
    try {
      final Map<Boolean, Long> counts =
          artifacts.stream()
              .map(artifact -> IOUtil.unchecked(() -> index(artifact, log)))
              .collect(Collectors.partitioningBy(b -> b, Collectors.counting()));
      log.info(
          "[sync] built %d dependency type indexes, %d already cached in %dms"
              .formatted(
                  counts.getOrDefault(true, 0L), counts.getOrDefault(false, 0L), t.elapsedMs()));
    } catch (final UncheckedIOException e) {
      throw new SyncException("lathe:sync failed to index dependency artifacts", e);
    }
  }

  public static Path indexPath(final Artifact artifact) {
    final Path versionDir =
        LatheLayout.userCacheRoot()
            .resolve(LatheLayout.TYPE_INDEX_DIR)
            .resolve(LatheLayout.CACHE_DEPS_DIR)
            .resolve(artifact.getGroupId())
            .resolve(artifact.getArtifactId())
            .resolve(artifact.getVersion());
    final String classifier = artifact.getClassifier();
    if (classifier == null || classifier.isBlank()) {
      return versionDir.resolve(INDEX_JSON);
    }

    return versionDir.resolve(classifier).resolve(INDEX_JSON);
  }

  private static boolean index(final Artifact artifact, final Log log) throws IOException {
    final Path jar = artifact.getFile().toPath();
    final Path index = indexPath(artifact);
    final Stopwatch t = Stopwatch.start();
    if (isCurrent(index, artifact, jar)) {
      final TypeIndexFile current = Json.read(index, TypeIndexFile.class);
      log.debug(
          "[type-index] reused %s %dms types=%d"
              .formatted(jar, t.elapsedMs(), current.types().size()));
      return false;
    }

    final ClassFileTypeScanner scanner = new ClassFileTypeScanner();
    final TypeIndexFile file =
        new TypeIndexFile(
            LatheLayout.SCHEMA_VERSION,
            TypeIndexOrigin.dependency(
                new DependencyTypeIndexOrigin(
                    ReactorProjects.gav(artifact),
                    jar.toString(),
                    Files.size(jar),
                    Files.getLastModifiedTime(jar).toMillis())),
            scanner.scanJar(jar));
    Files.createDirectories(index.getParent());
    FileUtil.writeAtomically(index.getParent(), index, Json.toJson(file), false);
    log.debug(
        "[type-index] scanned %s %dms types=%d".formatted(jar, t.elapsedMs(), file.types().size()));
    return true;
  }

  private static boolean isCurrent(final Path index, final Artifact artifact, final Path jar) {
    if (!Files.exists(index)) {
      return false;
    }

    try {
      final TypeIndexFile file = Json.read(index, TypeIndexFile.class);
      if (file.origin().kind() != TypeIndexOriginKind.DEPENDENCY) {
        return false;
      }

      final DependencyTypeIndexOrigin origin = file.origin().dependency();
      return LatheLayout.SCHEMA_VERSION.equals(file.schema())
          && ReactorProjects.gav(artifact).equals(origin.gav())
          && jar.toString().equals(origin.jar())
          && Files.size(jar) == origin.size()
          && Files.getLastModifiedTime(jar).toMillis() == origin.mtimeMillis();
    } catch (final RuntimeException | IOException e) {
      return false;
    }
  }
}
