package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

final class AddThrowsProvider implements CodeActionProvider {

  private static final Logger LOG = Logger.getLogger(AddThrowsProvider.class.getName());

  @Override
  public List<Either<Command, CodeAction>> provide(
      final CodeActionRequest request,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex) {
    if (analysis.tree() == null) {
      return List.of();
    }

    final Diagnostic diag = request.diag();
    final CompilationUnitTree cu = analysis.tree();
    final SourcePositions positions = analysis.trees().getSourcePositions();

    final TreePath path =
        CodeActionSupport.pathAt(
            analysis,
            diag.getRange().getStart().getLine(),
            diag.getRange().getStart().getCharacter());
    if (CodeActionSupport.isInsideClosure(path)) {
      LOG.fine(() -> "[codeAction:throws] closure context skipped");
      return List.of();
    }

    final TreePath methodPath = CodeActionSupport.enclosingMethod(path);
    if (methodPath == null) {
      return List.of();
    }

    final MethodTree methodTree = (MethodTree) methodPath.getLeaf();
    final String fqn = request.payload().name();
    final int lastDot = fqn.lastIndexOf('.');
    final String simpleName = lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
    final String pkg = lastDot >= 0 ? fqn.substring(0, lastDot) : "";

    final String source;
    try {
      source = cu.getSourceFile().getCharContent(false).toString();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[codeAction:throws] failed to read source");
      return List.of();
    }

    final TextEdit throwsEdit = buildThrowsEdit(methodTree, simpleName, cu, positions, source);
    if (throwsEdit == null) {
      return List.of();
    }

    final var edits = new ArrayList<TextEdit>();
    edits.add(throwsEdit);

    if (!pkg.equals("java.lang")) {
      final var importAnalyzer = new ImportAnalyzer(analysis);
      if (!importAnalyzer.importedQualifiedNames().contains(fqn)) {
        final Range insertionRange = importAnalyzer.insertionRange();
        if (insertionRange != null) {
          edits.add(new TextEdit(insertionRange, "import %s;\n".formatted(fqn)));
        }
      }
    }

    final var action = new CodeAction();
    action.setTitle("Add 'throws %s' to method".formatted(simpleName));
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
      final String simpleName,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final String source) {
    final List<? extends ExpressionTree> existingThrows = methodTree.getThrows();
    if (!existingThrows.isEmpty()) {
      final long lastEnd = positions.getEndPosition(cu, existingThrows.getLast());
      if (lastEnd < 0) {
        return null;
      }

      final Position pos = SourceLocator.offsetToPosition(cu, lastEnd);
      return new TextEdit(new Range(pos, pos), ", %s".formatted(simpleName));
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

    final Position pos = SourceLocator.offsetToPosition(cu, insertOffset);
    return new TextEdit(new Range(pos, pos), " throws %s".formatted(simpleName));
  }
}
