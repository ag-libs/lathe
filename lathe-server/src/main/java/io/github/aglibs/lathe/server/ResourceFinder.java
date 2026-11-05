package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.FileUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

// The resources the client's finder lists: files under the reactor's resource roots and entries in
// the dependency jars.
final class ResourceFinder {

  private static final Logger LOG = Logger.getLogger(ResourceFinder.class.getName());

  private ResourceFinder() {}

  // Every resource the finder lists: reactor resource roots (real editable files) and each dep
  // jar's entries (read from its central directory -- no decompression, no extraction). `.class` is
  // excluded; origin comes from the owning module / GAV.
  static List<ResourceEntry> list(
      final Map<Path, String> resourceDirs, final Map<Path, String> jarToGav) {
    return Stream.concat(
            resourceDirs.entrySet().stream()
                .flatMap(dir -> resourcesUnder(dir.getKey(), dir.getValue()).stream()),
            jarToGav.entrySet().stream()
                .flatMap(dep -> jarResources(dep.getKey(), dep.getValue()).stream()))
        .toList();
  }

  private static List<ResourceEntry> resourcesUnder(final Path dir, final String module) {
    try {
      return FileUtil.walkFiles(dir, ResourceFinder::isResource).stream()
          .map(
              file ->
                  ResourceEntry.reactor(
                      FileUtil.slashed(dir.relativize(file)), module, file.toString()))
          .toList();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[resources] walk %s failed".formatted(dir));
      return List.of();
    }
  }

  private static List<ResourceEntry> jarResources(final Path jar, final String gav) {
    try {
      return FileUtil.listEntries(jar, ResourceFinder::isResource).stream()
          .map(entry -> ResourceEntry.dependency(gav, jar.toString(), entry))
          .toList();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[resources] list %s failed".formatted(jar));
      return List.of();
    }
  }

  // Excluding compiled classes is the whole filter -- a denylist keeps unusual resource extensions.
  private static boolean isResource(final String name) {
    return !name.endsWith(".class");
  }

  private static boolean isResource(final Path file) {
    return isResource(file.getFileName().toString());
  }

  static Supplier<Path> tempDest(final String entry) {
    return () -> {
      try {
        return Files.createTempDirectory("lathe-resource-").resolve(Path.of(entry).getFileName());
      } catch (final IOException e) {
        throw new UncheckedIOException("temp dir for %s".formatted(entry), e);
      }
    };
  }
}
