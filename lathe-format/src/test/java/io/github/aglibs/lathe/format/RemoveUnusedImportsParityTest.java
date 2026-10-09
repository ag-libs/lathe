package io.github.aglibs.lathe.format;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.googlejavaformat.java.RemoveUnusedImports;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

// The fork reads javadoc references from their public signature text instead of javac's internal
// reference trees; it must remove exactly the imports google-java-format removes.
final class RemoveUnusedImportsParityTest {

  private static final FormatSubject GJF = RemoveUnusedImports::removeUnusedImports;
  private static final FormatSubject FORK =
      io.github.aglibs.lathe.format.gjf.java.RemoveUnusedImports::removeUnusedImports;

  @ParameterizedTest(name = "{0}")
  @MethodSource("io.github.aglibs.lathe.format.CorpusProvider#goldenCases")
  void removeUnusedImports_goldenFixture_matchesGoogleJavaFormat(
      final String name, final String input, final String expected) {
    assertMatchesGoogleJavaFormat(input);
    assertMatchesGoogleJavaFormat(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "import a.List;\nimport b.Map;\nimport c.Entry;\nimport d.Unused;\n"
            + "/** {@link List#add(Map.Entry, int[])} */ class A {}",
        "import java.util.List;\nimport java.util.Map;\n"
            + "/** {@link java.base/java.util.List} and {@linkplain Map the map} */ class A {}",
        "import java.io.IOException;\nimport x.Foo;\n"
            + "class A {\n  /**\n   * @throws IOException never\n   * @see Foo.Bar#baz(String...)\n   */\n"
            + "  void m() {}\n}",
        "import a.K;\nimport b.V;\nimport c.Gone;\n"
            + "/** {@link #put(java.util.Map<K, V>, ? extends Gone.Inner)} */ class A {}",
        "import a.Used;\nimport b.Gone;\nclass A { Used u; /** not a {@code Gone} reference */ int x; }",
      })
  void removeUnusedImports_javadocReferences_matchGoogleJavaFormat(final String source) {
    assertMatchesGoogleJavaFormat(source);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("io.github.aglibs.lathe.format.CorpusProvider#corpusFiles")
  @EnabledIfSystemProperty(named = CorpusProvider.CORPUS_PROPERTY, matches = ".+")
  void removeUnusedImports_corpusFile_matchesGoogleJavaFormat(final Path file) throws Exception {
    assertMatchesGoogleJavaFormat(Files.readString(file));
  }

  private static void assertMatchesGoogleJavaFormat(final String source) {
    assertThat(outcome(FORK, source)).isEqualTo(outcome(GJF, source));
  }

  // Both implementations reject unparsable input; comparing the failure by type keeps those cases
  // in the comparison instead of aborting it.
  private static String outcome(final FormatSubject subject, final String source) {
    try {
      return subject.format(source);
    } catch (final Exception e) {
      return "<%s>".formatted(e.getClass().getSimpleName());
    }
  }
}
