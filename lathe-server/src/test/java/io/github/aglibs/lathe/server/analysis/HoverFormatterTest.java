package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HoverFormatterTest extends SampleFixture {

  // "DocHelper" in "var instance = new DocHelper()"
  private static final int DOC_HELPER_LINE = 142;
  private static final int DOC_HELPER_COL = 25;

  @Test
  void format_withOrigin_appendsSourceFooter() {
    final var element = elementAt(DOC_HELPER_LINE, DOC_HELPER_COL);
    final var result = HoverFormatter.format(element, null, null, "com.example:lib:1.0");
    assertThat(result).isPresent();
    assertThat(result.get()).endsWith("*source: com.example:lib:1.0*");
  }

  @Test
  void format_withoutOrigin_omitsSourceFooter() {
    final var element = elementAt(DOC_HELPER_LINE, DOC_HELPER_COL);
    final var result = HoverFormatter.format(element, null, null, null);
    assertThat(result).isPresent();
    assertThat(result.get()).doesNotContain("*source:");
  }
}
