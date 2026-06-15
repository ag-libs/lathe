package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;

record OpenDocument(String uri, String content, int version, long generation) {
  OpenDocument {
    ValidCheck.check().notNull(uri, "uri").notNull(content, "content").validate();
  }
}
