package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceCandidateIndexTest {

  @TempDir Path root;

  // A module whose generated sources live under the .lathe mirror (the searched, fresh copy).
  private ModuleSourceConfig withGeneratedSources(final Path src) {
    return TestCompiler.moduleConfig(
        root.resolve(".lathe/module"),
        root.resolve("target/classes"),
        src,
        root.resolve("target/generated-sources/annotations"));
  }

  private String uri(final Path file) {
    return file.toUri().toString();
  }

  @Test
  void build_indexesAllJavaFilesUnderSourceRoot() throws IOException {
    final var src = Files.createDirectories(root.resolve("src"));
    final var foo = Files.writeString(src.resolve("Foo.java"), "class Foo { void bar() {} }");
    final var baz = Files.writeString(src.resolve("Baz.java"), "class Baz { Foo field; }");

    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));

    assertThat(index.candidateUris("bar")).containsExactly(uri(foo));
    assertThat(index.candidateUris("Foo")).containsExactlyInAnyOrder(uri(foo), uri(baz));
    assertThat(index.candidateUris("absent")).isEmpty();
  }

  @Test
  void build_ignoresNonJavaFiles() throws IOException {
    final var src = Files.createDirectories(root.resolve("src"));
    Files.writeString(src.resolve("README.txt"), "class NotJava { void myMethod() {} }");
    Files.writeString(src.resolve("Real.java"), "class Real {}");

    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));

    assertThat(index.candidateUris("myMethod")).isEmpty();
  }

  @Test
  void update_replacesOldTokensWithNewContent() throws IOException {
    final var src = Files.createDirectories(root.resolve("src"));
    final var file = Files.writeString(src.resolve("A.java"), "class A { void oldMethod() {} }");
    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));

    assertThat(index.candidateUris("oldMethod")).containsExactly(uri(file));

    index.update(uri(file), "class A { void newMethod() {} }");

    assertThat(index.candidateUris("oldMethod")).isEmpty();
    assertThat(index.candidateUris("newMethod")).containsExactly(uri(file));
  }

  @Test
  void remove_cleansUpAllTokensForUri() throws IOException {
    final var src = Files.createDirectories(root.resolve("src"));
    final var file = Files.writeString(src.resolve("A.java"), "class Alpha { void beta() {} }");
    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));

    assertThat(index.candidateUris("Alpha")).isNotEmpty();

    index.remove(uri(file));

    assertThat(index.candidateUris("Alpha")).isEmpty();
    assertThat(index.candidateUris("beta")).isEmpty();
  }

  @Test
  void update_openFileContentOverridesDiskTokens() throws IOException {
    final var src = Files.createDirectories(root.resolve("src"));
    final var file = Files.writeString(src.resolve("A.java"), "class A { void diskMethod() {} }");
    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));

    // Simulate open document with unsaved edits
    index.update(uri(file), "class A { void liveMethod() {} }");

    assertThat(index.candidateUris("diskMethod")).isEmpty();
    assertThat(index.candidateUris("liveMethod")).containsExactly(uri(file));
  }

  @Test
  void build_deduplicatesSourceRootsAcrossConfigs() throws IOException {
    final var src = Files.createDirectories(root.resolve("src"));
    Files.writeString(src.resolve("A.java"), "class A {}");
    final var config = TestCompiler.moduleConfig(root, src);

    // Same source root in two configs — file should appear only once
    final var index = ReferenceCandidateIndex.build(List.of(config, config));

    assertThat(index.candidateUris("A")).hasSize(1);
  }

  @Test
  void candidateUris_returnsEmptyForUnknownToken() throws IOException {
    final var src = Files.createDirectories(root.resolve("src"));
    Files.writeString(src.resolve("A.java"), "class A {}");
    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));

    assertThat(index.candidateUris("nonexistent")).isEmpty();
  }

  @Test
  void update_extractsFullyQualifiedImports() throws IOException {
    final var src = Files.createDirectories(root.resolve("src"));
    final var file =
        Files.writeString(
            src.resolve("A.java"),
            """
            import java.util.List;
            import static java.util.Collections.emptyList;
            import java.util.concurrent.*;
            class A {}""");
    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));

    assertThat(index.candidateUris("java.util.List")).containsExactly(uri(file));
    assertThat(index.candidateUris("java.util.Collections.emptyList")).containsExactly(uri(file));
    assertThat(index.candidateUris("java.util.concurrent.*")).containsExactly(uri(file));
  }

  @Test
  void update_nestedTypeImport_indexesEnclosingTypeSpelling() throws IOException {
    final var src = Files.createDirectories(root.resolve("src"));
    final var file =
        Files.writeString(
            src.resolve("A.java"),
            """
            import com.example.Outer.Inner;
            import static com.example.Owner.member;
            import java.util.List;
            class A {}""");
    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));

    // A search for the enclosing type `com.example.Outer` must discover the file that only imports
    // its nested type `com.example.Outer.Inner`; likewise the static-import owner
    // `com.example.Owner`.
    assertThat(index.candidateUris("com.example.Outer")).containsExactly(uri(file));
    assertThat(index.candidateUris("com.example.Owner")).containsExactly(uri(file));
    // The lowercase package prefix of an ordinary import must not be indexed as a type token.
    assertThat(index.candidateUris("java.util")).isEmpty();
  }

  @Test
  void build_importWordInCommentEndingInDots_indexesFileWithoutFailing() throws IOException {
    // The JDK's Xerces XSDHandler has this comment: the text scan reads "import" up to the next
    // ';' as `theonecontainingdecl...returnnull`, whose enclosing spelling ends in a dot.
    final var src = Files.createDirectories(root.resolve("src"));
    final var file =
        Files.writeString(
            src.resolve("Handler.java"),
            """
            class Handler {
              Object find() {
                // import the one containing decl...
                return null;
              }
            }""");

    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));

    assertThat(index.candidateUris("Handler")).containsExactly(uri(file));
  }

  @Test
  void build_includesGeneratedSourcesDir_whenPresent() throws IOException {
    final var src = Files.createDirectories(root.resolve("src"));
    Files.writeString(
        src.resolve("Entity.java"), "class Entity { String requestId() { return null; } }");
    // EG-052: the generated companion lives under the .lathe mirror (the searched, fresh copy);
    // originalGenSourcesDir points at Maven's target/ copy only to flag "has generated sources".
    final var config = withGeneratedSources(src);
    final var gen = Files.createDirectories(config.generatedSourcesDir());
    Files.writeString(
        gen.resolve("EntityBuilder.java"),
        "class EntityBuilder { Entity build() { return null; } }");

    final var index = ReferenceCandidateIndex.build(List.of(config));

    assertThat(index.candidateUris("EntityBuilder")).isNotEmpty();
    assertThat(index.candidateUris("build")).isNotEmpty();
  }

  @Test
  void syncFromDisk_changedNewAndDeletedFiles_followDiskButOpenFileKeepsBuffer()
      throws IOException {
    final var src = Files.createDirectories(root.resolve("src"));
    final var changed =
        TestCompiler.writeAt(src.resolve("A.java"), "class A { void oldMethod() {} }", 1_000L);
    final var deleted =
        TestCompiler.writeAt(src.resolve("B.java"), "class B { void goneMethod() {} }", 1_000L);
    final var open =
        TestCompiler.writeAt(src.resolve("C.java"), "class C { void diskMethod() {} }", 1_000L);
    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));
    index.update(uri(open), "class C { void bufferMethod() {} }");

    TestCompiler.writeAt(changed, "class A { void newMethod() {} }", 2_000L);
    Files.delete(deleted);
    final var added =
        TestCompiler.writeAt(src.resolve("D.java"), "class D { void addedMethod() {} }", 2_000L);
    TestCompiler.writeAt(open, "class C { void otherDiskMethod() {} }", 2_000L);
    index.syncFromDisk(FileUtil.fileMtimes(src, FileUtil::isJavaFile), uri(open)::equals);

    assertThat(index.candidateUris("oldMethod")).isEmpty();
    assertThat(index.candidateUris("newMethod")).containsExactly(uri(changed));
    assertThat(index.candidateUris("goneMethod")).isEmpty();
    assertThat(index.candidateUris("addedMethod")).containsExactly(uri(added));
    assertThat(index.candidateUris("bufferMethod")).containsExactly(uri(open));
    assertThat(index.candidateUris("otherDiskMethod")).isEmpty();
  }

  @Test
  void syncFromDisk_unchangedMtimeOrGeneratedSource_leavesTokensAlone() throws IOException {
    final var src = Files.createDirectories(root.resolve("src"));
    final var file =
        TestCompiler.writeAt(src.resolve("A.java"), "class A { void indexedMethod() {} }", 1_000L);
    final var config = withGeneratedSources(src);
    final var gen = Files.createDirectories(config.generatedSourcesDir());
    Files.writeString(gen.resolve("ABuilder.java"), "class ABuilder { void generatedMethod() {} }");
    final var index = ReferenceCandidateIndex.build(List.of(config));

    // Same mtime: the content is not read again, so the rewrite stays invisible.
    TestCompiler.writeAt(file, "class A { void rewrittenMethod() {} }", 1_000L);
    // The pass scans only non-generated roots; a generated source missing from it is not deleted.
    index.syncFromDisk(FileUtil.fileMtimes(src, FileUtil::isJavaFile), uri -> false);

    assertThat(index.candidateUris("indexedMethod")).containsExactly(uri(file));
    assertThat(index.candidateUris("rewrittenMethod")).isEmpty();
    assertThat(index.candidateUris("generatedMethod")).isNotEmpty();
  }
}
