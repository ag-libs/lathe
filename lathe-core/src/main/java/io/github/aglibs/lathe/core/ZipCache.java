package io.github.aglibs.lathe.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ZipCache {

  private ZipCache() {}

  public static void extract(final Path zipFile, final Path targetDir, final AfterExtract hook)
      throws IOException {
    Files.createDirectories(targetDir.getParent());
    final var tempDir =
        Files.createTempDirectory(targetDir.getParent(), targetDir.getFileName() + ".tmp-");
    try {
      FileUtil.unzip(zipFile, tempDir);
      hook.accept(tempDir);
      if (Files.exists(targetDir)) {
        FileUtil.deleteDir(targetDir);
      }
      FileUtil.moveReplacing(tempDir, targetDir);
    } finally {
      if (Files.exists(tempDir)) {
        FileUtil.deleteDir(tempDir);
      }
    }
  }

  @FunctionalInterface
  public interface AfterExtract {

    void accept(Path tempDir) throws IOException;
  }
}
