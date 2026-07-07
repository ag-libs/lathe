package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;

public record TransientSource(String uri, String content) {
  public TransientSource {
    ValidCheck.check().notNull(uri, "uri").notNull(content, "content").validate();
  }
}
