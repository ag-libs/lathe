package io.github.aglibs.lathe.maven.typeindex;

import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.typeindex.ClassFileTypeScanner;
import io.github.aglibs.lathe.core.typeindex.JdkTypeIndexOrigin;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeIndexFile;
import io.github.aglibs.lathe.core.typeindex.TypeIndexOrigin;
import io.github.aglibs.lathe.core.typeindex.TypeIndexOriginKind;
import io.github.aglibs.lathe.maven.SyncException;
import io.github.aglibs.lathe.maven.jdk.JdkSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.maven.plugin.logging.Log;

public final class JdkTypeIndexSync {

  private JdkTypeIndexSync() {}

  public static JdkSource index(final JdkSource source, final Log log) {
    if (source.home() == null) {
      log.debug("[type-index] skipping JDK type index because no JDK home is resolvable");
      return source;
    }

    final Path index = indexPath(source);
    final var t = Stopwatch.start();
    try {
      final Optional<TypeIndexFile> current = currentIndex(index, source);
      if (current.isPresent()) {
        log.debug(
            "[type-index] reused JDK %s %dms types=%d"
                .formatted(source.version(), t.elapsedMs(), current.get().types().size()));
        return source.withTypeIndex(index);
      }

      final TypeIndexFile file =
          new TypeIndexFile(
              LatheLayout.SCHEMA_VERSION,
              TypeIndexOrigin.jdk(
                  new JdkTypeIndexOrigin(
                      source.home().toString(), source.vendor(), source.version())),
              scanJdk());
      TypeIndexFiles.write(index, file);
      log.debug(
          "[type-index] scanned JDK %s %dms types=%d"
              .formatted(source.version(), t.elapsedMs(), file.types().size()));
      return source.withTypeIndex(index);
    } catch (final IOException | UncheckedIOException e) {
      throw new SyncException("lathe:sync failed to index JDK types", e);
    }
  }

  public static Path indexPath(final JdkSource source) {
    return LatheLayout.userCacheRoot()
        .resolve(LatheLayout.TYPE_INDEX_DIR)
        .resolve(LatheLayout.CACHE_JDKS_DIR)
        .resolve(source.cacheKey())
        .resolve(LatheLayout.TYPE_INDEX_FILENAME);
  }

  private static List<TypeIndexEntry> scanJdk() throws IOException {
    final FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
    final Path modules = jrt.getPath("/modules");
    try (final Stream<Path> moduleRoots = Files.list(modules)) {
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
