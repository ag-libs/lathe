package io.github.aglibs.lathe.install.typeindex;

import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.typeindex.ClassFileTypeScanner;
import io.github.aglibs.lathe.core.typeindex.DependencyTypeIndexOrigin;
import io.github.aglibs.lathe.core.typeindex.TypeIndexFile;
import io.github.aglibs.lathe.core.typeindex.TypeIndexOrigin;
import io.github.aglibs.lathe.core.typeindex.TypeIndexOriginKind;
import io.github.aglibs.lathe.install.ResolvedJar;
import io.github.aglibs.lathe.install.SyncException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.zip.ZipException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DependencyTypeIndexSync {

  private static final Logger LOG = LoggerFactory.getLogger(DependencyTypeIndexSync.class);

  private DependencyTypeIndexSync() {}

  public static void index(final Collection<ResolvedJar> artifacts) {
    final var t = Stopwatch.start();
    try {
      final Map<Boolean, Long> counts =
          artifacts.stream()
              .map(artifact -> IOUtil.unchecked(() -> indexReadable(artifact)))
              .collect(Collectors.partitioningBy(b -> b, Collectors.counting()));
      LOG.info(
          "[sync] built %d dependency type indexes, %d already cached in %dms"
              .formatted(
                  counts.getOrDefault(true, 0L), counts.getOrDefault(false, 0L), t.elapsedMs()));
    } catch (final UncheckedIOException e) {
      throw new SyncException("failed to index dependency artifacts", e);
    }
  }

  public static Path indexPath(final ResolvedJar artifact) {
    final Path versionDir =
        LatheLayout.artifactDir(
            LatheLayout.userCacheRoot()
                .resolve(LatheLayout.TYPE_INDEX_DIR)
                .resolve(LatheLayout.CACHE_DEPS_DIR),
            artifact.groupId(),
            artifact.artifactId(),
            artifact.version());
    if (artifact.classifier().isBlank()) {
      return versionDir.resolve(LatheLayout.TYPE_INDEX_FILENAME);
    }

    return versionDir.resolve(artifact.classifier()).resolve(LatheLayout.TYPE_INDEX_FILENAME);
  }

  // An artifact that is no readable archive (a POM-only module's .pom on a Gradle classpath, a
  // corrupt download) has no types to index: it is skipped, so one bad file never stops a sync.
  private static boolean indexReadable(final ResolvedJar artifact) throws IOException {
    try {
      return index(artifact);
    } catch (final ZipException e) {
      LOG.warn("[type-index] skipped %s: not a readable archive".formatted(artifact.jar()), e);
      return false;
    }
  }

  private static boolean index(final ResolvedJar artifact) throws IOException {
    final var jar = artifact.jar();
    final Path index = indexPath(artifact);
    final var t = Stopwatch.start();
    final Optional<TypeIndexFile> current = currentIndex(index, artifact, jar);
    if (current.isPresent()) {
      LOG.debug(
          "[type-index] reused %s %dms types=%d"
              .formatted(jar, t.elapsedMs(), current.get().types().size()));
      return false;
    }

    final TypeIndexFile file =
        new TypeIndexFile(
            LatheLayout.CACHE_SCHEMA_VERSION,
            TypeIndexOrigin.dependency(
                new DependencyTypeIndexOrigin(
                    artifact.gav(),
                    jar.toString(),
                    Files.size(jar),
                    Files.getLastModifiedTime(jar).toMillis())),
            ClassFileTypeScanner.scanJar(jar));
    TypeIndexFiles.write(index, file);
    LOG.debug(
        "[type-index] scanned %s %dms types=%d".formatted(jar, t.elapsedMs(), file.types().size()));
    return true;
  }

  private static Optional<TypeIndexFile> currentIndex(
      final Path index, final ResolvedJar artifact, final Path jar) {
    return TypeIndexFiles.current(index, TypeIndexOriginKind.DEPENDENCY)
        .filter(file -> isCurrent(file, artifact, jar));
  }

  private static boolean isCurrent(
      final TypeIndexFile file, final ResolvedJar artifact, final Path jar) {
    try {
      final DependencyTypeIndexOrigin origin = file.origin().dependency();
      return artifact.gav().equals(origin.gav())
          && jar.toString().equals(origin.jar())
          && Files.size(jar) == origin.size()
          && Files.getLastModifiedTime(jar).toMillis() == origin.mtimeMillis();
    } catch (final IOException e) {
      return false;
    }
  }
}
