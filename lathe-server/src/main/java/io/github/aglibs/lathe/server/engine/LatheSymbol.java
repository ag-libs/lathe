package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;

/** A workspace symbol hit: its name, kind, declaring container, and location with a snippet. */
public record LatheSymbol(String name, String kind, String container, LatheLocation location) {

  public LatheSymbol {
    ValidCheck.check()
        .notNull(name, "name")
        .notNull(kind, "kind")
        .notNull(container, "container")
        .notNull(location, "location")
        .validate();
  }
}
