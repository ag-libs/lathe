package io.github.aglibs.lathe.analysis.refactor;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.CodeActionRequest;
import io.github.aglibs.lathe.analysis.CursorElements;
import io.github.aglibs.lathe.analysis.Positions;
import io.github.aglibs.lathe.analysis.TreePaths;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public final class DeclareVariableProvider {

  private static final Logger LOG = Logger.getLogger(DeclareVariableProvider.class.getName());

  public List<Either<Command, CodeAction>> provide(
      final CodeActionRequest request,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex) {
    if (analysis.tree() == null) {
      return List.of();
    }

    final CompilationUnitTree cu = analysis.tree();
    final var positions = analysis.trees().getSourcePositions();
    final long offset =
        Positions.toOffset(
            cu,
            request.diag().getRange().getStart().getLine(),
            request.diag().getRange().getStart().getCharacter());

    final TreePath path = CursorElements.pathAt(analysis.trees(), cu, offset);
    if (path == null) {
      return List.of();
    }

    final TreePath stmtPath = TreePaths.enclosingExpressionStatement(path);
    if (stmtPath == null) {
      return List.of();
    }

    final ExpressionStatementTree exprStmt = (ExpressionStatementTree) stmtPath.getLeaf();
    if (!(exprStmt.getExpression() instanceof AssignmentTree assign)) {
      return List.of();
    }
    if (!(assign.getVariable() instanceof IdentifierTree lhs)) {
      return List.of();
    }

    final var varName = lhs.getName().toString();
    if (!varName.equals(request.payload().name())) {
      return List.of();
    }

    final TreePath rhsPath = analysis.trees().getPath(cu, assign.getExpression());
    final TypeMirror rhsType = rhsPath != null ? analysis.trees().getTypeMirror(rhsPath) : null;
    final CodeActionSupport.SourceType written =
        rhsType != null && CodeActionSupport.isDenotable(rhsType)
            ? CodeActionSupport.sourceType(analysis, rhsPath, rhsType)
            : new CodeActionSupport.SourceType("var", List.of());

    final long lhsStart = positions.getStartPosition(cu, lhs);
    final long lhsEnd = positions.getEndPosition(cu, lhs);
    if (lhsStart < 0 || lhsEnd < 0) {
      return List.of();
    }

    final var startPos = Positions.offsetToPosition(cu, lhsStart);
    final var endPos = Positions.offsetToPosition(cu, lhsEnd);
    final var edits = new ArrayList<TextEdit>();
    edits.add(
        new TextEdit(new Range(startPos, endPos), "%s %s".formatted(written.text(), varName)));
    edits.addAll(written.imports());

    final Diagnostic diag = request.diag();
    final var action = new CodeAction();
    action.setTitle("Declare local variable '%s'".formatted(varName));
    action.setKind(CodeActionKind.QuickFix);
    action.setDiagnostics(List.of(diag));

    final var workspaceEdit = new WorkspaceEdit();
    workspaceEdit.setChanges(Map.of(request.uri(), edits));
    action.setEdit(workspaceEdit);

    LOG.fine(() -> "[codeAction:declare] %s %s".formatted(written.text(), varName));
    return List.of(Either.forRight(action));
  }
}
