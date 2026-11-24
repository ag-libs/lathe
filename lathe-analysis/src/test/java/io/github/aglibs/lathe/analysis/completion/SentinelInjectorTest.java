package io.github.aglibs.lathe.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// The receiver text before the dot at the cursor, which member access, qualified type and import
// completion read beside the parse tree.
class SentinelInjectorTest {

  @Test
  void receiverText_afterUnmatchedAngleBracket_startsThere() {
    assertThat(receiver("class T { java.util.List<java.util.Ma§> x; }")).isEqualTo("java.util");
    assertThat(
            receiver(
                "class T { Object m() { return java.util.Collections.<java.util.Ma§>emptyList(); } }"))
        .isEqualTo("java.util");
    assertThat(receiver("class T { boolean m(int a, String b) { return a<b.len§; } }"))
        .isEqualTo("b");
  }

  // Past a `>` a `<` may be its match, so the receiver keeps both.
  @Test
  void receiverText_holdingAngleBrackets_keepsThemWhole() {
    assertThat(receiver("class T { void m() { java.util.Collections.<String>emptyList().ite§ } }"))
        .isEqualTo("java.util.Collections.<String>emptyList()");
    assertThat(receiver("class T { void m(Object o) { ((java.util.List<String>) o).si§ } }"))
        .isEqualTo("((java.util.List<String>) o)");
  }

  // Past a quote a `<` may sit in a literal or a comment, so it does not end the receiver.
  @Test
  void receiverText_pastQuote_ignoresAngleBrackets() {
    assertThat(receiver("class T { int m() { return \"a<b\".len§; } }")).isEqualTo("\"a<b\"");
    assertThat(receiver("class T { int m() { return Character.valueOf('<').hash§; } }"))
        .isEqualTo("Character.valueOf('<')");
  }

  private static String receiver(final String source) {
    final int cursor = source.indexOf('§');
    return new SentinelInjector(source.replace("§", "")).inject(cursor).receiverText();
  }
}
