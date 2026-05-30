package io.github.aglibs.lathe.server.analysis.completion;

import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

final class CompletionItemPresenter {

  private CompletionItemPresenter() {}

  static CompletionItem present(final RankedCompletionCandidate ranked) {
    final var item = present(ranked.candidate());
    item.setSortText(ranked.sortText());
    return item;
  }

  static CompletionItem present(final CompletionCandidate candidate) {
    final var item = new CompletionItem();
    item.setLabel(candidate.label());
    item.setInsertText(candidate.insertText());
    item.setFilterText(candidate.name());
    item.setDetail(candidate.detail());
    item.setSortText(candidate.sortText());
    item.setKind(kindFor(candidate.kind()));
    if (candidate.snippet()) {
      item.setInsertTextFormat(InsertTextFormat.Snippet);
    }

    return item;
  }

  static void applyReplacementRange(final List<CompletionItem> items, final Range range) {
    items.forEach(
        item -> {
          final var newText = item.getInsertText() != null ? item.getInsertText() : item.getLabel();
          item.setTextEdit(Either.forLeft(new TextEdit(range, newText)));
        });
  }

  private static CompletionItemKind kindFor(final CandidateKind kind) {
    return switch (kind) {
      case KEYWORD -> CompletionItemKind.Keyword;
      case LOCAL_VARIABLE -> CompletionItemKind.Variable;
      case FIELD -> CompletionItemKind.Field;
      case METHOD -> CompletionItemKind.Method;
      case PACKAGE -> CompletionItemKind.Module;
      case TYPE_CLASS -> CompletionItemKind.Class;
      case TYPE_INTERFACE -> CompletionItemKind.Interface;
      case TYPE_ENUM -> CompletionItemKind.Enum;
    };
  }
}
