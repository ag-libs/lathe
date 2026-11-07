package io.github.aglibs.lathe.server.files;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import io.github.aglibs.lathe.core.testing.ZipFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerFilesTest {

  @TempDir private Path tempDir;

  @Test
  void readIfPresent_fileMissingOrADirectory_emptyOtherwiseItsText() throws IOException {
    final Path file = Files.writeString(tempDir.resolve("A.java"), "class A {}");

    assertThat(ServerFiles.readIfPresent(file)).hasValue("class A {}");
    assertThat(ServerFiles.readIfPresent(tempDir.resolve("Missing.java"))).isEmpty();
    assertThat(ServerFiles.readIfPresent(tempDir)).isEmpty();
  }

  @Test
  void listEntries_predicate_keepsMatchesSkipsDirsAndFiltered() throws IOException {
    final Path jar =
        ZipFixture.create(
            tempDir.resolve("lib.jar"),
            Map.of(
                "com/x/schema.graphqls", "type Query", "com/x/App.class", "bytecode", "dir/", ""));

    final List<String> names = ServerFiles.listEntries(jar, name -> !name.endsWith(".class"));

    // .class filtered by the predicate, "dir/" skipped as a directory
    assertThat(names).containsExactly("com/x/schema.graphqls");
  }

  @Test
  void classBinaryName_classFilesBelowRoot_dottedWithNestedAndDefaultPackage() {
    final Path root = tempDir.resolve("classes");

    assertThat(ServerFiles.classBinaryName(root, root.resolve("com/example/Foo$Inner.class")))
        .isEqualTo("com.example.Foo$Inner");
    assertThat(ServerFiles.classBinaryName(root, root.resolve("module-info.class")))
        .isEqualTo("module-info");
  }

  @Test
  void hasContent_sameChangedMissingOrNoRecord_trueOnlyForTheSameContent() throws IOException {
    final Path file = Files.writeString(tempDir.resolve("Foo.java"), "class Foo {}");
    final ContentFingerprint recorded = FileUtil.fingerprint(file);

    assertThat(ServerFiles.hasContent(file, recorded)).as("same content").isTrue();
    assertThat(ServerFiles.hasContent(file, null)).as("no record").isFalse();
    Files.writeString(file, "class Foo { int changed; }");
    assertThat(ServerFiles.hasContent(file, recorded)).as("changed content").isFalse();
    Files.delete(file);
    assertThat(ServerFiles.hasContent(file, recorded)).as("missing file").isFalse();
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

    assertThat(ServerFiles.fileMtimes(root, FileUtil::isJavaFile))
        .as("only the kept regular files, each with its mtime; directories never")
        .isEqualTo(Map.of(java, 5_000L));
    assertThat(ServerFiles.fileMtimes(tempDir.resolve("missing"), path -> true)).isEmpty();
  }
}
