package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.ImportAnalyzer;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemLabelDetails;
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
    applyLabelDetails(candidate, item);
    if (candidate.snippet()) {
      item.setInsertTextFormat(InsertTextFormat.Snippet);
    }

    return item;
  }

  private static void applyLabelDetails(
      final CompletionCandidate candidate, final CompletionItem item) {
    if (candidate.labelDetail() == null && candidate.labelDescription() == null) {
      return;
    }

    final var labelDetails = new CompletionItemLabelDetails();
    labelDetails.setDetail(candidate.labelDetail());
    labelDetails.setDescription(candidate.labelDescription());
    item.setLabelDetails(labelDetails);
  }

  static void applyImportEdits(
      final List<CompletionCandidate> candidates,
      final List<CompletionItem> items,
      final AttributedFileAnalysis analysis) {
    final var importAnalyzer = new ImportAnalyzer(analysis);
    final var insertionRange = importAnalyzer.insertionRange();
    if (insertionRange == null) {
      return;
    }

    final var alreadyImported = importAnalyzer.importedQualifiedNames();
    final var alreadyStaticImported = importAnalyzer.importedStaticNames();
    for (int i = 0; i < candidates.size(); i++) {
      final ImportEdit edit = candidates.get(i).importEdit();
      if (edit == null) {
        continue;
      }

      final var existing = edit.isStatic() ? alreadyStaticImported : alreadyImported;
      if (existing.contains(edit.qualifiedName())) {
        continue;
      }

      final var importText =
          edit.isStatic()
              ? "import static %s;\n".formatted(edit.qualifiedName())
              : "import %s;\n".formatted(edit.qualifiedName());
      items.get(i).setAdditionalTextEdits(List.of(new TextEdit(insertionRange, importText)));
    }
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
      case PROPERTY -> CompletionItemKind.Property;
      case METHOD -> CompletionItemKind.Method;
      case PACKAGE -> CompletionItemKind.Module;
      case TYPE_CLASS -> CompletionItemKind.Class;
      case TYPE_INTERFACE -> CompletionItemKind.Interface;
      case TYPE_ENUM -> CompletionItemKind.Enum;
    };
  }
}
