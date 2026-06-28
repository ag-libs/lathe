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
    return new TypeIndexEntry(
        simpleName, "%s.%s".formatted(pkg, simpleName), pkg, TypeKind.CLASS, true, List.of());
  }

  private static TypeIndexEntry graphEntry(
      final String binaryName, final boolean typeNameCandidate, final String... directSupertypes) {
    final int packageEnd = binaryName.lastIndexOf('.');
    final String packageName = packageEnd > 0 ? binaryName.substring(0, packageEnd) : "";
    final int nestedNameStart = binaryName.lastIndexOf('$') + 1;
    final String simpleName = binaryName.substring(Math.max(packageEnd + 1, nestedNameStart));
    return new TypeIndexEntry(
        simpleName,
        binaryName,
        packageName,
        TypeKind.CLASS,
        typeNameCandidate,
        List.of(directSupertypes));
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

  @Test
  void build_nonTypeNameCandidateInReactor_includedInSearch() {
    final var packagePrivate =
        new TypeIndexEntry(
            "FooTest", "com.example.FooTest", "com.example", TypeKind.CLASS, false, List.of());

    final var index = WorkspaceTypeIndex.build(List.of(), List.of(List.of(packagePrivate)));

    assertThat(index.search("FooTest", 10))
        .extracting(TypeIndexEntry::simpleName)
        .containsExactly("FooTest");
  }

  @Test
  void build_nonTypeNameCandidateInStaticShard_excludedFromSearch() throws IOException {
    final var shardPath =
        writeShard(
            tmp,
            "dep.json",
            shard(
                new TypeIndexEntry(
                    "Hidden", "com.dep.Hidden", "com.dep", TypeKind.CLASS, false, List.of())));

    final var index = WorkspaceTypeIndex.build(List.of(shardPath));

    assertThat(index.search("Hidden", 10)).isEmpty();
  }

  @Test
  void graph_directRelations_returnsImmediateTypes() {
    final var object = graphEntry("java.lang.Object", true);
    final var base = graphEntry("com.example.Base", true, "java.lang.Object");
    final var child = graphEntry("com.example.Child", true, "com.example.Base");
    final var index = WorkspaceTypeIndex.build(List.of(), List.of(List.of(object, base, child)));

    assertThat(index.directSupertypes("com.example.Child"))
        .extracting(TypeIndexEntry::binaryName)
        .containsExactly("com.example.Base");
    assertThat(index.directSubtypes("com.example.Base"))
        .extracting(TypeIndexEntry::binaryName)
        .containsExactly("com.example.Child");
    assertThat(index.directSubtypes("java.lang.Object"))
        .extracting(TypeIndexEntry::binaryName)
        .containsExactly("com.example.Base");
  }

  @Test
  void graph_transitiveSubtypes_traversesGraphOnlyIntermediate() {
    final var base = graphEntry("com.example.Base", true);
    final var internal = graphEntry("com.example.Internal", false, "com.example.Base");
    final var leaf = graphEntry("com.example.Leaf", true, "com.example.Internal");
    final var index = WorkspaceTypeIndex.build(List.of(), List.of(List.of(base, internal, leaf)));

    assertThat(index.transitiveSubtypes("com.example.Base"))
        .extracting(TypeIndexEntry::binaryName)
        .containsExactly("com.example.Internal", "com.example.Leaf");
  }

  @Test
  void graph_transitiveSubtypes_cycle_excludesTargetAndTerminates() {
    final var a = graphEntry("com.example.A", true, "com.example.C");
    final var b = graphEntry("com.example.B", true, "com.example.A");
    final var c = graphEntry("com.example.C", true, "com.example.B");
    final var index = WorkspaceTypeIndex.build(List.of(), List.of(List.of(a, b, c)));

    assertThat(index.transitiveSubtypes("com.example.A"))
        .extracting(TypeIndexEntry::binaryName)
        .containsExactly("com.example.B", "com.example.C");
  }

  @Test
  void graph_duplicateBinaryName_withinShard_keepsFirst() {
    final var first = graphEntry("com.example.Duplicate", true, "com.example.ParentA");
    final var second = graphEntry("com.example.Duplicate", true, "com.example.ParentB");
    final var index = WorkspaceTypeIndex.build(List.of(), List.of(List.of(first, second)));

    assertThat(index.findType("com.example.Duplicate"))
        .hasValueSatisfying(
            e -> assertThat(e.directSupertypes()).containsExactly("com.example.ParentA"));
  }

  @Test
  void merge_duplicateTypeAcrossShards_keepsFirstEntry() {
    final var fromShardA = graphEntry("com.example.Dup", true, "com.example.ParentA");
    final var fromShardB = graphEntry("com.example.Dup", true, "com.example.ParentB");
    final var index =
        WorkspaceTypeIndex.build(List.of(), List.of(List.of(fromShardA), List.of(fromShardB)));

    assertThat(index.findType("com.example.Dup"))
        .hasValueSatisfying(
            e -> assertThat(e.directSupertypes()).containsExactly("com.example.ParentA"));
  }

  @Test
  void withReactorEntries_replacesReactorEntriesAndPreservesSnapshot() throws IOException {
    final var shard = writeShard(tmp, "shard.json", shard(entry("Alpha", "com.static")));
    final var original =
        WorkspaceTypeIndex.build(List.of(shard), List.of(List.of(entry("Beta", "com.reactor"))));

    final var refreshed =
        original.withReactorEntries(List.of(List.of(entry("Gamma", "com.reactor"))));

    assertThat(refreshed.search("Alpha", 10)).hasSize(1);
    assertThat(refreshed.search("Beta", 10)).isEmpty();
    assertThat(refreshed.search("Gamma", 10)).hasSize(1);
    assertThat(original.search("Beta", 10)).hasSize(1);
    assertThat(original.search("Gamma", 10)).isEmpty();
  }

  @Test
  void withReactorEntries_changedStaticShard_reloadsOnlyOnBuild() throws IOException {
    final var shard = writeShard(tmp, "shard.json", shard(entry("Alpha", "com.static")));
    final var original = WorkspaceTypeIndex.build(List.of(shard));
    writeShard(tmp, "shard.json", shard(entry("Delta", "com.static")));

    final var refreshed = original.withReactorEntries(List.of());
    final var reloaded = WorkspaceTypeIndex.build(List.of(shard));

    assertThat(refreshed.search("Alpha", 10)).hasSize(1);
    assertThat(refreshed.search("Delta", 10)).isEmpty();
    assertThat(reloaded.search("Alpha", 10)).isEmpty();
    assertThat(reloaded.search("Delta", 10)).hasSize(1);
  }

  @Test
  void search_exactName_ranksReactorTypeBeforeDependencyType() throws IOException {
    final var depShard = writeShard(tmp, "dep.json", shard(entry("Application", "com.dep")));
    final var reactorEntry = entry("Application", "com.reactor");

    final var index = WorkspaceTypeIndex.build(List.of(depShard), List.of(List.of(reactorEntry)));

    final List<TypeIndexEntry> results = index.search("Application", 10);
    assertThat(results).hasSize(2);
    assertThat(results.get(0).packageName()).isEqualTo("com.reactor");
    assertThat(results.get(1).packageName()).isEqualTo("com.dep");
  }
}
