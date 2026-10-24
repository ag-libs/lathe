package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;

/** One declaration of a file's outline, depth 0 for a top-level type, located at its name. */
public record LatheOutlineItem(String kind, String name, int depth, LatheLocation location) {
  public LatheOutlineItem {
    ValidCheck.check()
        .notNull(kind, "kind")
        .notNull(name, "name")
        .notNull(location, "location")
        .assertTrue(depth >= 0, "depth")
        .validate();
  }
}
