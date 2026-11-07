package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;
import org.eclipse.lsp4j.Position;

// A new type's file as rendered: where it goes, what it holds, and where the caret lands.
record NewTypeSource(String path, String content, Position caret) {

  NewTypeSource {
    ValidCheck.check()
        .notBlank(path, "path")
        .notBlank(content, "content")
        .notNull(caret, "caret")
        .validate();
  }
}
