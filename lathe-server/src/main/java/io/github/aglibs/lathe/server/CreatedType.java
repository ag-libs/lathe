package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;
import org.eclipse.lsp4j.Position;

// A lathe.types.create answer: the file the editor opens, and where its caret goes, or that the
// file already existed and was left as it was (no caret).
record CreatedType(String uri, Position caret, boolean existed) {

  CreatedType {
    ValidCheck.check()
        .notBlank(uri, "uri")
        .assertTrue(existed == (caret == null), "caret")
        .validate();
  }
}
