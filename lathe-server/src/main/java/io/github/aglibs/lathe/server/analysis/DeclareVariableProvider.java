package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

final class DeclareVariableProvider implements CodeActionProvider {

  private static final Logger LOG = Logger.getLogger(DeclareVariableProvider.class.getName());

  @Override
  public List<Either<Command, CodeAction>> provide(
      final CodeActionRequest request,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex) {
    if (analysis.tree() == null) {
      return List.of();
    }

    final CompilationUnitTree cu = analysis.tree();
    final SourcePositions positions = analysis.trees().getSourcePositions();
    final long offset =
        SourceLocator.toOffset(
            cu,
            request.diag().getRange().getStart().getLine(),
            request.diag().getRange().getStart().getCharacter());

    final TreePath path = SourceLocator.pathAt(analysis.trees(), cu, offset);
    if (path == null) {
      return List.of();
    }

    final TreePath stmtPath = findEnclosingStatement(path);
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

    final String varName = lhs.getName().toString();
    if (!varName.equals(request.payload().name())) {
      return List.of();
    }

    final TreePath rhsPath = analysis.trees().getPath(cu, assign.getExpression());
    final TypeMirror rhsType = rhsPath != null ? analysis.trees().getTypeMirror(rhsPath) : null;
    final String simpleName = rhsType != null ? CodeActionSupport.typeSimpleName(rhsType) : null;
    final String fqn = rhsType != null ? CodeActionSupport.typeFqn(rhsType) : null;
    final String typePart = simpleName != null ? simpleName : "var";

    final long lhsStart = positions.getStartPosition(cu, lhs);
    final long lhsEnd = positions.getEndPosition(cu, lhs);
    if (lhsStart < 0 || lhsEnd < 0) {
      return List.of();
    }

    final Position startPos = SourceLocator.offsetToPosition(cu, lhsStart);
    final Position endPos = SourceLocator.offsetToPosition(cu, lhsEnd);
    final var edits = new ArrayList<TextEdit>();
    edits.add(new TextEdit(new Range(startPos, endPos), typePart + " " + varName));

    if (fqn != null && !fqn.startsWith("java.lang.")) {
      final var importAnalyzer = new ImportAnalyzer(analysis);
      if (!importAnalyzer.importedQualifiedNames().contains(fqn)) {
        final Range insertionRange = importAnalyzer.insertionRange();
        if (insertionRange != null) {
          edits.add(new TextEdit(insertionRange, "import " + fqn + ";\n"));
        }
      }
    }

    final Diagnostic diag = request.diag();
    final var action = new CodeAction();
    action.setTitle("Declare local variable '" + varName + "'");
    action.setKind(CodeActionKind.QuickFix);
    action.setDiagnostics(List.of(diag));

    final var workspaceEdit = new WorkspaceEdit();
    workspaceEdit.setChanges(Map.of(request.uri(), edits));
    action.setEdit(workspaceEdit);

    LOG.fine(() -> "[codeAction:declare] %s %s".formatted(typePart, varName));
    return List.of(Either.forRight(action));
  }

  private static TreePath findEnclosingStatement(final TreePath path) {
    TreePath current = path;
    while (current != null) {
      if (current.getLeaf() instanceof ExpressionStatementTree) {
        return current;
      }
      if (current.getLeaf() instanceof MethodTree || current.getLeaf() instanceof ClassTree) {
        return null;
      }
      current = current.getParentPath();
    }
    return null;
  }
}
