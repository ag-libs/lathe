package io.github.aglibs.lathe.server.analysis.completion;

import org.eclipse.lsp4j.Position;

final class CursorFixture {
  static final char MARKER = '§';

  record Cursor(String content, int offset) {
    int lspLine() {
      return (int) content.substring(0, offset).chars().filter(ch -> ch == '\n').count();
    }

    int lspChar() {
      return offset - (content.lastIndexOf('\n', offset - 1) + 1);
    }
  }

  private CursorFixture() {}

  static Cursor cursor(final String source) {
    final int offset = source.indexOf(MARKER);
    if (offset < 0) {
      throw new IllegalArgumentException("source must contain the § marker");
    }

    return new Cursor(source.replace(String.valueOf(MARKER), ""), offset);
  }

  static int offset(final String content, final Position position) {
    final String[] lines = content.split("\\n", -1);
    int offset = 0;
    for (int line = 0; line < position.getLine(); line++) {
      offset += lines[line].length() + 1; // "\n"
    }

    return offset + position.getCharacter();
  }
}
