package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.eclipse.lsp4j.DocumentHighlightKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

class ReferenceMatchTest {

  private static final Range RANGE = new Range(new Position(2, 4), new Position(2, 8));

  @Test
  void toHighlight_everyRole_mapsReadWriteDistinctlyAndRestToText() {
    final Map<ReferenceRole, DocumentHighlightKind> expected =
        Map.of(
            ReferenceRole.READ, DocumentHighlightKind.Read,
            ReferenceRole.WRITE, DocumentHighlightKind.Write,
            ReferenceRole.DECLARATION, DocumentHighlightKind.Text,
            ReferenceRole.IMPORT, DocumentHighlightKind.Text,
            ReferenceRole.INVOCATION, DocumentHighlightKind.Text,
            ReferenceRole.TYPE_USE, DocumentHighlightKind.Text);

    // Guard: a newly added role must be assigned a kind here, otherwise the switch is
    // non-exhaustive.
    assertThat(expected).containsOnlyKeys(ReferenceRole.values());
    expected.forEach(
        (role, kind) -> {
          final var highlight = new ReferenceMatch("file:///T.java", RANGE, role).toHighlight();
          assertThat(highlight.getKind()).isEqualTo(kind);
          assertThat(highlight.getRange()).isEqualTo(RANGE);
        });
  }
}
