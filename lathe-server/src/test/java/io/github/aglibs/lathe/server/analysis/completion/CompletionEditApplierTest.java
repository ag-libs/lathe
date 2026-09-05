package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

class CompletionEditApplierTest {

  @Test
  void absorbFollowingSpaceAfterKeyword_spaceFollowsToken_extendsEditOverSpace() {
    // "int§ x;" -- a space already separates the keyword from the next token, so the edit grows to
    // overwrite it and the inserted "int " keeps a single space instead of doubling it (CQ-0054).
    final var item = keywordItem("int ", 24, 27);

    CompletionEditApplier.absorbFollowingSpaceAfterKeyword(
        List.of(item), requestWith("class Test { void m() { int x; } }"), 27);

    assertThat(rangeEndCharacter(item)).isEqualTo(28);
  }

  @Test
  void absorbFollowingSpaceAfterKeyword_newlineFollowsToken_leavesEditUnchanged() {
    // Only a literal space is absorbed; a trailing newline is left in place so the space + newline
    // is harmless and the caret still lands after the keyword's own space.
    final var item = keywordItem("class ", 11, 11);

    CompletionEditApplier.absorbFollowingSpaceAfterKeyword(
        List.of(item), requestWith("class Test\n"), 11);

    assertThat(rangeEndCharacter(item)).isEqualTo(11);
  }

  @Test
  void absorbFollowingSpaceAfterKeyword_bareStandaloneKeyword_leavesEditUnchanged() {
    // A standalone keyword carries no trailing space, so it is never extended even when a space
    // follows -- "null ==" must not become "null==".
    final var item = keywordItem("null", 24, 28);

    CompletionEditApplier.absorbFollowingSpaceAfterKeyword(
        List.of(item), requestWith("class Test { void m() { null == x; } }"), 28);

    assertThat(rangeEndCharacter(item)).isEqualTo(28);
  }

  private static CompletionItem keywordItem(
      final String insertText, final int startChar, final int endChar) {
    final var item = new CompletionItem();
    item.setKind(CompletionItemKind.Keyword);
    item.setInsertText(insertText);
    final var range = new Range(new Position(0, startChar), new Position(0, endChar));
    item.setTextEdit(Either.forLeft(new TextEdit(range, insertText)));
    return item;
  }

  private static CompletionRequest requestWith(final String content) {
    return new CompletionRequest(
        "file:///Test.java", content, new Position(0, 0), null, null, null, null);
  }

  private static int rangeEndCharacter(final CompletionItem item) {
    return item.getTextEdit().getLeft().getRange().getEnd().getCharacter();
  }
}
