package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskEditsTest {
  @TempDir private Path tmp;

  @Test
  void apply_uniformLineEndings_preservesTheFileSeparatorAndTheOriginalEdit() throws Exception {
    for (final String separator : List.of("\n", "\r\n")) {
      for (final String insertedSeparator : List.of("\n", "\r\n")) {
        final String content = "first%ssecond%s".formatted(separator, separator);
        final String replacement = "added%sline%s".formatted(insertedSeparator, insertedSeparator);
        final var edit =
            new TextEdit(new Range(new Position(1, 0), new Position(1, 0)), replacement);

        assertThat(apply(content, List.of(edit)))
            .isEqualTo(
                "first%sadded%sline%ssecond%s"
                    .formatted(separator, separator, separator, separator));
        assertThat(edit.getNewText()).isEqualTo(replacement);
      }
    }
  }

  @Test
  void apply_mixedLineEndings_matchesEachEditedLineWithoutRewritingTheOtherLines()
      throws Exception {
    final var crlf = new TextEdit(new Range(new Position(1, 0), new Position(1, 0)), "added\n");
    final var lf = new TextEdit(new Range(new Position(2, 0), new Position(2, 0)), "added\r\n");

    assertThat(apply("first\nsecond\r\nthird\nlast", List.of(lf, crlf)))
        .isEqualTo("first\nadded\r\nsecond\r\nadded\nthird\nlast");
  }

  @Test
  void apply_unterminatedLastLine_usesThePreviousLineSeparator() throws Exception {
    final var edit = new TextEdit(new Range(new Position(1, 4), new Position(1, 4)), "\nadded\n");

    assertThat(apply("first\r\nlast", List.of(edit))).isEqualTo("first\r\nlast\r\nadded\r\n");
  }

  @Test
  void apply_noExistingLineBreak_usesLf() throws Exception {
    final var edit = new TextEdit(new Range(new Position(0, 0), new Position(0, 0)), "added\r\n");

    assertThat(apply("", List.of(edit))).isEqualTo("added\n");
    assertThat(apply("last", List.of(edit))).isEqualTo("added\nlast");
  }

  @Test
  void apply_identifierReplacement_preservesMixedLineEndingsExactly() throws Exception {
    final var edit = new TextEdit(new Range(new Position(1, 0), new Position(1, 3)), "newName");

    assertThat(apply("first\r\nold\nlast", List.of(edit))).isEqualTo("first\r\nnewName\nlast");
  }

  private String apply(final String content, final List<TextEdit> edits) throws IOException {
    final Path source = tmp.resolve("Sample.java");
    Files.writeString(source, content, StandardCharsets.UTF_8);
    final Path lathe = Files.createDirectories(tmp.resolve(LatheLayout.LATHE_DIR));
    final var disk = new DiskEdits(lathe, written -> {});
    disk.apply(Map.of(source.toUri().toString(), edits), Map.of(), Set.of(source));
    return Files.readString(source, StandardCharsets.UTF_8);
  }
}
