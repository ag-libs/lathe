package io.github.aglibs.lathe.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class FileUtil {

  private FileUtil() {}

  public static Path writeTempSourceFile(
      final Path tempDir, final Path sourceRoot, final Path filePath, final String content)
      throws IOException {
    final Path tempFile = tempDir.resolve(sourceRoot.relativize(filePath));
    Files.createDirectories(tempFile.getParent());
    Files.writeString(tempFile, content);
    return tempFile;
  }

  public static void replaceDir(final Path src, final Path dest) throws IOException {
    if (Files.exists(dest)) {
      deleteDir(dest);
    }
    copyDir(src, dest);
  }

  public static void writeAtomically(
      final Path dir, final Path target, final String content, final boolean executable)
      throws IOException {
    final var tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
    try {
      Files.writeString(tmp, content, StandardCharsets.UTF_8);
      if (executable) {
        tmp.toFile().setExecutable(true, false);
      }
      moveReplacing(tmp, target);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  public static void moveReplacing(final Path src, final Path dest) throws IOException {
    try {
      Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (final AtomicMoveNotSupportedException e) {
      Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public static void copyDir(final Path src, final Path dest) throws IOException {
    try (final var walk = Files.walk(src)) {
      walk.forEach(source -> IOUtil.unchecked(() -> copyEntry(src, dest, source)));
    } catch (final UncheckedIOException e) {
      throw e.getCause();
    }
  }

  public static void deleteDir(final Path dir) throws IOException {
    try (final var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(path -> IOUtil.unchecked(() -> Files.delete(path)));
    } catch (final UncheckedIOException e) {
      throw e.getCause();
    }
  }

  public static void unzip(final Path zipFile, final Path destDir) throws IOException {
    try (final ZipInputStream in = new ZipInputStream(Files.newInputStream(zipFile))) {
      ZipEntry entry = in.getNextEntry();
      while (entry != null) {
        extractZipEntry(destDir, in, entry);
        in.closeEntry();
        entry = in.getNextEntry();
      }
    }
  }

  private static void copyEntry(final Path src, final Path dest, final Path source)
      throws IOException {
    final var target = dest.resolve(src.relativize(source));
    if (Files.isDirectory(source)) {
      Files.createDirectories(target);
    } else {
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void extractZipEntry(
      final Path destDir, final ZipInputStream in, final ZipEntry entry) throws IOException {
    final Path target = destDir.resolve(entry.getName()).normalize();
    if (!target.startsWith(destDir)) {
      throw new IOException("zip contains unsafe path " + entry.getName());
    }

    if (entry.isDirectory()) {
      Files.createDirectories(target);
      return;
    }

    Files.createDirectories(target.getParent());
    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
  }
}
