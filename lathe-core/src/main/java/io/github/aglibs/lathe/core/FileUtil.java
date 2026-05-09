package io.github.aglibs.lathe.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

public final class FileUtil {

  private FileUtil() {}

  public static void replaceDir(final Path src, final Path dest) throws IOException {
    if (Files.exists(dest)) {
      deleteDir(dest);
    }
    copyDir(src, dest);
  }

  public static void copyDir(final Path src, final Path dest) throws IOException {
    try (final var walk = Files.walk(src)) {
      walk.forEach(
          source -> {
            try {
              final var target = dest.resolve(src.relativize(source));
              if (Files.isDirectory(source)) {
                Files.createDirectories(target);
              } else {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
              }
            } catch (final IOException e) {
              throw new UncheckedIOException(e);
            }
          });
    } catch (final UncheckedIOException e) {
      throw e.getCause();
    }
  }

  public static void deleteDir(final Path dir) throws IOException {
    try (final var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.delete(path);
                } catch (final IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    } catch (final UncheckedIOException e) {
      throw e.getCause();
    }
  }
}
