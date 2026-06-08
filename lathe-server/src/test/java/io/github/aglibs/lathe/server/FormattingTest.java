package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;
import java.io.IOException;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class FormattingTest {

  private static String sampleSource() throws IOException {
    try (final var in = FormattingTest.class.getResourceAsStream("/Sample.java")) {
      return new String(Objects.requireNonNull(in).readAllBytes());
    }
  }

  @Test
  void formatSource_violation_reformatsToOriginal() throws IOException, FormatterException {
    final var original = sampleSource();
    final var unformatted = original.replace("public String getName()", "public String  getName()");
    assertThat(new Formatter().formatSource(unformatted)).isEqualTo(original);
  }

  @Test
  void format_alreadyFormatted_returnsEmpty() throws IOException {
    final var original = sampleSource();
    assertThat(JavaFormatter.format(original)).isEmpty();
  }

  @Test
  void format_syntaxError_returnsEmpty() {
    assertThat(JavaFormatter.format("class { broken")).isEmpty();
  }
}
