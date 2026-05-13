package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.FileUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class CachedZipExtractor {

  private CachedZipExtractor() {}

  static void extract(final Path zipFile, final Path targetDir, final AfterExtract afterExtract)
      throws IOException {
    Files.createDirectories(targetDir.getParent());
    final Path tempDir =
        Files.createTempDirectory(targetDir.getParent(), targetDir.getFileName() + ".tmp-");
    try {
      FileUtil.unzip(zipFile, tempDir);
      afterExtract.accept(tempDir);
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
  interface AfterExtract {

    void accept(Path tempDir) throws IOException;
  }
}
