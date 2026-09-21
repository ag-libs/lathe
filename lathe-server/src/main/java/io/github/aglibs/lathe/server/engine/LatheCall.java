package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;

/**
 * One end of a call-hierarchy edge: the caller/callee method and the call-site (or decl) location.
 */
public record LatheCall(String name, LatheLocation location) {

  public LatheCall {
    ValidCheck.check().notNull(name, "name").notNull(location, "location").validate();
  }
}
