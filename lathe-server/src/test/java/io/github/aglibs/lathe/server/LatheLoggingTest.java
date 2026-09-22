package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheLoggingTest {

  private static final Logger LATHE = Logger.getLogger("io.github.aglibs.lathe");

  @AfterEach
  void removeFileHandlers() {
    for (final Handler handler : LATHE.getHandlers()) {
      if (handler instanceof FileHandler) {
        LATHE.removeHandler(handler);
        handler.close();
      }
    }
  }

  @Test
  void initFile_missingParentDir_createsItAndWritesRecord(@TempDir final Path cache)
      throws IOException {
    final Path logFile = cache.resolve("logs").resolve("mcp-test.log");

    LatheLogging.initFile(logFile);
    Logger.getLogger("io.github.aglibs.lathe.test").info("[startup] pid=1234 cwd=/tmp/project");
    flushFileHandlers();

    assertThat(logFile).exists();
    assertThat(Files.readString(logFile)).contains("[startup] pid=1234 cwd=/tmp/project", "INFO");
  }

  @Test
  void initFile_unwritablePath_degradesWithoutThrowing(@TempDir final Path cache)
      throws IOException {
    // A regular file where a directory is expected makes createDirectories fail; initFile must
    // swallow it so the server still starts with console-only logging.
    final Path notADir = cache.resolve("blocker");
    Files.writeString(notADir, "x");
    final Path logFile = notADir.resolve("logs").resolve("mcp-test.log");

    LatheLogging.initFile(logFile);

    assertThat(LATHE.getHandlers()).noneMatch(FileHandler.class::isInstance);
  }

  private void flushFileHandlers() {
    for (final Handler handler : LATHE.getHandlers()) {
      handler.flush();
    }
  }
}
