package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocumentRegistryTest {

  private final DocumentRegistry registry = new DocumentRegistry();

  @Test
  void put_newUri_storesDocument() {
    final var doc = registry.put("file:///A.java", "class A {}", 1);
    assertThat(doc.uri()).isEqualTo("file:///A.java");
    assertThat(doc.content()).isEqualTo("class A {}");
    assertThat(doc.version()).isEqualTo(1);
    assertThat(doc.generation()).isGreaterThan(0);
  }

  @Test
  void put_sameUri_updatesContentAndAdvancesGeneration() {
    final var first = registry.put("file:///A.java", "v1", 1);
    final var second = registry.put("file:///A.java", "v2", 2);
    assertThat(second.content()).isEqualTo("v2");
    assertThat(second.generation()).isGreaterThan(first.generation());
  }

  @Test
  void get_absentUri_returnsNull() {
    assertThat(registry.get("file:///Missing.java")).isNull();
  }

  @Test
  void remove_existingUri_removesDocument() {
    registry.put("file:///A.java", "class A {}", 1);
    registry.remove("file:///A.java");
    assertThat(registry.get("file:///A.java")).isNull();
  }

  @Test
  void uris_reflectsCurrentDocuments() {
    registry.put("file:///A.java", "a", 1);
    registry.put("file:///B.java", "b", 1);
    assertThat(registry.uris()).containsExactlyInAnyOrder("file:///A.java", "file:///B.java");
  }

  @Test
  void all_returnsAllDocuments() {
    registry.put("file:///A.java", "a", 1);
    registry.put("file:///B.java", "b", 1);
    assertThat(registry.all()).hasSize(2);
  }

  @Test
  void isStale_currentGeneration_returnsFalse() {
    final var doc = registry.put("file:///A.java", "v1", 1);
    assertThat(registry.isStale(doc, doc.generation())).isFalse();
  }

  @Test
  void isStale_olderGeneration_returnsTrue() {
    final var old = registry.put("file:///A.java", "v1", 1);
    registry.put("file:///A.java", "v2", 2);
    assertThat(registry.isStale(old, old.generation())).isTrue();
  }

  @Test
  void isStale_removedUri_returnsTrue() {
    final var doc = registry.put("file:///A.java", "v1", 1);
    registry.remove("file:///A.java");
    assertThat(registry.isStale(doc, doc.generation())).isTrue();
  }
}
