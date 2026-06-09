package io.github.aglibs.lathe.maven.jdk;

import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.ZipCache;
import io.github.aglibs.lathe.maven.SyncException;
import java.io.IOException;
import java.nio.file.Files;
import org.apache.maven.plugin.logging.Log;

public final class JdkSourceSync {

  private JdkSourceSync() {}

  public static void extract(final JdkSource source, final Log log) {
    if (!source.isPresent()) {
      log.info("[sync] jdk sources missing");
      return;
    }

    final Stopwatch t = Stopwatch.start();
    try {
      if (Files.exists(source.sourceDir())) {
        log.info("[sync] jdk sources already cached in %dms".formatted(t.elapsedMs()));
        return;
      }

      ZipCache.extract(source.sourceZip(), source.sourceDir(), ignored -> {});
      log.info("[sync] jdk sources extracted in %dms".formatted(t.elapsedMs()));
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to extract JDK sources", e);
    }
  }
}
