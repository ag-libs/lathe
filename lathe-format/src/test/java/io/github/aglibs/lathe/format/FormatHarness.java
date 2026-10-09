package io.github.aglibs.lathe.format;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.JavaFormatterOptions;
import com.google.googlejavaformat.java.StringWrapper;

final class FormatHarness {

  private FormatHarness() {}

  // formatSource + StringWrapper.wrap: the exact pipeline GJF's own golden fixtures expect. Pinned
  // to the GJF version the formatter is forked from.
  static FormatSubject googleJavaFormat() {
    final var formatter = new Formatter();
    return source -> StringWrapper.wrap(formatter.formatSource(source), formatter);
  }

  // The forked formatter: same pipeline, our vendored copy — the subject under test.
  static FormatSubject latheFormat() {
    final var formatter = new io.github.aglibs.lathe.format.gjf.java.Formatter();
    return source ->
        io.github.aglibs.lathe.format.gjf.java.StringWrapper.wrap(
            formatter.formatSource(source), formatter);
  }

  // formatSourceAndFixImports in the given style ("GOOGLE" or "AOSP"): what lathe-server calls.
  static FormatSubject googleJavaFormatFixingImports(final String style) {
    final var options =
        JavaFormatterOptions.builder().style(JavaFormatterOptions.Style.valueOf(style)).build();
    return new Formatter(options)::formatSourceAndFixImports;
  }

  static FormatSubject latheFormatFixingImports(final String style) {
    final var options =
        io.github.aglibs.lathe.format.gjf.java.JavaFormatterOptions.builder()
            .style(io.github.aglibs.lathe.format.gjf.java.JavaFormatterOptions.Style.valueOf(style))
            .build();
    return new io.github.aglibs.lathe.format.gjf.java.Formatter(options)::formatSourceAndFixImports;
  }

  // Both must produce the same text, or fail the same way: unparsable input is part of the
  // comparison, since an editor formats half-typed code all the time.
  static void assertSameOutcome(
      final FormatSubject reference, final FormatSubject subject, final String source) {
    assertThat(outcome(subject, source)).isEqualTo(outcome(reference, source));
  }

  private static String outcome(final FormatSubject subject, final String source) {
    try {
      return subject.format(source);
    } catch (final Exception | Error e) {
      return "<%s: %s>".formatted(e.getClass().getSimpleName(), e.getMessage());
    }
  }

  static void assertFormats(final FormatSubject subject, final String input, final String expected)
      throws Exception {
    assertThat(subject.format(input)).isEqualTo(expected);
  }

  static void assertIdempotent(final FormatSubject subject, final String source) throws Exception {
    final String once = subject.format(source);

    assertThat(subject.format(once)).isEqualTo(once);
  }
}
