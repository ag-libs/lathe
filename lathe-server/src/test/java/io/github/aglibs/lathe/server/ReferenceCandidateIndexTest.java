package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceCandidateIndexTest {

  @TempDir Path root;

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
}
