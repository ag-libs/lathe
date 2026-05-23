package io.github.aglibs.lathe.server.analysis.completion;

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
}
