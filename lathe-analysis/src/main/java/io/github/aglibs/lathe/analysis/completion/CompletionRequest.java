package io.github.aglibs.lathe.analysis.completion;

import io.github.aglibs.lathe.analysis.CachedFileAnalysis;
import io.github.aglibs.lathe.analysis.LearnedStaticImports;
import io.github.aglibs.lathe.analysis.Positions;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
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
    List<String> moduleNames,
    LearnedStaticImports staticImports) {

  public CompletionRequest {
    ValidCheck.check()
        .notBlank(uri, "uri")
        .notNull(content, "content")
        .notNull(pos, "pos")
        .validate();
    moduleNames = moduleNames != null ? List.copyOf(moduleNames) : List.of();
    staticImports = staticImports != null ? staticImports : LearnedStaticImports.empty();
  }

  int cursorOffset() {
    return Positions.toOffset(content, pos);
  }

  boolean noDiff() {
    return cached != null && content.equals(cached.content());
  }

  // The cached snapshot predates the last keystrokes, so its positions are compared with an offset
  // moved into its content. Inside the changed region no place corresponds; the region's end in the
  // snapshot is the edit's start for a pure insertion, the typing case, and caps the raw offset
  // when edits above and below the cursor span the region across it.
  int snapshotOffset(final int offset) {
    if (cached == null || noDiff()) {
      return offset;
    }

    final String snapshotContent = cached.content();
    final int shorter = Math.min(content.length(), snapshotContent.length());
    int prefix = 0;
    while (prefix < shorter && content.charAt(prefix) == snapshotContent.charAt(prefix)) {
      prefix++;
    }

    int suffix = 0;
    while (suffix < shorter - prefix
        && content.charAt(content.length() - 1 - suffix)
            == snapshotContent.charAt(snapshotContent.length() - 1 - suffix)) {
      suffix++;
    }

    if (offset <= prefix) {
      return offset;
    }

    if (offset >= content.length() - suffix) {
      return offset - (content.length() - snapshotContent.length());
    }

    return Math.min(offset, snapshotContent.length() - suffix);
  }

  char charAfterCursor() {
    final int offset = cursorOffset();
    return offset < content.length() ? content.charAt(offset) : '\0';
  }
}
