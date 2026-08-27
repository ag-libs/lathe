package io.github.aglibs.lathe.server.debug;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

/**
 * Covers the LSP→DAP completion-item mapping in isolation (no live JDI frame): the snippet occupies
 * the cursor line from column 0, so a replacement range on that line becomes a snippet-relative
 * {@code start}/{@code number}, and the identifier-prefix fallback applies when the engine attaches
 * no edit.
 */
final class LatheCompletionsMappingTest {

  private static CompletionItem lspItem(final String label, final CompletionItemKind kind) {
    final var item = new CompletionItem();
    item.setLabel(label);
    item.setKind(kind);
    return item;
  }

  private static TextEdit edit(final int line, final int startChar, final int endChar) {
    return new TextEdit(
        new Range(new Position(line, startChar), new Position(line, endChar)), "toUpperCase");
  }

  @Test
  void toDapItem_editOnCursorLine_usesEditRange() {
    final var item = lspItem("toUpperCase", CompletionItemKind.Method);
    item.setInsertText("toUpperCase");
    item.setSortText("0007");
    item.setTextEdit(Either.forLeft(edit(4, 6, 9)));

    final var dap = LatheCompletionsProvider.toDapItem(item, 4, 99, 99);

    assertThat(dap.label).isEqualTo("toUpperCase");
    assertThat(dap.text).isEqualTo("toUpperCase");
    assertThat(dap.type).isEqualTo("method");
    assertThat(dap.sortText).isEqualTo("0007");
    assertThat(dap.start).isEqualTo(6);
    assertThat(dap.number).isEqualTo(3);
  }

  @Test
  void toDapItem_editOnOtherLine_usesFallbackRange() {
    final var item = lspItem("value", CompletionItemKind.Field);
    item.setTextEdit(Either.forLeft(edit(9, 0, 5)));

    final var dap = LatheCompletionsProvider.toDapItem(item, 4, 2, 3);

    assertThat(dap.start).isEqualTo(2);
    assertThat(dap.number).isEqualTo(3);
  }

  @Test
  void toDapItem_noEdit_usesFallbackRangeAndLabelAsText() {
    final var item = lspItem("args", CompletionItemKind.Variable);

    final var dap = LatheCompletionsProvider.toDapItem(item, 4, 1, 4);

    assertThat(dap.start).isEqualTo(1);
    assertThat(dap.number).isEqualTo(4);
    assertThat(dap.text).isEqualTo("args");
    assertThat(dap.type).isEqualTo("variable");
  }

  @Test
  void toDapItem_missingInsertText_fallsBackToEditNewText() {
    final var item = lspItem("greet", CompletionItemKind.Method);
    item.setTextEdit(
        Either.forLeft(new TextEdit(new Range(new Position(4, 0), new Position(4, 2)), "greet")));

    final var dap = LatheCompletionsProvider.toDapItem(item, 4, 0, 2);

    assertThat(dap.text).isEqualTo("greet");
  }

  @Test
  void toDapItem_kind_mapsToDapType() {
    assertThat(
            LatheCompletionsProvider.toDapItem(lspItem("a", CompletionItemKind.Class), 0, 0, 0)
                .type)
        .isEqualTo("class");
    assertThat(
            LatheCompletionsProvider.toDapItem(lspItem("a", CompletionItemKind.Keyword), 0, 0, 0)
                .type)
        .isEqualTo("keyword");
    assertThat(
            LatheCompletionsProvider.toDapItem(
                    lspItem("a", CompletionItemKind.Constructor), 0, 0, 0)
                .type)
        .isEqualTo("constructor");
  }

  @Test
  void toDapItem_nullKind_mapsToValue() {
    final var dap = LatheCompletionsProvider.toDapItem(lspItem("a", null), 0, 0, 0);

    assertThat(dap.type).isEqualTo("value");
  }
}
