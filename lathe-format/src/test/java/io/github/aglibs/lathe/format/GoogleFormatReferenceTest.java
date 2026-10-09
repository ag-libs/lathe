package io.github.aglibs.lathe.format;

import org.junit.jupiter.api.Test;

// Proves GJF runs under this module's surefire flags and that the harness core drives it.
final class GoogleFormatReferenceTest {

  private final FormatSubject gjf = FormatHarness.googleJavaFormat();

  @Test
  void format_messyInput_producesGoogleStyle() throws Exception {
    FormatHarness.assertFormats(gjf, "class  A{int x ;}", "class A {\n  int x;\n}\n");
  }

  @Test
  void format_alreadyFormatted_isIdempotent() throws Exception {
    FormatHarness.assertIdempotent(gjf, "class A {\n  int x;\n}\n");
  }
}
