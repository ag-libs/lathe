package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CollectionUtilTest {

  @Test
  void last_severalItems_returnsLastItem() {
    assertThat(CollectionUtil.last(List.of(1, 2, 3))).isEqualTo(3);
  }

  @Test
  void last_emptyList_throws() {
    assertThatThrownBy(() -> CollectionUtil.last(List.of()))
        .isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  void partition_sizeNotMultipleOfBatch_lastChunkHoldsRemainder() {
    final List<List<Integer>> chunks = CollectionUtil.partition(List.of(1, 2, 3, 4, 5), 2);

    assertThat(chunks).containsExactly(List.of(1, 2), List.of(3, 4), List.of(5));
  }

  @Test
  void partition_fewerItemsThanBatch_returnsSingleChunk() {
    final List<List<Integer>> chunks = CollectionUtil.partition(List.of(1, 2), 8);

    assertThat(chunks).containsExactly(List.of(1, 2));
  }

  @Test
  void partition_emptyItems_returnsNoChunks() {
    final List<List<Integer>> chunks = CollectionUtil.partition(List.of(), 8);

    assertThat(chunks).isEmpty();
  }
}
