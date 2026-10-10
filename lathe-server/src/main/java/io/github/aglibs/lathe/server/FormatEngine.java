package io.github.aglibs.lathe.server;

import java.nio.file.Path;
import java.util.List;

// A Java source-to-source formatter: the project's pinned formatter run in-process
// (GjfFormatEngine, EclipseFormatEngine), or an external command. `file` is the document's path,
// used by engines that format in place (FileCommandFormatEngine) or by file kind; the others ignore
// it. Throws its native failure type; JavaFormatter catches late.
sealed interface FormatEngine
    permits GjfFormatEngine,
        EclipseFormatEngine,
        ExternalCommandFormatEngine,
        FileCommandFormatEngine {

  // Spotless re-applies a step until its output stops changing (its PaddedCell, at most 10 passes),
  // so spotless:apply writes the converged text: removing an unused import after formatting leaves
  // a blank line that only the next pass collapses.
  int MAX_PASSES = 10;

  String format(String source, Path file) throws Exception;

  // Whole-file engines (external commands) cannot format a range; in-process ones can.
  default boolean formatsRanges() {
    return false;
  }

  // Formats only the given spans of source, leaving everything else (imports included) alone.
  default String formatRanges(final String source, final List<TextRange> ranges) throws Exception {
    throw new UnsupportedOperationException(
        "%s formats whole files only".formatted(getClass().getSimpleName()));
  }

  // Applies pass until its output stops changing, as spotless:apply does.
  static String converge(final String source, final Pass pass) throws Exception {
    String current = source;
    for (int i = 0; i < MAX_PASSES; i++) {
      final String next = pass.apply(current);
      if (next.equals(current)) {
        return current;
      }

      current = next;
    }

    return current;
  }

  @FunctionalInterface
  interface Pass {
    String apply(String source) throws Exception;
  }
}
