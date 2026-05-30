package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.ImportTree;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
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

  static void applyImportEdits(
      final List<CompletionCandidate> candidates,
      final List<CompletionItem> items,
      final AttributedFileAnalysis analysis) {
    final var insertionRange = importInsertionRange(analysis);
    if (insertionRange == null) {
      return;
    }

    final var alreadyImported = importedQualifiedNames(analysis);
    final var alreadyStaticImported = importedStaticNames(analysis);
    for (int i = 0; i < candidates.size(); i++) {
      final var edit = candidates.get(i).importEdit();
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

  static Range importInsertionRange(final AttributedFileAnalysis analysis) {
    if (analysis == null || analysis.tree() == null) {
      return null;
    }

    final var cu = analysis.tree();
    final var positions = analysis.trees().getSourcePositions();
    final var lineMap = cu.getLineMap();

    final var imports = cu.getImports();
    if (!imports.isEmpty()) {
      final long endOffset = positions.getEndPosition(cu, imports.getLast());
      if (endOffset >= 0) {
        final int insertLine = (int) lineMap.getLineNumber(endOffset);
        return new Range(new Position(insertLine, 0), new Position(insertLine, 0));
      }
    }

    final var pkg = cu.getPackage();
    if (pkg != null) {
      final long endOffset = positions.getEndPosition(cu, pkg);
      if (endOffset >= 0) {
        final int insertLine = (int) lineMap.getLineNumber(endOffset);
        return new Range(new Position(insertLine, 0), new Position(insertLine, 0));
      }
    }

    return new Range(new Position(0, 0), new Position(0, 0));
  }

  private static Set<String> importedQualifiedNames(final AttributedFileAnalysis analysis) {
    if (analysis == null || analysis.tree() == null) {
      return Set.of();
    }

    return analysis.tree().getImports().stream()
        .filter(imp -> !imp.isStatic())
        .map(imp -> imp.getQualifiedIdentifier().toString())
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Set<String> importedStaticNames(final AttributedFileAnalysis analysis) {
    if (analysis == null || analysis.tree() == null) {
      return Set.of();
    }

    return analysis.tree().getImports().stream()
        .filter(ImportTree::isStatic)
        .map(imp -> imp.getQualifiedIdentifier().toString())
        .collect(Collectors.toUnmodifiableSet());
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
