package io.github.aglibs.lathe.format;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// The fork must format exactly like google-java-format through the call lathe-server makes
// (formatSourceAndFixImports, both styles), and fail the same way on input it cannot format.
final class FormatterDifferentialTest {

  private static final List<String> STYLES = List.of("GOOGLE", "AOSP");

  @GoldenFixtures
  void format_goldenFixture_matchesGoogleJavaFormat(
      final String name, final String input, final String expected) {
    assertMatchesGoogleJavaFormat(input);
  }

  @GoldenFixtures
  void format_truncatedGoldenFixture_failsLikeGoogleJavaFormat(
      final String name, final String input, final String expected) {
    truncations(input, 4).forEach(FormatterDifferentialTest::assertMatchesGoogleJavaFormat);
  }

  @Snippets
  void format_targetedSnippet_matchesGoogleJavaFormat(final String name, final String source) {
    // A snippet google-java-format cannot format would make both fail alike and test nothing.
    STYLES.forEach(
        style ->
            assertThatCode(() -> FormatHarness.googleJavaFormatFixingImports(style).format(source))
                .doesNotThrowAnyException());

    assertMatchesGoogleJavaFormat(source);
  }

  // google-java-format rejects whitespace written as a Unicode escape; the fork must reject it at
  // the same token, after lexing the escaped string quotes and line break before it like javac.
  @ParameterizedTest
  @ValueSource(
      strings =
          "class Esc { String s = \\u0022quoted\\u0022 + '\\u0041'; int\\u0020c = 3; // ends\\u000a"
              + " int d = 4; }")
  void format_unicodeEscapedSource_failsLikeGoogleJavaFormat(final String source) {
    assertMatchesGoogleJavaFormat(source);
  }

  @CorpusFiles
  void format_corpusFile_matchesGoogleJavaFormat(final Path file) throws Exception {
    final String source = Files.readString(file);

    assertMatchesGoogleJavaFormat(source);
    truncations(source, 2).forEach(FormatterDifferentialTest::assertMatchesGoogleJavaFormat);
  }

  private static void assertMatchesGoogleJavaFormat(final String source) {
    STYLES.forEach(
        style ->
            FormatHarness.assertSameOutcome(
                FormatHarness.googleJavaFormatFixingImports(style),
                FormatHarness.latheFormatFixingImports(style),
                source));
  }

  // Cuts the source at evenly spaced offsets, giving the half-typed files an editor formats.
  private static List<String> truncations(final String source, final int parts) {
    return IntStream.range(1, parts)
        .mapToObj(part -> source.substring(0, source.length() * part / parts))
        .toList();
  }
}
