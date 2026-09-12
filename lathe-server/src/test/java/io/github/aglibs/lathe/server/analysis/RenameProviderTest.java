package io.github.aglibs.lathe.server.analysis;

import static io.github.aglibs.lathe.server.analysis.SampleFixture.posOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.util.List;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

class RenameProviderTest {

  // --- name validation ---

  @Test
  void isValidName_identifier_isTrue() {
    assertThat(RenameProvider.isValidName("total")).isTrue();
  }

  @Test
  void isValidName_keywordOrIllegalOrNull_isFalse() {
    assertThat(RenameProvider.isValidName("class")).isFalse();
    assertThat(RenameProvider.isValidName("1total")).isFalse();
    assertThat(RenameProvider.isValidName("has space")).isFalse();
    assertThat(RenameProvider.isValidName("")).isFalse();
    assertThat(RenameProvider.isValidName(null)).isFalse();
  }

  // --- identifier range (prepareRename) ---

  @Test
  void identifierRange_cursorInsideIdentifier_returnsWholeToken() {
    final var range = RenameProvider.identifierRange("int foo = 1;", new Position(0, 5));

    assertThat(range).isEqualTo(new Range(new Position(0, 4), new Position(0, 7)));
  }

  @Test
  void identifierRange_cursorOnOperator_returnsNull() {
    // the `=` at column 8 is bounded by whitespace, so no identifier token surrounds the cursor
    assertThat(RenameProvider.identifierRange("int foo = 1;", new Position(0, 8))).isNull();
  }

  // --- edit assembly ---

  @Test
  void toWorkspaceEdit_groupsByUri_andReplacesEveryRangeWithNewName() {
    final var a = new Location("file:///A.java", range(0, 0, 0, 1));
    final var b = new Location("file:///A.java", range(1, 2, 1, 3));
    final var c = new Location("file:///B.java", range(0, 0, 0, 1));

    final var edit = RenameProvider.toWorkspaceEdit(List.of(a, b, c), "renamed");

    assertThat(edit.getChanges().get("file:///A.java")).hasSize(2);
    assertThat(edit.getChanges().get("file:///B.java")).hasSize(1);
    assertThat(edit.getChanges().values().stream().flatMap(List::stream))
        .allMatch(e -> e.getNewText().equals("renamed"));
  }

  // --- integration: rename edit through the reference pipeline ---

  @Test
  void rename_localVariable_editsDeclarationAndUsesButNotOtherScope() {
    final var source =
        """
        class Test {
            int first() {
                int x = 1;
                return x + x;
            }
            int second() {
                int x = 2;
                return x;
            }
        }
        """;
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
      final var target = session.resolveTarget(requestAt(source, posOf(source, "int x = 1", "x")));
      assertThat(target.isLocalScope()).isTrue();

      final List<Location> occurrences =
          session.searchReferences(TempSourceCompiler.TEST_URI, source, 1, target, true).stream()
              .map(match -> new Location(match.uri(), match.range()))
              .toList();
      final var edits =
          RenameProvider.toWorkspaceEdit(occurrences, "y")
              .getChanges()
              .get(TempSourceCompiler.TEST_URI);

      // decl + two uses in first(); nothing from second()
      assertThat(edits).hasSize(3).allMatch(e -> e.getNewText().equals("y"));
      assertThat(edits)
          .noneMatch(e -> e.getRange().getStart().equals(posOf(source, "int x = 2", "x")));
    }
  }

  @Test
  void resolveTarget_onMember_isNotLocalScope() {
    final var source = "class Test { int field; }";
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
      final var request = requestAt(source, posOf(source, "int field", "field"));

      assertThat(session.resolveTarget(request).isLocalScope()).isFalse();
    }
  }

  private static Range range(final int sl, final int sc, final int el, final int ec) {
    return new Range(new Position(sl, sc), new Position(el, ec));
  }

  private static SourceFeatureRequest requestAt(final String source, final Position pos) {
    return new SourceFeatureRequest(
        TempSourceCompiler.TEST_URI, source, 1, pos, List.of(), WorkspaceManifest.empty());
  }
}
