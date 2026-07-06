package io.github.aglibs.lathe.server;

import java.util.LinkedHashMap;
import java.util.Optional;

final class AnalysisLru {

  static final int DEFAULT_MAX_ENTRIES = 100;

  private final int maxEntries;
  private final LinkedHashMap<String, Boolean> entries;

  AnalysisLru() {
    this(DEFAULT_MAX_ENTRIES);
  }

  AnalysisLru(final int maxEntries) {
    this.maxEntries = maxEntries;
    this.entries = new LinkedHashMap<>(16, 0.75f, true);
  }

  Optional<String> touch(final String uri) {
    entries.put(uri, Boolean.TRUE);
    if (entries.size() <= maxEntries) {
      return Optional.empty();
    }

    final String eldest = entries.entrySet().iterator().next().getKey();
    entries.remove(eldest);
    return Optional.of(eldest);
  }

  void remove(final String uri) {
    entries.remove(uri);
  }

  int size() {
    return entries.size();
  }
}
