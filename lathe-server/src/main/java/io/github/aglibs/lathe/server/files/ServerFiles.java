package io.github.aglibs.lathe.server.files;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// The server's own file helpers; FileUtil keeps what the build side shares.
public final class ServerFiles {

  private ServerFiles() {}

  // A file's text, or empty when it cannot be read (deleted, moved, a directory): for a source a
  // caller treats as absent rather than as an error.
  public static Optional<String> readIfPresent(final Path file) {
    try {
      return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
    } catch (final IOException e) {
      return Optional.empty();
    }
  }

  // A class file's binary name: its path below the class-output root, dotted, without .class.
  public static String classBinaryName(final Path root, final Path classFile) {
    final var relative = root.relativize(classFile).toString();
    return relative
        .substring(0, relative.length() - ".class".length())
        .replace(classFile.getFileSystem().getSeparator(), ".");
  }

  // Whether the file still holds the content the fingerprint recorded: never for no record, nor for
  // a file that cannot be read (deleted, say).
  public static boolean hasContent(final Path file, final ContentFingerprint fingerprint) {
    try {
      return fingerprint != null && fingerprint.equals(FileUtil.fingerprint(file));
    } catch (final IOException e) {
      return false;
    }
  }

  // Every regular file below root matching keep, with its mtime read from the walk's own attributes
  // -- one stat per entry, not a walk plus a stat per file, which matters for a scan repeated every
  // few seconds (file-system calls are costly on macOS). Empty when root is not a directory; a file
  // that vanishes during the walk is skipped.
  public static Map<Path, Long> fileMtimes(final Path root, final Predicate<Path> keep)
      throws IOException {
    if (!Files.isDirectory(root)) {
      return Map.of();
    }

    final var mtimes = new HashMap<Path, Long>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
            if (attrs.isRegularFile() && keep.test(file)) {
              mtimes.put(file, attrs.lastModifiedTime().toMillis());
            }

            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(final Path file, final IOException e) {
            return FileVisitResult.CONTINUE;
          }
        });
    return Map.copyOf(mtimes);
  }

  // Where writeTempSourceFile puts a source file's copy: its path below its root, under tempDir.
  public static Path tempSourceFile(
      final Path tempDir, final Path sourceRoot, final Path filePath) {
    return tempDir.resolve(sourceRoot.relativize(filePath));
  }

  public static Path writeTempSourceFile(
      final Path tempDir, final Path sourceRoot, final Path filePath, final String content)
      throws IOException {
    final Path tempFile = tempSourceFile(tempDir, sourceRoot, filePath);
    Files.createDirectories(tempFile.getParent());
    Files.writeString(tempFile, content);
    return tempFile;
  }

  // The files called name in dir and in each of its ancestors, nearest first.
  public static List<Path> ancestorFiles(final Path dir, final String name) {
    final List<Path> found = new ArrayList<>();
    for (Path current = dir; current != null; current = current.getParent()) {
      final Path candidate = current.resolve(name);
      if (Files.isRegularFile(candidate)) {
        found.add(candidate);
      }
    }

    return List.copyOf(found);
  }

  // Entry names of a zip whose name passes `keep`, read from the central directory only (no
  // decompression), directories excluded. Used to list jar resources without unpacking the jar.
  public static List<String> listEntries(final Path zipFile, final Predicate<String> keep)
      throws IOException {
    try (final var zip = new ZipFile(zipFile.toFile())) {
      final var names = new ArrayList<String>();
      final var entries = zip.entries();
      while (entries.hasMoreElements()) {
        final ZipEntry entry = entries.nextElement();
        if (!entry.isDirectory() && keep.test(entry.getName())) {
          names.add(entry.getName());
        }
      }

      return List.copyOf(names);
    }
  }
}
