package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;

/** How many edits a rename applied to one file, and where that file lives. */
public record LatheFileEdit(String uri, String path, LatheLocation.Origin origin, int editCount) {

  public LatheFileEdit {
    ValidCheck.check()
        .notNull(uri, "uri")
        .notNull(path, "path")
        .notNull(origin, "origin")
        .validate();
  }
}
