package io.github.aglibs.lathe.format;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.format.JavaLexer.LexToken;
import java.util.List;
import org.junit.jupiter.api.Test;

final class JavaLexerTest {

  @Test
  void tokenize_validSource_coversTextContiguously() {
    final String source = "class A { /* c */ int x = 1; } // end\n";

    final List<LexToken> tokens = JavaLexer.tokenize(source).orElseThrow();

    assertThat(tokens.getFirst().start()).isZero();
    assertThat(tokens.getLast().end()).isEqualTo(source.length());
    for (int i = 1; i < tokens.size(); i++) {
      assertThat(tokens.get(i).start()).isEqualTo(tokens.get(i - 1).end());
    }
  }

  @Test
  void tokenize_unterminatedConstruct_returnsEmpty() {
    assertThat(JavaLexer.tokenize("/* closed */ x")).isPresent();
    assertThat(JavaLexer.tokenize("\"closed\" + 'c' + \"\"\"\n  block\"\"\"")).isPresent();

    assertThat(JavaLexer.tokenize("x /* open")).isEmpty();
    assertThat(JavaLexer.tokenize("\"open")).isEmpty();
    assertThat(JavaLexer.tokenize("\"broken\nline\"")).isEmpty();
    assertThat(JavaLexer.tokenize("'c")).isEmpty();
    assertThat(JavaLexer.tokenize("\"\"\"\n  open block")).isEmpty();
  }

  @Test
  void tokenize_unicodeEscapes_keepRawRanges() {
    assertThat(texts("\\u0061b c")).containsExactly("\\u0061b", " ", "c");
    assertThat(texts("\\uuu0061")).containsExactly("\\uuu0061");
    assertThat(texts("\\u002F\\u002F x\ny")).containsExactly("\\u002F\\u002F x", "\n", "y");
    // An even run of backslashes does not start an escape.
    assertThat(texts("\"\\\\u0041\"")).containsExactly("\"\\\\u0041\"");

    assertThat(JavaLexer.tokenize("\\u00G1")).isEmpty();
    assertThat(JavaLexer.tokenize("\\u00")).isEmpty();
  }

  @Test
  void tokenize_operatorsAndNumbers_cutLikeJavac() {
    assertThat(texts("a>>>=b")).containsExactly("a", ">>>=", "b");
    assertThat(texts("x->y::z")).containsExactly("x", "->", "y", "::", "z");
    assertThat(texts("i--->0")).containsExactly("i", "--", "->", "0");
    assertThat(texts("a<-b")).containsExactly("a", "<", "-", "b");
    assertThat(texts("f(String...a)")).containsExactly("f", "(", "String", "...", "a", ")");

    assertThat(texts("0x1.8p-3f")).containsExactly("0x1.8p-3f");
    assertThat(texts("1_000L+.5e+2d")).containsExactly("1_000L", "+", ".5e+2d");
    assertThat(texts("0b1010_1010")).containsExactly("0b1010_1010");
    assertThat(texts("1.f")).containsExactly("1.f");
  }

  @Test
  void lex_errorAfterValidTokens_keepsTokensBeforeIt() {
    final JavaLexer.Lexed complete = JavaLexer.lex("class A {}");
    final JavaLexer.Lexed broken = JavaLexer.lex("import a.B; class A { /* open");
    final JavaLexer.Lexed badEscape = JavaLexer.lex("class A { int ab\\u00G1; }");

    assertThat(complete.complete()).isTrue();
    assertThat(broken.complete()).isFalse();
    assertThat(broken.tokens().getLast().end()).isEqualTo("import a.B; class A { ".length());
    assertThat(badEscape.complete()).isFalse();
    assertThat(badEscape.tokens().getLast().end()).isEqualTo("class A { int ".length());
  }

  private static List<String> texts(final String source) {
    return JavaLexer.tokenize(source).orElseThrow().stream()
        .map(token -> source.substring(token.start(), token.end()))
        .toList();
  }
}
