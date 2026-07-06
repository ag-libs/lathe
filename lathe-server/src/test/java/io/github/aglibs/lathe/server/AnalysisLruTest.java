package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnalysisLruTest {

  @Test
  void touch_defaultCap_allowsHundredEntries() {
    final var lru = new AnalysisLru();

    for (int i = 0; i < 100; i++) {
      assertThat(lru.touch("file:///%d.java".formatted(i))).isEmpty();
    }

    assertThat(lru.touch("file:///100.java")).contains("file:///0.java");
  }

  @Test
  void touch_belowCap_evictsNothing() {
    final var lru = new AnalysisLru(2);

    assertThat(lru.touch("file:///A.java")).isEmpty();
    assertThat(lru.touch("file:///B.java")).isEmpty();

    assertThat(lru.size()).isEqualTo(2);
  }

  @Test
  void touch_beyondCap_returnsEldest() {
    final var lru = new AnalysisLru(2);
    lru.touch("file:///A.java");
    lru.touch("file:///B.java");

    assertThat(lru.touch("file:///C.java")).contains("file:///A.java");

    assertThat(lru.size()).isEqualTo(2);
  }

  @Test
  void touch_existingUri_promotesEntry() {
    final var lru = new AnalysisLru(2);
    lru.touch("file:///A.java");
    lru.touch("file:///B.java");
    lru.touch("file:///A.java");

    assertThat(lru.touch("file:///C.java")).contains("file:///B.java");
  }

  @Test
  void remove_trackedUri_untracksEntry() {
    final var lru = new AnalysisLru(2);
    lru.touch("file:///A.java");
    lru.remove("file:///A.java");

    assertThat(lru.touch("file:///B.java")).isEmpty();
    assertThat(lru.touch("file:///C.java")).isEmpty();
  }
}
