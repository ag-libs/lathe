package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

class FormattingTest {

  private static final GjfFormatEngine ENGINE = FormatterFixtures.googleJavaFormat();
  private static final Path FILE = Path.of("Sample.java");

  // Formatting returns a minimal edit (only the changed region), so apply it to recover the result.
  private static String formattedText(final String source) throws Exception {
    final List<TextEdit> edits = JavaFormatter.format(ENGINE, source, FILE);
    assertThat(edits).hasSize(1);
    return apply(source, edits.getFirst());
  }

  // The whole line of source containing text, as a range request selects it.
  private static Range lineRange(final String source, final String text) {
    final int line = (int) source.lines().takeWhile(candidate -> !candidate.contains(text)).count();
    return new Range(new Position(line, 0), new Position(line + 1, 0));
  }

  private static String apply(final String source, final TextEdit edit) {
    final Range range = edit.getRange();
    final int start =
        SourceLocator.toOffset(source, range.getStart().getLine(), range.getStart().getCharacter());
    final int end =
        SourceLocator.toOffset(source, range.getEnd().getLine(), range.getEnd().getCharacter());
    return source.substring(0, start) + edit.getNewText() + source.substring(end);
  }

  @Test
  void format_alreadyFormattedAndImportsOptimized_returnsEmpty() throws Exception {
    assertThat(
            JavaFormatter.format(
                ENGINE,
                """
                import static java.util.Objects.requireNonNull;

                import java.util.List;

                final class Sample {
                  List<String> values(String value) {
                    requireNonNull(value);
                    return List.of(value);
                  }
                }
                """,
                FILE))
        .isEmpty();
  }

  @Test
  void format_unusedImport_removesImport() throws Exception {
    final var source =
        """
        package example;

        import java.util.List;

        final class Sample {
          String value() {
            return "ok";
          }
        }
        """;

    assertThat(formattedText(source))
        .isEqualTo(
            """
            package example;

            final class Sample {
              String value() {
                return "ok";
              }
            }
            """);
  }

  @Test
  void format_localizedChange_returnsMinimalEditNotWholeDocument() throws Exception {
    final var original =
        """
        final class Sample {
          int a = 1;
          int b = 2;
          int c = 3;
        }
        """;
    // Only line "int b" is misformatted; the edit must not span the whole buffer.
    final var unformatted = original.replace("  int b = 2;", "  int   b = 2;");

    final List<TextEdit> edits = JavaFormatter.format(ENGINE, unformatted, FILE);

    assertThat(edits).hasSize(1);
    final TextEdit edit = edits.getFirst();
    assertThat(edit.getRange().getStart().getLine()).isEqualTo(2); // the "int b" line, not line 0
    assertThat(edit.getRange().getEnd().getLine()).isEqualTo(2); // and nothing past it
    assertThat(apply(unformatted, edit)).isEqualTo(original);
  }

  @Test
  void format_reorderImportsOption_followsSpotlessDefault() throws Exception {
    final var unsorted =
        """
        import java.util.Map;
        import java.util.List;

        final class Sample {
          Map<String, List<String>> values;
        }
        """;
    final var reordering =
        FormatterFixtures.googleJavaFormat(Map.of(LatheFlags.FORMAT_REORDER_IMPORTS, "true"));

    assertThat(JavaFormatter.format(ENGINE, unsorted, FILE)).isEmpty();
    assertThat(apply(unsorted, JavaFormatter.format(reordering, unsorted, FILE).getFirst()))
        .isEqualTo(
            unsorted.replace(
                "import java.util.Map;\nimport java.util.List;",
                "import java.util.List;\nimport java.util.Map;"));
  }

  @Test
  void formatRanges_oneOfTwoMangledMethods_formatsOnlyThatRange() throws Exception {
    final var source =
        """
        import java.util.List;

        final class Sample {
          int a( ) {return   1;}

          int b( ) {return   2;}
        }
        """;
    final Range range = lineRange(source, "int a(");

    final List<TextEdit> edits = JavaFormatter.formatRanges(ENGINE, source, List.of(range));

    assertThat(edits).hasSize(1);
    assertThat(apply(source, edits.getFirst()))
        .isEqualTo(
            source.replace(
                "  int a( ) {return   1;}\n",
                """
                  int a() {
                    return 1;
                  }
                """));
  }

  @Test
  void format_palantirJavaFormat_followsSpotlessPalantirStepAndFormatsRanges() throws Exception {
    final GjfFormatEngine palantir = FormatterFixtures.palantirJavaFormat();
    final var source =
        """
        import java.util.Map;
        import java.util.List;
        import java.util.Set;

        final class Sample {
          Map<String, List<String>> values( ) {return   null;}

          int b( ) {return   2;}
        }
        """;
    final var expected =
        """
        import java.util.List;
        import java.util.Map;

        final class Sample {
            Map<String, List<String>> values() {
                return null;
            }

            int b() {
                return 2;
            }
        }
        """;
    // Spotless's palantir step always reorders imports and removes unused ones, then formats.
    final String formatted = apply(source, JavaFormatter.format(palantir, source, FILE).getFirst());
    final Range range = lineRange(source, "int b(");
    final List<TextEdit> ranged = JavaFormatter.formatRanges(palantir, source, List.of(range));

    assertThat(formatted).isEqualTo(expected);
    assertThat(JavaFormatter.format(palantir, expected, FILE)).isEmpty();
    assertThat(ranged)
        .singleElement()
        .satisfies(
            edit ->
                assertThat(edit.getRange().getStart().getLine())
                    .isEqualTo(range.getStart().getLine()));
  }

  @Test
  void format_syntaxError_throws() {
    // JavaFormatter no longer swallows; WorkspaceSession.format catches, logs, and notifies.
    assertThatThrownBy(() -> JavaFormatter.format(ENGINE, "class { broken", FILE))
        .isInstanceOf(Exception.class);
  }
}
