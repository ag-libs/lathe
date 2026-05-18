package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.CachedAnalysis;
import io.github.aglibs.validcheck.ValidCheck;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.Position;

public record CompletionRequest(
    String uri, String content, Position pos, CompletionContext context, CachedAnalysis cached) {

  public CompletionRequest {
    ValidCheck.check()
        .notBlank(uri, "uri")
        .notNull(content, "content")
        .notNull(pos, "pos")
        .validate();
  }

  int cursorOffset() {
    int offset = 0;
    int line = 0;
    for (int i = 0; i < content.length() && line < pos.getLine(); i++) {
      if (content.charAt(i) == '\n') {
        line++;
        offset = i + 1;
      }
    }

    return offset + pos.getCharacter();
  }

  String sourceLine() {
    return content.lines().skip(pos.getLine()).findFirst().orElse("");
  }

  boolean noDiff() {
    return cached != null && content.equals(cached.content());
  }

  int firstDiff() {
    if (cached == null) {
      return 0;
    }

    final var c = cached.content();
    final int limit = Math.min(content.length(), c.length());
    for (int i = 0; i < limit; i++) {
      if (content.charAt(i) != c.charAt(i)) {
        return i;
      }
    }

    return limit;
  }
}
