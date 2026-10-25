package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;

/** A formatted file and the 1-based lines of it the formatter rewrote. */
public record LatheFormat(String path, int firstLine, int lastLine) {
  public LatheFormat {
    ValidCheck.check()
        .notNull(path, "path")
        .assertTrue(firstLine >= 1 && lastLine >= firstLine, "lines")
        .validate();
  }
}
