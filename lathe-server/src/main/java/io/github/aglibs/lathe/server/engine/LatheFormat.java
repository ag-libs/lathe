package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;

// A formatted file and the lines of it the formatter rewrote, 0-based like every engine position.
public record LatheFormat(String path, int firstLine, int lastLine) {
  public LatheFormat {
    ValidCheck.check()
        .notNull(path, "path")
        .assertTrue(firstLine >= 0 && lastLine >= firstLine, "lines")
        .validate();
  }
}
