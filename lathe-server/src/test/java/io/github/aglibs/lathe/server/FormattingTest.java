package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.format.gjf.java.JavaFormatterOptions.Style;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

class FormattingTest {

  private static final FormatEngine ENGINE = new GoogleFormatEngine(Style.GOOGLE);
  private static final Path FILE = Path.of("Sample.java");

  // Formatting returns a minimal edit (only the changed region), so apply it to recover the result.
  private static String formattedText(final String source) throws Exception {
    final List<TextEdit> edits = JavaFormatter.format(ENGINE, source, FILE);
    assertThat(edits).hasSize(1);
    return apply(source, edits.getFirst());
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
  void format_syntaxError_throws() {
    // JavaFormatter no longer swallows; WorkspaceSession.format catches, logs, and notifies.
    assertThatThrownBy(() -> JavaFormatter.format(ENGINE, "class { broken", FILE))
        .isInstanceOf(Exception.class);
  }
}
