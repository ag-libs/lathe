package io.github.aglibs.lathe.format;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.format.gjf.java.JavacLexOracle;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// JavaLexer must cut the source exactly where javac's scanner did, since every formatter decision
// downstream is keyed to those boundaries.
final class TokenOracleTest {

  @GoldenFixtures
  void tokenize_goldenFixture_matchesJavac(
      final String name, final String input, final String expected) {
    assertMatchesJavac(input);
    assertMatchesJavac(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "int a = 1_000 + 0x1F + 0X1.8p-3 + 0b1010_1010 + 07 + 1L + 1e10 + 1.5e-3f + .5e+2d + 1.f;",
        "double d = 1. + 0x1p3 + 0xFFL + 9_9.0_1D;",
        "char c = '\\'' + '\"' + '\\\\' + '\\u0041' + 'x';",
        "String s = \"a\\\"b // not a comment /* nor this */\" + \"\\t\" + \"é😀\";",
        "String t = \"\"\"\n  line \\\"\"\" still\n  \\\n  end\"\"\";",
        "a/*x*//*y*/b // trailing",
        "/** doc */ @interface A { int v() default 1; }",
        "Map<List<String>, ? extends Set<int[]>> m = x -> y::z; a >>>= 2; b >>= 1; c ... d;",
        "non-sealed class $X_é { int \\u0061b = 1; }",
        "\\u002F\\u002F escaped line comment\nint x;",
        "String u = \"\\\\u0041\";",
        "class A {}\r\n\f\tclass B {}\r",
        "// comment at end of file without newline",
        "/// markdown\n  /// doc\n  //// rule\nclass A {}",
        "String q = \\u0022quoted\\u0022; // ends here\\u000a int\\u0020c = 3;",
        "String e = \"tab\\u005ct\" + '\\u005c\\u005c';",
      })
  void tokenize_trickySource_matchesJavac(final String source) {
    assertMatchesJavac(source);
  }

  @Snippets
  void tokenize_targetedSnippet_matchesJavac(final String name, final String source) {
    assertMatchesJavac(source);
  }

  @CorpusFiles
  void tokenize_corpusFile_matchesJavac(final Path file) throws Exception {
    assertMatchesJavac(Files.readString(file));
  }

  private static void assertMatchesJavac(final String source) {
    assertThat(JavaLexer.tokenize(source)).isEqualTo(JavacLexOracle.tokenize(source));
  }
}
