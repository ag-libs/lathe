package io.github.aglibs.lathe.install.jdk;

import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.ZipCache;
import io.github.aglibs.lathe.install.SyncException;
import java.io.IOException;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JdkSourceSync {

  private static final Logger LOG = LoggerFactory.getLogger(JdkSourceSync.class);

  private JdkSourceSync() {}

  public static void extract(final JdkSource source) {
    if (!source.isPresent()) {
      LOG.info("[sync] jdk sources missing");
      return;
    }

    final var t = Stopwatch.start();
    try {
      if (Files.exists(source.sourceDir())) {
        LOG.info("[sync] jdk sources already cached in %dms".formatted(t.elapsedMs()));
        return;
      }

      final int skipped = ZipCache.extract(source.sourceZip(), source.sourceDir(), ignored -> {});
      if (skipped > 0) {
        LOG.warn("[sync] jdk sources skipped %d unsafe entries".formatted(skipped));
      }

      LOG.info("[sync] jdk sources extracted in %dms".formatted(t.elapsedMs()));
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to extract JDK sources", e);
    }
  }
}
