package io.github.aglibs.lathe.core;

import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class FileUtil {

  // Windows briefly denies a rename or delete while another process holds the file (a virus
  // scanner reading one just written), so those are retried, a little longer each time.
  private static final int RETRY_ATTEMPTS = 5;
  private static final long RETRY_PAUSE_MS = 10;

  private FileUtil() {}

  // One identity per file for a path given as text; a relative one resolves against the cwd.
  public static Path absolute(final String path) {
    return Path.of(path).toAbsolutePath().normalize();
  }

  public static boolean isJavaFile(final Path path) {
    return path.getFileName().toString().endsWith(".java");
  }

  public static boolean isClassFile(final Path path) {
    return path.getFileName().toString().endsWith(".class");
  }

  // A class file's binary name: its path below the class-output root, dotted, without .class.
  public static String classBinaryName(final Path root, final Path classFile) {
    final var relative = root.relativize(classFile).toString();
    return relative
        .substring(0, relative.length() - ".class".length())
        .replace(classFile.getFileSystem().getSeparator(), ".");
  }

  public static ContentFingerprint fingerprint(final Path file) throws IOException {
    return ContentFingerprint.of(Files.readAllBytes(file));
  }

  // Whether the file still holds the content the fingerprint recorded: never for no record, nor for
  // a file that cannot be read (deleted, say).
  public static boolean hasContent(final Path file, final ContentFingerprint fingerprint) {
    try {
      return fingerprint != null && fingerprint.equals(fingerprint(file));
    } catch (final IOException e) {
      return false;
    }
  }

  // The top-level type name of a .java source — its file name without the .java suffix.
  public static String javaTypeName(final Path javaFile) {
    final var name = javaFile.getFileName().toString();
    return name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : name;
  }

  // Every directory below root (excluding root itself), sorted; empty when root is not a directory.
  public static List<Path> subdirectories(final Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return List.of();
    }

    try (final var walk = Files.walk(root)) {
      return walk.filter(Files::isDirectory).filter(path -> !path.equals(root)).sorted().toList();
    }
  }

  // Deletes the files directly in dir that match, all but the last count in name order: the newest,
  // for names that start with a sortable timestamp.
  public static void deleteAllButLast(
      final Path dir, final Predicate<Path> matching, final int count) throws IOException {
    if (!Files.isDirectory(dir)) {
      return;
    }

    final List<Path> files;
    try (final var list = Files.list(dir)) {
      files = list.filter(Files::isRegularFile).filter(matching).sorted().toList();
    }

    for (final Path file : files.subList(0, Math.max(0, files.size() - count))) {
      Files.deleteIfExists(file);
    }
  }

  // Every regular file below root matching keep, sorted; empty when root is not a directory.
  public static List<Path> walkFiles(final Path root, final Predicate<Path> keep)
      throws IOException {
    if (!Files.isDirectory(root)) {
      return List.of();
    }

    try (final var walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile).filter(keep).sorted().toList();
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

  // Dotted names of every package that contains at least one .class file below root, sorted and
  // deduped; empty when root is not a directory. The default (root) package is excluded — a named
  // module never has default-package classes.
  public static List<String> packagesWithClasses(final Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return List.of();
    }

    try (final var walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile)
          .filter(FileUtil::isClassFile)
          .map(path -> toPackageName(root, path))
          .filter(name -> !name.isEmpty())
          .distinct()
          .sorted()
          .toList();
    }
  }

  private static String toPackageName(final Path root, final Path classFile) {
    final Path packageDir = root.relativize(classFile.getParent());
    return packageDir.toString().replace(packageDir.getFileSystem().getSeparator(), ".");
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

  // Bumps an existing file's mtime to now, signalling a watcher without changing its content.
  public static void touch(final Path file) throws IOException {
    Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
  }

  public static void replaceDir(final Path src, final Path dest) throws IOException {
    deleteDir(dest);
    copyDir(src, dest);
  }

  // A relative path as saved in .lathe/: its names joined with '/' on every OS, so a file written
  // on Windows reads the same elsewhere. Joining names, not replacing '\', leaves a Linux file name
  // that contains '\' intact.
  public static String slashed(final Path relative) {
    return StreamSupport.stream(relative.spliterator(), false)
        .map(Path::toString)
        .collect(Collectors.joining("/"));
  }

  // Writes content atomically (creating dir) unless target already holds it; returns whether it
  // wrote, so callers log only real changes and avoid touching an unchanged file's mtime. A symlink
  // is always replaced by a file, even one whose target holds the same content.
  public static boolean writeIfChanged(
      final Path dir, final Path target, final String content, final boolean executable)
      throws IOException {
    return writeBytesIfChanged(dir, target, utf8(content), executable);
  }

  public static boolean copyIfChanged(
      final Path source, final Path dir, final Path target, final boolean executable)
      throws IOException {
    return writeBytesIfChanged(dir, target, Files.readAllBytes(source), executable);
  }

  // The bytes as they are, so a file in any charset (an argfile in native.encoding) is compared
  // and written unchanged.
  public static boolean writeBytesIfChanged(
      final Path dir, final Path target, final byte[] bytes, final boolean executable)
      throws IOException {
    if (Files.exists(target)
        && !Files.isSymbolicLink(target)
        && (!executable || Files.isExecutable(target))
        && Arrays.equals(bytes, Files.readAllBytes(target))) {
      return false;
    }

    Files.createDirectories(dir);
    writeBytesAtomically(dir, target, bytes, executable);
    return true;
  }

  public static void writeAtomically(
      final Path dir, final Path target, final String content, final boolean executable)
      throws IOException {
    writeBytesAtomically(dir, target, utf8(content), executable);
  }

  // Strict, like Files.writeString: malformed text fails instead of being written as '?'.
  private static byte[] utf8(final String content) throws CharacterCodingException {
    return encode(content, StandardCharsets.UTF_8);
  }

  public static byte[] encode(final String content, final Charset charset)
      throws CharacterCodingException {
    final ByteBuffer encoded = charset.newEncoder().encode(CharBuffer.wrap(content));
    final var bytes = new byte[encoded.remaining()];
    encoded.get(bytes);
    return bytes;
  }

  private static void writeBytesAtomically(
      final Path dir, final Path target, final byte[] content, final boolean executable)
      throws IOException {
    final var tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
    try {
      Files.write(tmp, content);
      if (executable) {
        setExecutable(tmp);
      }
      moveReplacing(tmp, target);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  /**
   * Moves {@code newDir} to {@code target} by renames, never deleting a visible {@code target} in
   * place: another build may be reading it. Concurrent callers each move in a complete copy, so if
   * another lands first, its copy stays and {@code newDir} is left for the caller to delete. Any
   * other failure is thrown.
   */
  public static void moveDirReplacing(final Path newDir, final Path target) throws IOException {
    final Path previous = newDir.resolveSibling("%s.old".formatted(newDir.getFileName()));
    for (int attempt = 1; ; attempt++) {
      try {
        Files.move(target, previous, StandardCopyOption.ATOMIC_MOVE);
      } catch (final NoSuchFileException e) {
        // No copy to replace yet.
      } catch (final FileSystemException e) {
        // Windows denies renaming a directory another caller is renaming at that moment.
        pauseBeforeRetry(attempt, e);
        continue;
      }

      try {
        Files.move(newDir, target, StandardCopyOption.ATOMIC_MOVE);
        return;
      } catch (final FileSystemException e) {
        // Other callers can hold the target aside for a moment; a real failure repeats every time.
        if (Files.isDirectory(target)) {
          return;
        }

        pauseBeforeRetry(attempt, e);
      } finally {
        if (Files.exists(previous)) {
          deleteDir(previous);
        }
      }
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
      for (final var source : walk.toList()) {
        copyEntry(src, dest, source);
      }
    }
  }

  // Copies each file below src matching keep that dest lacks; files dest already has are left as
  // they are. Returns how many it copied.
  public static int copyMissing(final Path src, final Path dest, final Predicate<Path> keep)
      throws IOException {
    final UnaryOperator<Path> target = file -> dest.resolve(src.relativize(file));
    final List<Path> missing =
        walkFiles(src, keep).stream().filter(file -> Files.notExists(target.apply(file))).toList();
    missing.forEach(file -> IOUtil.unchecked(() -> copyFileAtomically(file, target.apply(file))));
    return missing.size();
  }

  // Temp file + atomic rename so a concurrent reader (a replay JVM reading .lathe/) never sees a
  // partial file.
  public static void copyFileAtomically(final Path src, final Path dest) throws IOException {
    Files.createDirectories(dest.getParent());
    final var tmp = Files.createTempFile(dest.getParent(), ".lathe-copy-", ".tmp");
    try {
      Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
      moveReplacing(tmp, dest);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  public static void deleteDir(final Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }

    try (final var walk = Files.walk(dir)) {
      final List<Path> sorted = walk.sorted(Comparator.reverseOrder()).toList();
      for (final var path : sorted) {
        deleteRetrying(path);
      }
    }
  }

  // Extracted sources are read-only files, which Windows refuses to delete, so the bit is cleared
  // first. Its result goes unchecked: if clearing it fails, the delete fails too and is retried,
  // and the last failure reported is the delete's own. A path already gone counts as deleted: on
  // Windows a rename racing another rename of the same directory moves the directory the other
  // caller set aside, so both moveDirReplacing callers delete the same tree.
  private static void deleteRetrying(final Path path) throws IOException {
    for (int attempt = 1; ; attempt++) {
      if (!Files.isDirectory(path) && !Files.isWritable(path)) {
        path.toFile().setWritable(true);
      }

      try {
        Files.deleteIfExists(path);
        return;
      } catch (final FileSystemException e) {
        pauseBeforeRetry(attempt, e);
      }
    }
  }

  // Rethrows the last attempt's failure; before any other, waits a little longer than last time.
  private static void pauseBeforeRetry(final int attempt, final FileSystemException failure)
      throws IOException {
    if (attempt == RETRY_ATTEMPTS) {
      throw failure;
    }

    IOUtil.sleep(RETRY_PAUSE_MS * attempt);
  }

  // Returns the number of entries skipped because they would escape destDir (see extractZipEntry).
  // Entries are written in parallel (creating many small files is slow on Windows), split from a
  // list: ZipFile's own stream splits only per 1024 entries. A failure is a value, not thrown, so
  // no write outlives the call.
  public static int unzip(final Path zipFile, final Path destDir) throws IOException {
    try (final var zip = new ZipFile(zipFile.toFile())) {
      final List<EntryResult> results =
          zip.stream().toList().parallelStream()
              .map(entry -> unzipEntry(zip, destDir, entry))
              .toList();
      final Optional<IOException> failure =
          results.stream().map(EntryResult::failure).filter(Objects::nonNull).findFirst();
      if (failure.isPresent()) {
        throw failure.get();
      }

      return (int) results.stream().filter(EntryResult::skipped).count();
    }
  }

  private static EntryResult unzipEntry(
      final ZipFile zip, final Path destDir, final ZipEntry entry) {
    try {
      return new EntryResult(!extractZipEntry(destDir, zip, entry), null);
    } catch (final IOException e) {
      return new EntryResult(false, e);
    }
  }

  // One entry's outcome: written (or deliberately dropped), skipped as unsafe, or failed.
  private record EntryResult(boolean skipped, IOException failure) {
    private EntryResult {
      ValidCheck.check()
          .assertTrue(!skipped || failure == null, "a skipped entry has no failure")
          .validate();
    }
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

  // Extracts a single zip entry to `dest`, read-only, without unpacking the rest of the archive.
  public static void extractEntry(final Path zipFile, final String entryName, final Path dest)
      throws IOException {
    try (final var zip = new ZipFile(zipFile.toFile())) {
      final ZipEntry entry = zip.getEntry(entryName);
      if (entry == null || entry.isDirectory()) {
        throw new IOException("no entry %s in %s".formatted(entryName, zipFile));
      }

      writeEntry(zip, entry, dest);
    }
  }

  private static void writeEntry(final ZipFile zip, final ZipEntry entry, final Path dest)
      throws IOException {
    Files.createDirectories(dest.getParent());
    try (final var in = zip.getInputStream(entry)) {
      Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    setReadOnly(dest);
  }

  // The UTF-8 text of one zip entry, without unpacking the rest; empty when there is no such file.
  public static Optional<String> readEntry(final Path zipFile, final String entryName)
      throws IOException {
    try (final var zip = new ZipFile(zipFile.toFile())) {
      final ZipEntry entry = zip.getEntry(entryName);
      if (entry == null || entry.isDirectory()) {
        return Optional.empty();
      }

      try (final var in = zip.getInputStream(entry)) {
        return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
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

  // Extracts one entry under destDir. An absolute entry name (e.g. "/home/x/Foo.java", seen in
  // obfuscated or vendored jars) is neutralized to a path relative to destDir; anything that would
  // still escape destDir after normalization — a zip-slip attempt — is skipped rather than written,
  // returning false so a single hostile or malformed entry cannot abort the whole archive.
  private static boolean extractZipEntry(
      final Path destDir, final ZipFile zip, final ZipEntry entry) throws IOException {
    final Path entryPath = Path.of(entry.getName());
    // A rooted entry (/x) is placed under destDir: on Windows /x has a root but no drive, so it is
    // not "absolute" there, yet resolving it would still leave destDir.
    final Path relative =
        entryPath.getRoot() != null ? entryPath.getRoot().relativize(entryPath) : entryPath;
    if (relative.getNameCount() == 0) {
      return false;
    }

    // Build metadata holds no source, but its <group>/<artifact> nesting makes the longest cache
    // paths, past Windows' 260-character limit.
    if (relative.startsWith(LatheLayout.JAR_MAVEN_METADATA_DIR)) {
      return true;
    }

    final Path target = destDir.resolve(relative).normalize();
    if (!target.startsWith(destDir)) {
      return false;
    }

    if (entry.isDirectory()) {
      Files.createDirectories(target);
      return true;
    }

    writeEntry(zip, entry, target);
    return true;
  }

  private static void setReadOnly(final Path path) throws IOException {
    if (!path.toFile().setReadOnly()) {
      throw new IOException("Failed to set read-only: %s".formatted(path));
    }
  }

  private static void setExecutable(final Path path) throws IOException {
    if (!path.toFile().setExecutable(true, false)) {
      throw new IOException("Failed to set executable: %s".formatted(path));
    }
  }
}
