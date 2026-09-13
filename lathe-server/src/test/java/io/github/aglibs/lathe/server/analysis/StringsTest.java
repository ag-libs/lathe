package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// screamingSnake is covered indirectly via Extract Constant; this pins constantName, the
// camelCase-aware conversion used for static-final field name suggestions.
class StringsTest {

  @Test
  void constantName_singleWord_uppercasesWhole() {
    assertThat(Strings.constantName("logger")).isEqualTo("LOGGER");
  }

  @Test
  void constantName_camelCase_splitsAtHumps() {
    assertThat(Strings.constantName("connectionString")).isEqualTo("CONNECTION_STRING");
    assertThat(Strings.constantName("ioException")).isEqualTo("IO_EXCEPTION");
  }

  @Test
  void constantName_withDigits_keepsDigitBoundaries() {
    assertThat(Strings.constantName("http2Client")).isEqualTo("HTTP2_CLIENT");
  }

  @Test
  void constantName_empty_returnsEmpty() {
    assertThat(Strings.constantName("")).isEmpty();
  }
}
