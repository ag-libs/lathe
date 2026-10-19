package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileUtilTest {

  @TempDir private Path tempDir;

  @Test
  void listEntries_predicate_keepsMatchesSkipsDirsAndFiltered() throws IOException {
    final Path jar =
        ZipFixture.create(
            tempDir.resolve("lib.jar"),
            Map.of(
                "com/x/schema.graphqls", "type Query", "com/x/App.class", "bytecode", "dir/", ""));

    final List<String> names = FileUtil.listEntries(jar, name -> !name.endsWith(".class"));

    // .class filtered by the predicate, "dir/" skipped as a directory
    assertThat(names).containsExactly("com/x/schema.graphqls");
  }

  @Test
  void extractEntry_singleEntry_writesReadOnlyAndNothingElse() throws IOException {
    final Path jar =
        ZipFixture.create(
            tempDir.resolve("lib.jar"),
            Map.of("com/x/schema.graphqls", "type Query", "com/x/App.class", "bytecode"));
    final Path dest = tempDir.resolve("out/schema.graphqls");

    FileUtil.extractEntry(jar, "com/x/schema.graphqls", dest);

    assertThat(dest).hasContent("type Query");
    assertThat(dest.toFile().canWrite()).isFalse();
    assertThat(tempDir.resolve("out/com/x/App.class")).doesNotExist();
  }

  @Test
  void extractEntry_missingEntry_throwsIOException() throws IOException {
    final Path jar = ZipFixture.create(tempDir.resolve("lib.jar"), "a.txt", "x");

    assertThatThrownBy(() -> FileUtil.extractEntry(jar, "nope.txt", tempDir.resolve("d/nope.txt")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no entry");
  }

  @Test
  void unzip_absoluteAndEscapingEntries_neutralizesAbsoluteSkipsEscapingReturnsCount()
      throws IOException {
    final Path zip =
        ZipFixture.create(
            tempDir.resolve("bad.jar"),
            Map.of(
                "com/example/Hello.java", "class Hello {}",
                "/home/x/Abs.java", "abs",
                "../escape.java", "bad"));

    final Path dest = tempDir.resolve("dest");
    final int skipped = FileUtil.unzip(zip, dest);

    // Normal entry extracted, absolute entry neutralized to a path under dest.
    assertThat(dest.resolve("com/example/Hello.java")).hasContent("class Hello {}");
    assertThat(dest.resolve("home/x/Abs.java")).hasContent("abs");
    // The escaping entry is skipped, never written outside dest, and counted.
    assertThat(tempDir.resolve("escape.java")).doesNotExist();
    assertThat(skipped).isEqualTo(1);
  }

  @Test
  void touch_existingFile_bumpsMtimeKeepsContent_missingFileThrows() throws IOException {
    final Path file = Files.writeString(tempDir.resolve("workspace.json"), "{}");
    Files.setLastModifiedTime(file, FileTime.fromMillis(1_000L));

    FileUtil.touch(file);

    assertThat(Files.getLastModifiedTime(file).toMillis()).isGreaterThan(1_000L);
    assertThat(Files.readString(file)).isEqualTo("{}");
    assertThatThrownBy(() -> FileUtil.touch(tempDir.resolve("missing.json")))
        .isInstanceOf(IOException.class);
  }

  @Test
  void deleteDir_nestedTree_deletesRecursively() throws IOException {
    final Path nested = tempDir.resolve("tree/a/b");
    Files.createDirectories(nested);
    Files.writeString(nested.resolve("file.txt"), "content");

    FileUtil.deleteDir(tempDir.resolve("tree"));

    assertThat(tempDir.resolve("tree")).doesNotExist();
  }

  @Test
  void subdirectories_nestedTree_returnsDescendantsSortedExcludingRoot() throws IOException {
    Files.createDirectories(tempDir.resolve("com/example/sub"));
    Files.writeString(tempDir.resolve("com/example/Foo.java"), "");

    assertThat(FileUtil.subdirectories(tempDir))
        .containsExactly(
            tempDir.resolve("com"),
            tempDir.resolve("com/example"),
            tempDir.resolve("com/example/sub"));
  }

  @Test
  void subdirectories_missingRoot_returnsEmpty() throws IOException {
    assertThat(FileUtil.subdirectories(tempDir.resolve("nope"))).isEmpty();
  }

  @Test
  void packagesWithClasses_nestedPackagesWithClasses_returnsDottedPackages() throws IOException {
    writeEmpty(tempDir.resolve("com/example/app/Foo.class"));
    writeEmpty(tempDir.resolve("com/example/app/Bar.class"));
    writeEmpty(tempDir.resolve("com/example/util/Util.class"));

    assertThat(FileUtil.packagesWithClasses(tempDir))
        .containsExactly("com.example.app", "com.example.util");
  }

  @Test
  void packagesWithClasses_nonClassFilesAndDefaultPackage_areExcluded() throws IOException {
    writeEmpty(tempDir.resolve("Root.class"));
    writeEmpty(tempDir.resolve("com/example/resources/data.txt"));
    writeEmpty(tempDir.resolve("com/example/app/Foo.class"));

    assertThat(FileUtil.packagesWithClasses(tempDir)).containsExactly("com.example.app");
  }

  @Test
  void packagesWithClasses_missingRoot_returnsEmpty() throws IOException {
    assertThat(FileUtil.packagesWithClasses(tempDir.resolve("nope"))).isEmpty();
  }

  @Test
  void moveDirReplacing_existingTarget_replacedAndNoBackupLeft() throws IOException {
    final Path target = copyOf("old");
    final Path newDir = copyOf("new");

    FileUtil.moveDirReplacing(newDir, target);

    assertThat(target.resolve("a")).hasContent("new");
    assertThat(newDir).doesNotExist();
    assertThat(siblingNames()).containsExactly(target.getFileName().toString());
  }

  @Test
  void moveDirReplacing_moveFails_throwsInsteadOfReportingSuccess() throws IOException {
    final Path target = copyOf("old");

    assertThatThrownBy(() -> FileUtil.moveDirReplacing(tempDir.resolve("missing"), target))
        .isInstanceOf(NoSuchFileException.class);
  }

  @Test
  void moveDirReplacing_concurrentCallers_leaveOneCompleteCopy() throws Exception {
    final Path target = tempDir.resolve("target");
    final List<Callable<Void>> replacements =
        IntStream.range(0, 8)
            .mapToObj(i -> IOUtil.unchecked(() -> copyOf("v" + i)))
            .<Callable<Void>>map(
                copy ->
                    () -> {
                      FileUtil.moveDirReplacing(copy, target);
                      return null;
                    })
            .toList();
    final var pool = Executors.newFixedThreadPool(replacements.size());
    try {
      for (final Future<Void> replaced : pool.invokeAll(replacements)) {
        replaced.get();
      }
    } finally {
      pool.shutdown();
    }

    assertThat(Files.readString(target.resolve("a")))
        .as("both files come from the same copy")
        .isEqualTo(Files.readString(target.resolve("b")));
    assertThat(siblingNames()).noneMatch(name -> name.endsWith(".old"));
  }

  private Path copyOf(final String content) throws IOException {
    final Path dir = Files.createDirectories(tempDir.resolve("copy-%s".formatted(content)));
    Files.writeString(dir.resolve("a"), content);
    Files.writeString(dir.resolve("b"), content);
    return dir;
  }

  private List<String> siblingNames() throws IOException {
    try (final var children = Files.list(tempDir)) {
      return children.map(child -> child.getFileName().toString()).toList();
    }
  }

  @Test
  void moveReplacing_existingDest_replacesFile() throws IOException {
    final Path src = tempDir.resolve("src.txt");
    final Path dest = tempDir.resolve("dest.txt");
    Files.writeString(src, "new");
    Files.writeString(dest, "old");

    FileUtil.moveReplacing(src, dest);

    assertThat(src).doesNotExist();
    assertThat(dest).hasContent("new");
  }

  @Test
  void writeIfChanged_newSameAndChangedContent_writesOnlyWhenDifferent() throws IOException {
    final Path dir = tempDir.resolve("nested");
    final Path file = dir.resolve("args");

    assertThat(FileUtil.writeIfChanged(dir, file, "a\n", false)).isTrue();
    assertThat(FileUtil.writeIfChanged(dir, file, "a\n", false)).isFalse();
    assertThat(FileUtil.writeIfChanged(dir, file, "b\n", false)).isTrue();
    assertThat(file).hasContent("b");
    // Same content but not yet executable: rewritten so the launcher can run.
    assertThat(FileUtil.writeIfChanged(dir, file, "b\n", true)).isTrue();
    assertThat(Files.isExecutable(file)).isTrue();
    assertThat(FileUtil.writeIfChanged(dir, file, "b\n", true)).isFalse();
  }

  // Workspaces synced by older versions hold launcher symlinks; the replacement must not write
  // through the link into the shared target.
  @Test
  void writeIfChanged_symlinkToSameContent_replacedByFile_targetUntouched() throws IOException {
    final Path target = tempDir.resolve("target.sh");
    final Path link = tempDir.resolve("link.sh");
    Files.writeString(target, "same\n");
    Files.createSymbolicLink(link, target);

    assertThat(FileUtil.writeIfChanged(tempDir, link, "same\n", false)).isTrue();
    assertThat(FileUtil.writeIfChanged(tempDir, link, "new\n", false)).isTrue();
    assertThat(link).hasContent("new");
    assertThat(target).hasContent("same");
  }

  private static void writeEmpty(final Path file) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, "");
  }

  @Test
  void fileMtimes_regularFilesKeptByTheFilter_withTheirMtimes_emptyForMissingRoot()
      throws Exception {
    final Path root = tempDir.resolve("src");
    final Path java = root.resolve("com/example/A.java");
    final Path text = root.resolve("com/example/notes.txt");
    Files.createDirectories(java.getParent());
    Files.writeString(java, "class A {}");
    Files.writeString(text, "notes");
    Files.setLastModifiedTime(java, FileTime.fromMillis(5_000L));

    assertThat(FileUtil.fileMtimes(root, FileUtil::isJavaFile))
        .as("only the kept regular files, each with its mtime; directories never")
        .isEqualTo(Map.of(java, 5_000L));
    assertThat(FileUtil.fileMtimes(tempDir.resolve("missing"), path -> true)).isEmpty();
  }
}
