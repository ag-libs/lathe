package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.ImportAnalyzer;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

final class CompletionEditApplier {

  private CompletionEditApplier() {}

  static void preserveExistingMethodCall(
      final List<CompletionItem> items, final CompletionRequest req, final int tokenEnd) {
    if (tokenEnd >= req.content().length() || req.content().charAt(tokenEnd) != '(') {
      return;
    }

    items.stream()
        .filter(item -> item.getKind() == CompletionItemKind.Method)
        .forEach(CompletionEditApplier::replaceWithMethodNameOnly);
  }

  static void applyNestedOuterImportEdits(
      final List<CompletionItem> items,
      final String receiverText,
      final AttributedFileAnalysis analysis) {
    if (analysis == null || receiverText == null || receiverText.indexOf('.') >= 0) {
      return;
    }

    final var importAnalyzer = new ImportAnalyzer(analysis);
    if (importAnalyzer.insertionRange() == null) {
      return;
    }

    items.stream()
        .filter(item -> nestedTypeOwnedByReceiver(item, receiverText))
        .map(item -> new ImportTarget(item, outerQualifiedName(item.getDetail())))
        .filter(target -> target.qualifiedName() != null)
        .forEach(
            target -> {
              final var edit = importAnalyzer.importEdit(target.qualifiedName());
              if (edit != null) {
                CompletionItemPresenter.addAdditionalTextEdit(target.item(), edit);
              }
            });
  }

  static void applyStatementSemicolonEdits(
      final List<CompletionItem> items,
      final CompletionSite site,
      final AttributedFileAnalysis analysis,
      final CompletionRequest req,
      final int tokenStart) {
    if (analysis == null) {
      return;
    }

    final TreePath cursorPath = SourceLocator.pathAt(analysis.trees(), analysis.tree(), tokenStart);
    if (cursorPath == null) {
      return;
    }

    boolean isInitializerContext = false;
    Tree exprNode = null;
    TreePath previous = cursorPath;
    for (TreePath path = cursorPath.getParentPath(); path != null; path = path.getParentPath()) {
      final Tree leaf = path.getLeaf();
      if (leaf instanceof final VariableTree variable) {
        if (variable.getInitializer() == previous.getLeaf()) {
          isInitializerContext = true;
          exprNode = variable.getInitializer();
        }

        break;
      }

      if (leaf instanceof final AssignmentTree assignment) {
        if (assignment.getExpression() == previous.getLeaf()) {
          isInitializerContext = true;
          exprNode = assignment.getExpression();
        }

        break;
      }

      if (leaf instanceof ParenthesizedTree) {
        previous = path;
        continue;
      }

      break;
    }

    if (!isInitializerContext || exprNode == null) {
      return;
    }

    final var positions = analysis.trees().getSourcePositions();
    final long exprEnd = positions.getEndPosition(analysis.tree(), exprNode);
    if (exprEnd < 0) {
      return;
    }

    final String content = req.content();
    for (int i = (int) exprEnd; i < content.length(); i++) {
      final char c = content.charAt(i);
      if (c == ';') {
        return;
      }

      if (!Character.isWhitespace(c)) {
        break;
      }
    }

    final CompilationUnitTree cu = analysis.tree();
    final long replacementEnd =
        SourceLocator.toOffset(
            cu,
            site.replacementRange().getEnd().getLine(),
            site.replacementRange().getEnd().getCharacter());

    final boolean appendDirectly = replacementEnd >= exprEnd;
    items.stream()
        .filter(item -> item.getKind() == CompletionItemKind.Method)
        .forEach(item -> applySemicolonToMethod(item, cu, exprEnd, appendDirectly));
  }

  private static void replaceWithMethodNameOnly(final CompletionItem item) {
    final String methodName = item.getFilterText() != null ? item.getFilterText() : item.getLabel();
    item.setInsertText(methodName);
    item.setInsertTextFormat(null);
    if (item.getTextEdit() != null && item.getTextEdit().isLeft()) {
      item.getTextEdit().getLeft().setNewText(methodName);
    }
  }

  private record ImportTarget(CompletionItem item, String qualifiedName) {}

  private static boolean nestedTypeOwnedByReceiver(
      final CompletionItem item, final String receiverText) {
    if (item.getDetail() == null) {
      return false;
    }

    final String outer = outerQualifiedName(item.getDetail());
    return outer != null && receiverText.equals(simpleName(outer));
  }

  private static String outerQualifiedName(final String nestedQualifiedName) {
    final int nestedDot = nestedQualifiedName.lastIndexOf('.');
    if (nestedDot < 0) {
      return null;
    }

    final String outer = nestedQualifiedName.substring(0, nestedDot);
    return outer.indexOf('.') >= 0 ? outer : null;
  }

  private static String simpleName(final String qualifiedName) {
    final int dot = qualifiedName.lastIndexOf('.');
    return dot >= 0 ? qualifiedName.substring(dot + 1) : qualifiedName;
  }

  private static void applySemicolonToMethod(
      final CompletionItem item,
      final CompilationUnitTree cu,
      final long exprEnd,
      final boolean appendDirectly) {
    final String insertText = item.getInsertText();
    if (insertText == null) {
      return;
    }

    if (appendDirectly) {
      if (insertText.endsWith("()") && item.getInsertTextFormat() != InsertTextFormat.Snippet) {
        final String newInsertText = insertText + ";";
        item.setInsertText(newInsertText);
        if (item.getTextEdit() != null && item.getTextEdit().isLeft()) {
          item.getTextEdit().getLeft().setNewText(newInsertText);
        }
      } else if (insertText.endsWith("($1)")
          && item.getInsertTextFormat() == InsertTextFormat.Snippet) {
        final String newInsertText = insertText.replace("($1)", "($1);$0");
        item.setInsertText(newInsertText);
        if (item.getTextEdit() != null && item.getTextEdit().isLeft()) {
          item.getTextEdit().getLeft().setNewText(newInsertText);
        }
      }
    } else {
      final long insertLine = cu.getLineMap().getLineNumber(exprEnd) - 1;
      final long insertCol = cu.getLineMap().getColumnNumber(exprEnd) - 1;
      final var pos = new Position((int) insertLine, (int) insertCol);
      final var range = new Range(pos, pos);
      final var semicolonEdit = new TextEdit(range, ";");
      final List<TextEdit> edits =
          item.getAdditionalTextEdits() == null
              ? List.of(semicolonEdit)
              : Stream.concat(item.getAdditionalTextEdits().stream(), Stream.of(semicolonEdit))
                  .toList();
      item.setAdditionalTextEdits(edits);
    }
  }
}
