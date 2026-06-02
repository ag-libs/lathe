package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.typeindex.DependencyTypeIndexOrigin;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeIndexFile;
import io.github.aglibs.lathe.core.typeindex.TypeIndexOrigin;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTypeIndexTest {

  @TempDir private Path tmp;

  private static TypeIndexEntry entry(final String simpleName, final String pkg) {
    return new TypeIndexEntry(simpleName, pkg + "." + simpleName, pkg, TypeKind.CLASS);
  }

  private static TypeIndexFile shard(final TypeIndexEntry... entries) {
    return new TypeIndexFile(
        "v1",
        TypeIndexOrigin.dependency(
            new DependencyTypeIndexOrigin("com.example:lib:1.0", "/lib.jar", 0L, 0L)),
        List.of(entries));
  }

  private static Path writeShard(final Path dir, final String name, final TypeIndexFile file)
      throws IOException {
    final var path = dir.resolve(name);
    Json.write(file, path);
    return path;
  }

  @Test
  void empty_search_returnsEmpty() {
    assertThat(WorkspaceTypeIndex.empty().search("Foo", 10)).isEmpty();
  }

  @Test
  void build_singleShard_searchByPrefix() throws IOException {
    final var shard =
        writeShard(
            tmp,
            "shard.json",
            shard(
                entry("FooService", "com.example"),
                entry("FooBar", "com.example"),
                entry("BazClient", "org.other")));

    final var index = WorkspaceTypeIndex.build(List.of(shard));

    assertThat(index.search("Foo", 10))
        .extracting(TypeIndexEntry::simpleName)
        .containsExactlyInAnyOrder("FooService", "FooBar");
    assertThat(index.search("Baz", 10))
        .extracting(TypeIndexEntry::simpleName)
        .containsExactly("BazClient");
    assertThat(index.search("xyz", 10)).isEmpty();
  }

  @Test
  void build_search_caseInsensitive() throws IOException {
    final var shard = writeShard(tmp, "shard.json", shard(entry("FooService", "com.example")));

    final var index = WorkspaceTypeIndex.build(List.of(shard));

    assertThat(index.search("foo", 10))
        .extracting(TypeIndexEntry::simpleName)
        .contains("FooService");
    assertThat(index.search("FOO", 10))
        .extracting(TypeIndexEntry::simpleName)
        .contains("FooService");
  }

  @Test
  void build_search_limitsResults() throws IOException {
    final var shard =
        writeShard(
            tmp,
            "shard.json",
            shard(
                entry("FooA", "com.example"),
                entry("FooB", "com.example"),
                entry("FooC", "com.example")));

    final var index = WorkspaceTypeIndex.build(List.of(shard));

    assertThat(index.search("Foo", 2)).hasSize(2);
  }

  @Test
  void build_missingShardIgnored() {
    final var missing = tmp.resolve("missing.json");
    final var index = WorkspaceTypeIndex.build(List.of(missing));
    assertThat(index.search("Foo", 10)).isEmpty();
  }

  @Test
  void build_multipleShards_mergedIntoIndex() throws IOException {
    final var shardA = writeShard(tmp, "a.json", shard(entry("Alpha", "com.a")));
    final var shardB = writeShard(tmp, "b.json", shard(entry("Beta", "com.b")));

    final var index = WorkspaceTypeIndex.build(List.of(shardA, shardB));

    assertThat(index.search("Al", 10)).extracting(TypeIndexEntry::simpleName).contains("Alpha");
    assertThat(index.search("Be", 10)).extracting(TypeIndexEntry::simpleName).contains("Beta");
  }

  @Test
  void build_reactorEntries_mergedWithShardEntries() throws IOException {
    final var shard = writeShard(tmp, "shard.json", shard(entry("Alpha", "com.a")));

    final var index =
        WorkspaceTypeIndex.build(List.of(shard), List.of(List.of(entry("Beta", "com.reactor"))));

    assertThat(index.search("Al", 10)).extracting(TypeIndexEntry::simpleName).contains("Alpha");
    assertThat(index.search("Be", 10)).extracting(TypeIndexEntry::simpleName).contains("Beta");
  }
}
