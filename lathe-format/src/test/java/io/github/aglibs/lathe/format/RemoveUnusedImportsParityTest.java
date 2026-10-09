package io.github.aglibs.lathe.format;

import com.google.googlejavaformat.java.RemoveUnusedImports;
import java.nio.file.Files;
import java.nio.file.Path;

// The fork reads javadoc references from their public signature text instead of javac's internal
// reference trees; it must remove exactly the imports google-java-format removes.
final class RemoveUnusedImportsParityTest {

  private static final FormatSubject GJF = RemoveUnusedImports::removeUnusedImports;
  private static final FormatSubject FORK =
      io.github.aglibs.lathe.format.gjf.java.RemoveUnusedImports::removeUnusedImports;

  @GoldenFixtures
  void removeUnusedImports_goldenFixture_matchesGoogleJavaFormat(
      final String name, final String input, final String expected) {
    FormatHarness.assertSameOutcome(GJF, FORK, input);
    FormatHarness.assertSameOutcome(GJF, FORK, expected);
  }

  @Snippets
  void removeUnusedImports_targetedSnippet_matchesGoogleJavaFormat(
      final String name, final String source) {
    FormatHarness.assertSameOutcome(GJF, FORK, source);
  }

  @CorpusFiles
  void removeUnusedImports_corpusFile_matchesGoogleJavaFormat(final Path file) throws Exception {
    FormatHarness.assertSameOutcome(GJF, FORK, Files.readString(file));
  }
}
