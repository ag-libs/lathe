package io.github.aglibs.lathe.install.typeindex;

import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.typeindex.ClassFileTypeScanner;
import io.github.aglibs.lathe.core.typeindex.JdkTypeIndexOrigin;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeIndexFile;
import io.github.aglibs.lathe.core.typeindex.TypeIndexOrigin;
import io.github.aglibs.lathe.core.typeindex.TypeIndexOriginKind;
import io.github.aglibs.lathe.install.SyncException;
import io.github.aglibs.lathe.install.jdk.JdkSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JdkTypeIndexSync {

  private static final Logger LOG = LoggerFactory.getLogger(JdkTypeIndexSync.class);

  private JdkTypeIndexSync() {}

  public static JdkSource index(final JdkSource source) {
    if (source.home() == null) {
      LOG.debug("[type-index] skipping JDK type index because no JDK home is resolvable");
      return source;
    }

    final Path index = indexPath(source);
    final var t = Stopwatch.start();
    final Optional<TypeIndexFile> current = currentIndex(index, source);
    if (current.isPresent()) {
      LOG.debug(
          "[type-index] reused JDK %s %dms types=%d"
              .formatted(source.version(), t.elapsedMs(), current.get().types().size()));
      return source.withTypeIndex(index);
    }

    // As for the JDK's sources: builds that start together on an empty cache would each scan the
    // whole JDK, so one does and the others wait for its index.
    try {
      return LatheLock.exclusive(
          LatheLayout.cacheLockFile(index),
          () -> LOG.info("[sync] jdk type index waiting for another build indexing the JDK"),
          () -> indexOnce(source, index, t));
    } catch (final IOException | UncheckedIOException e) {
      throw new SyncException("Lathe sync failed to index JDK types", e);
    }
  }

  private static JdkSource indexOnce(final JdkSource source, final Path index, final Stopwatch t)
      throws IOException {
    final Optional<TypeIndexFile> built = currentIndex(index, source);
    if (built.isPresent()) {
      LOG.info(
          "[sync] jdk type index built by another build, waited %dms types=%d"
              .formatted(t.elapsedMs(), built.get().types().size()));
      return source.withTypeIndex(index);
    }

    final TypeIndexFile file =
        new TypeIndexFile(
            LatheLayout.CACHE_SCHEMA_VERSION,
            TypeIndexOrigin.jdk(
                new JdkTypeIndexOrigin(
                    source.home().toString(), source.vendor(), source.version())),
            scanJdk(source.home()));
    TypeIndexFiles.write(index, file);
    LOG.info(
        "[sync] jdk type index built for JDK %s in %dms types=%d"
            .formatted(source.version(), t.elapsedMs(), file.types().size()));
    return source.withTypeIndex(index);
  }

  public static Path indexPath(final JdkSource source) {
    return LatheLayout.userCacheRoot()
        .resolve(LatheLayout.TYPE_INDEX_DIR)
        .resolve(LatheLayout.CACHE_JDKS_DIR)
        .resolve(source.cacheKey())
        .resolve(LatheLayout.TYPE_INDEX_FILENAME);
  }

  // The indexed JDK's own runtime image, which may not be the one running the build: a Gradle
  // daemon
  // on 21 compiling with a 17 toolchain indexes 17.
  private static List<TypeIndexEntry> scanJdk(final Path javaHome) throws IOException {
    try (final FileSystem jrt =
            FileSystems.newFileSystem(
                URI.create("jrt:/"), Map.of("java.home", javaHome.toString()));
        final Stream<Path> moduleRoots = Files.list(jrt.getPath("/modules"))) {
      return moduleRoots
          .filter(Files::isDirectory)
          .flatMap(
              moduleRoot ->
                  IOUtil.unchecked(() -> ClassFileTypeScanner.scanDirectory(moduleRoot)).stream())
          .sorted(Comparator.comparing(TypeIndexEntry::qualifiedName))
          .toList();
    }
  }

  private static Optional<TypeIndexFile> currentIndex(final Path index, final JdkSource source) {
    return TypeIndexFiles.current(index, TypeIndexOriginKind.JDK)
        .filter(file -> isCurrent(file.origin().jdk(), source));
  }

  private static boolean isCurrent(final JdkTypeIndexOrigin origin, final JdkSource source) {
    return source.home().toString().equals(origin.javaHome())
        && source.vendor().equals(origin.vendor())
        && source.version().equals(origin.version());
  }
}
