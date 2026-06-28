package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.CachedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.Position;

public record CompletionRequest(
    String uri,
    String content,
    Position pos,
    CompletionContext context,
    CachedFileAnalysis cached,
    WorkspaceTypeIndex typeIndex,
    List<String> moduleNames) {

  public CompletionRequest {
    ValidCheck.check()
        .notBlank(uri, "uri")
        .notNull(content, "content")
        .notNull(pos, "pos")
        .validate();
    moduleNames = moduleNames != null ? List.copyOf(moduleNames) : List.of();
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

  boolean noDiff() {
    return cached != null && content.equals(cached.content());
  }

  char charAfterCursor() {
    final int offset = cursorOffset();
    return offset < content.length() ? content.charAt(offset) : '\0';
  }
}
