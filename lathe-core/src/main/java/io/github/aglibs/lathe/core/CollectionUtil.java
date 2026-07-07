package io.github.aglibs.lathe.core;

import java.util.List;
import java.util.stream.IntStream;

public final class CollectionUtil {

  private CollectionUtil() {}

  /**
   * Splits {@code items} into consecutive sub-lists of at most {@code size}; the final chunk holds
   * the remainder. Returns views over the source list, so the source must outlive the chunks.
   */
  public static <T> List<List<T>> partition(final List<T> items, final int size) {
    return IntStream.range(0, (items.size() + size - 1) / size)
        .mapToObj(i -> items.subList(i * size, Math.min((i + 1) * size, items.size())))
        .toList();
  }
}
