package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.googlejavaformat.java.JavaFormatterOptions.Style;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FormattingTest {

  private static final FormatEngine ENGINE = new GoogleFormatEngine(Style.GOOGLE);
  private static final Path FILE = Path.of("Sample.java");

  private static String formattedText(final String source) {
    final var edits = JavaFormatter.format(ENGINE, source, FILE);
    assertThat(edits).hasSize(1);
    return edits.getFirst().getNewText();
  }

  @Test
  void format_violation_reformatsToOriginal() {
    final var original =
        """
        import static java.util.Objects.requireNonNull;

        import java.util.List;

        final class Sample {
          List<String> values(String value) {
            requireNonNull(value);
            return List.of(value);
          }
        }
        """;
    final var unformatted = original.replace("List<String> values", "List<String>  values");

    assertThat(formattedText(unformatted)).isEqualTo(original);
  }

  @Test
  void format_alreadyFormattedAndImportsOptimized_returnsEmpty() {
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
  void format_unusedImport_removesImport() {
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
  void format_syntaxError_returnsEmpty() {
    assertThat(JavaFormatter.format(ENGINE, "class { broken", FILE)).isEmpty();
  }
}
