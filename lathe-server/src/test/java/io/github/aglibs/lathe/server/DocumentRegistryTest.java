package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DocumentRegistryTest {

  private final TestLoop loop = new TestLoop();
  private final DocumentRegistry registry = new DocumentRegistry(loop.eventLoop);

  @AfterEach
  void closeLoop() {
    loop.close();
  }

  @Test
  void put_newUri_storesDocument() throws Exception {
    final var doc = loop.call(() -> registry.put("file:///A.java", "class A {}", 1));
    assertThat(doc.uri()).isEqualTo("file:///A.java");
    assertThat(doc.content()).isEqualTo("class A {}");
    assertThat(doc.version()).isEqualTo(1);
    assertThat(doc.generation()).isGreaterThan(0);
  }

  @Test
  void put_sameUri_updatesContentAndAdvancesGeneration() throws Exception {
    final var first = loop.call(() -> registry.put("file:///A.java", "v1", 1));
    final var second = loop.call(() -> registry.put("file:///A.java", "v2", 2));
    assertThat(second.content()).isEqualTo("v2");
    assertThat(second.generation()).isGreaterThan(first.generation());
  }

  @Test
  void get_absentUri_returnsNull() throws Exception {
    assertThat(loop.call(() -> registry.get("file:///Missing.java"))).isNull();
  }

  @Test
  void remove_existingUri_removesDocument() throws Exception {
    loop.run(() -> registry.put("file:///A.java", "class A {}", 1));
    loop.run(() -> registry.remove("file:///A.java"));
    assertThat(loop.call(() -> registry.get("file:///A.java"))).isNull();
  }

  // Copies, so nothing holds a live view of loop state off the loop.
  @Test
  void urisAndAll_currentDocuments_returnedAsCopies() throws Exception {
    loop.run(() -> registry.put("file:///A.java", "a", 1));
    loop.run(() -> registry.put("file:///B.java", "b", 1));
    final var uris = loop.call(registry::uris);
    final var all = loop.call(registry::all);
    loop.run(() -> registry.remove("file:///A.java"));

    assertThat(uris).containsExactlyInAnyOrder("file:///A.java", "file:///B.java");
    assertThat(all).hasSize(2);
  }

  @Test
  void isStale_currentOlderOrRemoved_onlyCurrentIsFresh() throws Exception {
    final var old = loop.call(() -> registry.put("file:///A.java", "v1", 1));
    final var current = loop.call(() -> registry.put("file:///A.java", "v2", 2));
    assertThat(loop.call(() -> registry.isStale(current, current.generation()))).isFalse();
    assertThat(loop.call(() -> registry.isStale(old, old.generation()))).isTrue();

    loop.run(() -> registry.remove("file:///A.java"));
    assertThat(loop.call(() -> registry.isStale(current, current.generation()))).isTrue();
  }

  @Test
  void put_offTheLoop_fails() {
    assertThatThrownBy(() -> registry.put("file:///A.java", "a", 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("loop state touched");
  }
}
