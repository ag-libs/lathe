package io.github.aglibs.lathe.server;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The open documents: loop state, shared by the loop components, so every access checks the loop.
final class DocumentRegistry {

  private final ServerEventLoop eventLoop;
  private final Map<String, OpenDocument> documents = new HashMap<>();
  private long nextGeneration;

  DocumentRegistry(final ServerEventLoop eventLoop) {
    this.eventLoop = eventLoop;
  }

  OpenDocument put(final String uri, final String content, final int version) {
    eventLoop.checkOnLoop();
    final var doc = new OpenDocument(uri, content, version, ++nextGeneration);
    documents.put(uri, doc);
    return doc;
  }

  OpenDocument get(final String uri) {
    eventLoop.checkOnLoop();
    return documents.get(uri);
  }

  void remove(final String uri) {
    eventLoop.checkOnLoop();
    documents.remove(uri);
  }

  Set<String> uris() {
    eventLoop.checkOnLoop();
    return Set.copyOf(documents.keySet());
  }

  List<OpenDocument> all() {
    eventLoop.checkOnLoop();
    return List.copyOf(documents.values());
  }

  int count() {
    eventLoop.checkOnLoop();
    return documents.size();
  }

  boolean isStale(final OpenDocument snapshot, final long generation) {
    eventLoop.checkOnLoop();
    final OpenDocument current = documents.get(snapshot.uri());
    return current == null || current.generation() != generation;
  }
}
