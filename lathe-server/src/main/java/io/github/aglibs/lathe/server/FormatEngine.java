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

  // Representative code formatted once at startup: loading a pinned formatter and running its code
  // the first time costs hundreds of milliseconds, which would otherwise land on the first
  // format-on-save.
  String WARM_UP_SOURCE =
      """
      package lathe.warmup;

      import java.util.List;
      import java.util.Map;
      import java.util.function.Function;

      /** A representative class, formatted once so the first real format runs warm. */
      public final class WarmUp {

        private static final Map<String, Integer> SIZES = Map.of("a", 1, "b", 2);

        record Point(int x, int y) {}

        sealed interface Shape permits Circle, Square {}

        record Circle(double radius) implements Shape {}

        record Square(double side) implements Shape {}

        @Deprecated
        static double area(final Shape shape) {
          return switch (shape) {
            case Circle c -> Math.PI * c.radius() * c.radius();
            case Square s -> s.side() * s.side();
          };
        }

        static List<String> names(final List<Point> points, final Function<Point, String> label) {
          return points.stream().filter(p -> p.x() > 0).map(label).sorted().toList();
        }

        String describe(final Object value) {
          if (value instanceof String text && !text.isEmpty()) {
            return "text " + text + " of " + SIZES.getOrDefault(text, 0);
          } else {
            return String.valueOf(value);
          }
        }
      }
      """;
  Path WARM_UP_FILE = Path.of("WarmUp.java");

  String format(String source, Path file) throws Exception;

  // Prepares an in-process formatter ahead of the first request; external commands have nothing to
  // prepare.
  default void warmUp() throws Exception {}

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
