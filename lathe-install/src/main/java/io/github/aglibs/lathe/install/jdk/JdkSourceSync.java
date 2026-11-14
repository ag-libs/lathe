package io.github.aglibs.lathe.install.jdk;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.install.SyncException;
import io.github.aglibs.lathe.install.ZipCache;
import java.io.IOException;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JdkSourceSync {

  private static final Logger LOG = LoggerFactory.getLogger(JdkSourceSync.class);

  private JdkSourceSync() {}

  public static void extract(final JdkSource source) {
    if (!source.isPresent()) {
      // INFO, not WARN: a build on a JDK without sources (a CI image) is fine; only the editor's
      // navigation into the JDK misses them, and the editor says so itself.
      source.toData().missingSourcesNote().ifPresent(note -> LOG.info("[sync] %s".formatted(note)));
      return;
    }

    final var t = Stopwatch.start();
    if (Files.exists(source.sourceDir())) {
      LOG.info("[sync] jdk sources already cached in %dms".formatted(t.elapsedMs()));
      return;
    }

    // Builds that start together on an empty cache (parallel builds, a test suite's forks) would
    // each extract the same sources: one does, and the others wait for it.
    try {
      LOG.info(
          LatheLock.exclusive(
              LatheLayout.cacheLockFile(source.sourceDir()),
              () -> LOG.info("[sync] jdk sources waiting for another build extracting them"),
              () -> extractOnce(source, t)));
    } catch (final IOException e) {
      throw new SyncException("Lathe sync failed to extract JDK sources", e);
    }
  }

  private static String extractOnce(final JdkSource source, final Stopwatch t) throws IOException {
    if (Files.exists(source.sourceDir())) {
      return "[sync] jdk sources extracted by another build, waited %dms".formatted(t.elapsedMs());
    }

    final int skipped = ZipCache.extract(source.sourceZip(), source.sourceDir(), ignored -> {});
    if (skipped > 0) {
      LOG.warn("[sync] jdk sources skipped %d unsafe entries".formatted(skipped));
    }

    return "[sync] jdk sources extracted in %dms".formatted(t.elapsedMs());
  }
}
