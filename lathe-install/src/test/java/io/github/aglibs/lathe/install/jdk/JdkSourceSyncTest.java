package io.github.aglibs.lathe.install.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.Stopwatch;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkSourceSyncTest {

  private static final String SOURCE = "java.base/java/lang/Object.java";

  @TempDir Path tmp;

  // A build that finds another extracting the same sources waits for it, then uses what it
  // extracted instead of extracting them again; a build alone extracts them itself.
  @Test
  void extract_anotherBuildExtracting_waitsAndReusesThem_aloneExtracts() throws Exception {
    final JdkSource source = source("jdk");
    final Closeable otherBuild =
        LatheLock.tryExclusive(LatheLayout.cacheLockFile(source.sourceDir())).orElseThrow();
    final var build =
        new FutureTask<Void>(
            () -> {
              JdkSourceSync.extract(source);
              return null;
            });
    final var buildThread = new Thread(build);
    buildThread.start();

    awaitWaiting(buildThread);
    Files.createDirectories(source.sourceDir());
    otherBuild.close();
    build.get();

    assertThat(source.sourceDir().resolve(SOURCE)).doesNotExist();
    final JdkSource alone = source("alone");
    JdkSourceSync.extract(alone);
    assertThat(alone.sourceDir().resolve(SOURCE)).hasContent("class Object {}");
  }

  // A build waiting for the cache lock sleeps between attempts; nothing else on its way does.
  private static void awaitWaiting(final Thread build) {
    final var waited = Stopwatch.start();
    while (build.getState() != Thread.State.TIMED_WAITING) {
      assertThat(waited.elapsedMs()).as("the build waits for the lock").isLessThan(10_000);
      Thread.onSpinWait();
    }
  }

  private JdkSource source(final String name) throws IOException {
    final Path zip = tmp.resolve(name).resolve("lib/src.zip");
    Files.createDirectories(zip.getParent());
    try (final var out = new ZipOutputStream(Files.newOutputStream(zip))) {
      out.putNextEntry(new ZipEntry(SOURCE));
      out.write("class Object {}".getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }

    return JdkSource.present(
        "vendor",
        "21",
        name,
        tmp.resolve(name),
        zip,
        tmp.resolve("cache/jdks").resolve(name).resolve("src"));
  }
}
