package io.github.aglibs.lathe.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ZipCache {

  private ZipCache() {}

  // Returns the number of entries skipped as unsafe during extraction (see FileUtil.unzip).
  public static int extract(final Path zipFile, final Path targetDir, final AfterExtract hook)
      throws IOException {
    Files.createDirectories(targetDir.getParent());
    final var tempDir =
        Files.createTempDirectory(targetDir.getParent(), targetDir.getFileName() + ".tmp-");
    try {
      final int skipped = FileUtil.unzip(zipFile, tempDir);
      hook.accept(tempDir);
      if (Files.exists(targetDir)) {
        FileUtil.deleteDir(targetDir);
      }

      FileUtil.moveReplacing(tempDir, targetDir);
      return skipped;
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
