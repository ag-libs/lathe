package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import org.eclipse.lsp4j.Position;

public record CompletionRequest(
    String uri,
    String source,
    Position position,
    int offset,
    String cachedSource,
    FileAnalysis cached) {

  public static CompletionRequest of(
      final String uri,
      final String source,
      final Position pos,
      final String cachedSource,
      final FileAnalysis cached) {
    return new CompletionRequest(uri, source, pos, cursorOffset(source, pos), cachedSource, cached);
  }

  private static int cursorOffset(final String source, final Position pos) {
    int offset = 0;
    int line = 0;
    for (int i = 0; i < source.length() && line < pos.getLine(); i++) {
      if (source.charAt(i) == '\n') {
        line++;
        offset = i + 1;
      }
    }

    return offset + pos.getCharacter();
  }
}
