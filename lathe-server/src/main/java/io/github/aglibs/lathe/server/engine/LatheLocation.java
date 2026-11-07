package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;

// A navigation target with its source line and where it resolved: code, not a bare file:line. The
// path is workspace-relative for a reactor or generated file, the URI for a dependency or the JDK;
// line and column, 0-based, are where the target starts.
public record LatheLocation(
    String uri, String path, int line, int column, Origin origin, String snippet) {

  /** Where a target resolved — the differentiator over a text search that cannot follow these. */
  public enum Origin {
    REACTOR,
    GENERATED,
    EXTERNAL
  }

  public LatheLocation {
    ValidCheck.check()
        .notNull(uri, "uri")
        .notNull(path, "path")
        .isNonNegative(line, "line")
        .isNonNegative(column, "column")
        .notNull(origin, "origin")
        .notNull(snippet, "snippet")
        .validate();
  }
}
