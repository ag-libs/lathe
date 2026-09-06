package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;
import org.eclipse.lsp4j.Position;

// The server computes path/content/caret but never writes the file — the client owns the IO.
record CreateTypeResult(String path, String content, Position caret) {

  CreateTypeResult {
    ValidCheck.check()
        .notBlank(path, "path")
        .notBlank(content, "content")
        .notNull(caret, "caret")
        .validate();
  }
}
