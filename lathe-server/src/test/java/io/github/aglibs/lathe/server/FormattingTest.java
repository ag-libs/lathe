package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FormattingTest {

  private static String formattedText(final String source) {
    final var edits = JavaFormatter.format(source);
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
                """
                import static java.util.Objects.requireNonNull;

                import java.util.List;

                final class Sample {
                  List<String> values(String value) {
                    requireNonNull(value);
                    return List.of(value);
                  }
                }
                """))
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
    assertThat(JavaFormatter.format("class { broken")).isEmpty();
  }
}
