package io.github.aglibs.lathe.server.analysis.completion;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;

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

  private static CompletionItemKind kindFor(final CandidateKind kind) {
    return switch (kind) {
      case KEYWORD -> CompletionItemKind.Keyword;
      case LOCAL_VARIABLE -> CompletionItemKind.Variable;
      case FIELD -> CompletionItemKind.Field;
      case METHOD -> CompletionItemKind.Method;
    };
  }
}
