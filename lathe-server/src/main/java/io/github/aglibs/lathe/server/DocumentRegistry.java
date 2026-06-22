package io.github.aglibs.lathe.server;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

final class DocumentRegistry {

  private final Map<String, OpenDocument> documents = new HashMap<>();
  private long nextGeneration;

  OpenDocument put(final String uri, final String content, final int version) {
    final var doc = new OpenDocument(uri, content, version, ++nextGeneration);
    documents.put(uri, doc);
    return doc;
  }

  OpenDocument get(final String uri) {
    return documents.get(uri);
  }

  void remove(final String uri) {
    documents.remove(uri);
  }

  Set<String> uris() {
    return documents.keySet();
  }

  Collection<OpenDocument> all() {
    return documents.values();
  }

  boolean isStale(final OpenDocument snapshot, final long generation) {
    final OpenDocument current = documents.get(snapshot.uri());
    return current == null || current.generation() != generation;
  }
}
