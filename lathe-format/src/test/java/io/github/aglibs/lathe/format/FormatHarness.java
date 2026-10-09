package io.github.aglibs.lathe.format;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.googlejavaformat.java.Formatter;
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

  static void assertFormats(final FormatSubject subject, final String input, final String expected)
      throws Exception {
    assertThat(subject.format(input)).isEqualTo(expected);
  }

  static void assertIdempotent(final FormatSubject subject, final String source) throws Exception {
    final String once = subject.format(source);

    assertThat(subject.format(once)).isEqualTo(once);
  }
}
