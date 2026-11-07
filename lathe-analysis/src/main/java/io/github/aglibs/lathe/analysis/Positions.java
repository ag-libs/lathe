package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.Trees;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.tools.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

// Conversions between LSP positions and source offsets, ranges, and applying text edits.
public final class Positions {

  private Positions() {}

  // An LSP character is an offset within the line. javac's column numbers expand each tab to the
  // next multiple of 8, so a position after a tab is measured from the line's start instead.
  public static long toOffset(final CompilationUnitTree cu, final int line, final int character) {
    try {
      return cu.getLineMap().getStartPosition(line + 1) + character;
    } catch (final ArrayIndexOutOfBoundsException e) {
      return Diagnostic.NOPOS;
    }
  }

  public static Position offsetToPosition(final CompilationUnitTree cu, final long offset) {
    final var lineMap = cu.getLineMap();
    try {
      final long line = lineMap.getLineNumber(offset);
      return new Position((int) line - 1, (int) (offset - lineMap.getStartPosition(line)));
    } catch (final ArrayIndexOutOfBoundsException e) {
      // A synthesized node reports NOPOS (-1), which the line map rejects. Mirror toOffset's guard
      // and fall back to the origin so one positionless node can't fail a whole feature.
      return new Position(0, 0);
    }
  }

  // The range of a name that starts at start and fits on its line.
  public static Range nameRange(final Position start, final String name) {
    return new Range(start, new Position(start.getLine(), start.getCharacter() + name.length()));
  }

  public static Range range(final Trees trees, final CompilationUnitTree cu, final Tree node) {
    final var positions = trees.getSourcePositions();
    final long start = positions.getStartPosition(cu, node);
    final long end = positions.getEndPosition(cu, node);
    return new Range(offsetToPosition(cu, start), offsetToPosition(cu, end));
  }

  public static Position offsetToPosition(final String content, final long offset) {
    final int limit = (int) Math.min(offset, content.length());
    int line = 0;
    int col = 0;
    for (int i = 0; i < limit; i++) {
      if (content.charAt(i) == '\n') {
        line++;
        col = 0;
      } else {
        col++;
      }
    }
    return new Position(line, col);
  }

  // Rebuild the document from the original: for each edit (in document order) emit the untouched
  // gap before it plus its replacement, then the final untouched tail. Edits never overlap, so the
  // gaps are well-defined and offsets need no adjusting for earlier edits.
  public static String applyEdits(final String content, final List<? extends TextEdit> edits) {
    final List<TextEdit> ordered =
        edits.stream()
            .map(TextEdit.class::cast)
            .sorted(Comparator.comparingInt(e -> offset(content, e.getRange().getStart())))
            .toList();
    return IntStream.rangeClosed(0, ordered.size())
        .mapToObj(i -> segment(content, ordered, i))
        .collect(Collectors.joining());
  }

  private static String segment(
      final String content, final List<TextEdit> ordered, final int index) {
    final int from = index == 0 ? 0 : offset(content, ordered.get(index - 1).getRange().getEnd());
    if (index == ordered.size()) {
      return content.substring(from); // the untouched tail after the last edit
    }

    final TextEdit edit = ordered.get(index);
    final int to = offset(content, edit.getRange().getStart());
    return "%s%s".formatted(content.substring(from, to), edit.getNewText());
  }

  private static int offset(final String content, final Position position) {
    return toOffset(content, position.getLine(), position.getCharacter());
  }

  /** The offset of a 0-based {@code line}/{@code character} in raw text, before it is parsed. */
  public static int toOffset(final String content, final int line, final int character) {
    int offset = 0;
    int currentLine = 0;
    for (int i = 0; i < content.length() && currentLine < line; i++) {
      if (content.charAt(i) == '\n') {
        currentLine++;
        offset = i + 1;
      }
    }

    return offset + character;
  }
}
