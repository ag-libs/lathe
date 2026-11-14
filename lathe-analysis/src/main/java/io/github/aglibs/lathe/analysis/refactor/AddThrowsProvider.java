package io.github.aglibs.lathe.analysis.refactor;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.CodeActionRequest;
import io.github.aglibs.lathe.analysis.Positions;
import io.github.aglibs.lathe.analysis.TreePaths;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.core.CollectionUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.lang.model.element.TypeElement;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public final class AddThrowsProvider {

  private static final Logger LOG = Logger.getLogger(AddThrowsProvider.class.getName());

  public List<Either<Command, CodeAction>> provide(
      final CodeActionRequest request,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex) {
    if (analysis.tree() == null) {
      return List.of();
    }

    final Diagnostic diag = request.diag();
    final CompilationUnitTree cu = analysis.tree();
    final var positions = analysis.trees().getSourcePositions();

    final TreePath path =
        CodeActionSupport.pathAt(
            analysis,
            diag.getRange().getStart().getLine(),
            diag.getRange().getStart().getCharacter());
    if (CodeActionSupport.isInsideClosure(path)) {
      LOG.fine(() -> "[codeAction:throws] closure context skipped");
      return List.of();
    }

    final TreePath methodPath = TreePaths.enclosingMethod(path);
    if (methodPath == null) {
      return List.of();
    }

    final MethodTree methodTree = (MethodTree) methodPath.getLeaf();
    final String fqn = request.payload().name();
    final TypeElement thrown = analysis.elements().getTypeElement(fqn);
    if (thrown == null) {
      return List.of();
    }

    final var written = CodeActionSupport.sourceType(analysis, methodPath, thrown.asType());

    final String source = CodeActionSupport.source(cu).orElse(null);
    if (source == null) {
      return List.of();
    }

    final TextEdit throwsEdit = buildThrowsEdit(methodTree, written.text(), cu, positions, source);
    if (throwsEdit == null) {
      return List.of();
    }

    final var edits = new ArrayList<TextEdit>();
    edits.add(throwsEdit);
    edits.addAll(written.imports());

    final var action = new CodeAction();
    action.setTitle("Add 'throws %s' to method".formatted(written.text()));
    action.setKind(CodeActionKind.QuickFix);
    action.setDiagnostics(List.of(diag));

    final var edit = new WorkspaceEdit();
    edit.setChanges(Map.of(request.uri(), edits));
    action.setEdit(edit);

    LOG.fine(() -> "[codeAction:throws] %s → %s".formatted(fqn, methodTree.getName()));
    return List.of(Either.forRight(action));
  }

  private static TextEdit buildThrowsEdit(
      final MethodTree methodTree,
      final String typeText,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final String source) {
    final List<? extends ExpressionTree> existingThrows = methodTree.getThrows();
    if (!existingThrows.isEmpty()) {
      final long lastEnd = positions.getEndPosition(cu, CollectionUtil.last(existingThrows));
      if (lastEnd < 0) {
        return null;
      }

      final var pos = Positions.offsetToPosition(cu, lastEnd);
      return new TextEdit(new Range(pos, pos), ", %s".formatted(typeText));
    }

    if (methodTree.getBody() == null) {
      return null;
    }

    final long bodyStart = positions.getStartPosition(cu, methodTree.getBody());
    if (bodyStart < 0) {
      return null;
    }

    int insertOffset = (int) bodyStart;
    while (insertOffset > 0 && Character.isWhitespace(source.charAt(insertOffset - 1))) {
      insertOffset--;
    }

    final Position pos = Positions.offsetToPosition(cu, insertOffset);
    return new TextEdit(new Range(pos, pos), " throws %s".formatted(typeText));
  }
}
