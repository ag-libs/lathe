package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;
import org.eclipse.lsp4j.Range;

/** A navigation target plus a source snippet and where it resolved — code, not a bare file:line. */
public record LatheLocation(String uri, Range range, Origin origin, String snippet) {

  /** Where a target resolved — the differentiator over a text search that cannot follow these. */
  public enum Origin {
    REACTOR,
    GENERATED,
    EXTERNAL
  }

  public LatheLocation {
    ValidCheck.check()
        .notNull(uri, "uri")
        .notNull(range, "range")
        .notNull(origin, "origin")
        .notNull(snippet, "snippet")
        .validate();
  }
}
