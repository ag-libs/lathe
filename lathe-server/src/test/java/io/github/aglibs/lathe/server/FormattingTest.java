package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormattingTest {

  private static final GjfFormatEngine ENGINE = FormatterFixtures.googleJavaFormat();
  private static final Path FILE = Path.of("Sample.java");
  // palantir-java-format 2.98.0 (the fixture) predates JDK 27's javac.
  private static final int PALANTIR_UNSUPPORTED_JDK = 27;

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
    assumeTrue(
        Runtime.version().feature() < PALANTIR_UNSUPPORTED_JDK,
        "the fixture's palantir does not support this JDK");
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

  // The profile's settings drive the result (3-space indent here), from either an Eclipse XML
  // export
  // or a .prefs file, as Spotless reads them; ranges touch only the requested line.
  @Test
  void format_eclipseJdtProfile_formatsDocumentAndRange(@TempDir final Path profiles)
      throws Exception {
    final Path xml = profiles.resolve("eclipse-formatter.xml");
    Files.writeString(
        xml,
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <profiles version="23">
          <profile kind="CodeFormatterProfile" name="project" version="23">
            <setting id="org.eclipse.jdt.core.formatter.tabulation.char" value="space"/>
            <setting id="org.eclipse.jdt.core.formatter.tabulation.size" value="3"/>
            <setting id="org.eclipse.jdt.core.formatter.indentation.size" value="3"/>
          </profile>
        </profiles>
        """);
    final Path prefs = profiles.resolve("org.eclipse.jdt.core.prefs");
    Files.writeString(
        prefs,
        """
        org.eclipse.jdt.core.formatter.tabulation.char=space
        org.eclipse.jdt.core.formatter.tabulation.size=3
        org.eclipse.jdt.core.formatter.indentation.size=3
        """);
    final var source =
        """
        package demo;

        final class Sample {
        int a( ) {return   1;}

        int b( ) {return   2;}
        }
        """;
    final var expected =
        """
        package demo;

        final class Sample {
           int a() {
              return 1;
           }

           int b() {
              return 2;
           }
        }
        """;
    final EclipseFormatEngine eclipse = FormatterFixtures.eclipseJdt(xml.toString());
    final Range range = lineRange(source, "int b(");
    final List<TextEdit> ranged = JavaFormatter.formatRanges(eclipse, source, List.of(range));

    assertThat(apply(source, JavaFormatter.format(eclipse, source, FILE).getFirst()))
        .isEqualTo(expected);
    assertThat(JavaFormatter.format(eclipse, expected, FILE)).isEmpty();
    assertThat(
            apply(
                source,
                JavaFormatter.format(FormatterFixtures.eclipseJdt(prefs.toString()), source, FILE)
                    .getFirst()))
        .isEqualTo(expected);
    assertThat(apply(source, ranged.getFirst()))
        .isEqualTo(
            source.replace(
                "int b( ) {return   2;}\n",
                """
                int b() {
                   return 2;
                }
                """));
  }

  // The fixture's palantir reads a javac field JDK 27 removed (removing an unused import is one
  // such path): the failure must name the JDK, not suggest a re-sync. Once a palantir release
  // supports JDK 27, this fails: bump the fixture and drop the test.
  @Test
  void format_palantirOnUnsupportedJdk_reportsUnsupportedJava() {
    assumeTrue(
        Runtime.version().feature() >= PALANTIR_UNSUPPORTED_JDK,
        "the fixture's palantir supports this JDK");

    assertThatThrownBy(
            () ->
                JavaFormatter.format(
                    FormatterFixtures.palantirJavaFormat(),
                    "import java.util.List;\nimport java.util.Set;\n\nclass A {\n  List<String> names;\n}\n",
                    FILE))
        .hasMessageContaining("does not support Java %d".formatted(Runtime.version().feature()));
  }

  // The startup warm-up snippet must format on every in-process engine: a warm-up failure is only
  // logged, so a broken snippet would silently leave the first save cold.
  @Test
  void warmUp_inProcessEngines_formatSnippetWithoutError() {
    assertThatCode(() -> FormatterFixtures.googleJavaFormat().warmUp()).doesNotThrowAnyException();
    if (Runtime.version().feature() < PALANTIR_UNSUPPORTED_JDK) {
      assertThatCode(() -> FormatterFixtures.palantirJavaFormat().warmUp())
          .doesNotThrowAnyException();
    }

    assertThatCode(() -> FormatterFixtures.eclipseJdt("").warmUp()).doesNotThrowAnyException();
  }

  @Test
  void format_syntaxError_throws() {
    // JavaFormatter no longer swallows; WorkspaceSession.format catches, logs, and notifies.
    assertThatThrownBy(() -> JavaFormatter.format(ENGINE, "class { broken", FILE))
        .isInstanceOf(Exception.class);
  }
}
