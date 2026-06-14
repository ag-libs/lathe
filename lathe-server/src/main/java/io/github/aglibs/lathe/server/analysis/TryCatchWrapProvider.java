package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import java.io.IOException;
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

final class TryCatchWrapProvider implements CodeActionProvider {

  private static final Logger LOG = Logger.getLogger(TryCatchWrapProvider.class.getName());

  @Override
  public List<Either<Command, CodeAction>> provide(
      final CodeActionRequest request,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex) {
    if (analysis.tree() == null) {
      return List.of();
    }

    final Diagnostic diag = request.diag();
    final TreePath path =
        CodeActionSupport.pathAt(
            analysis,
            diag.getRange().getStart().getLine(),
            diag.getRange().getStart().getCharacter());
    final TreePath statementPath = CodeActionSupport.enclosingClosureStatement(path);
    if (statementPath == null) {
      return List.of();
    }

    final CompilationUnitTree cu = analysis.tree();
    final SourcePositions positions = analysis.trees().getSourcePositions();
    final long statementStart = positions.getStartPosition(cu, statementPath.getLeaf());
    final long statementEnd = positions.getEndPosition(cu, statementPath.getLeaf());
    if (statementStart < 0 || statementEnd < 0) {
      return List.of();
    }

    final String source;
    try {
      source = cu.getSourceFile().getCharContent(false).toString();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[codeAction:tryCatch] failed to read source");
      return List.of();
    }

    final String original = source.substring((int) statementStart, (int) statementEnd).strip();
    final String indent = lineIndent(source, (int) statementStart);
    final String wrapped =
        """
        try {
        %s%s
        %s} catch (%s e) {
        %s}"""
            .formatted(indent + "  ", original, indent, request.payload().name(), indent);

    final Position start = SourceLocator.offsetToPosition(cu, statementStart);
    final Position end = SourceLocator.offsetToPosition(cu, statementEnd);
    final var edit = new WorkspaceEdit();
    edit.setChanges(Map.of(request.uri(), List.of(new TextEdit(new Range(start, end), wrapped))));

    final var action = new CodeAction();
    action.setTitle("Wrap in try/catch");
    action.setKind(CodeActionKind.QuickFix);
    action.setDiagnostics(List.of(diag));
    action.setEdit(edit);

    LOG.fine(() -> "[codeAction:tryCatch] %s".formatted(request.payload().name()));
    return List.of(Either.forRight(action));
  }

  private static String lineIndent(final String source, final int offset) {
    int lineStart = offset;
    while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
      lineStart--;
    }

    int indentEnd = lineStart;
    while (indentEnd < source.length() && Character.isWhitespace(source.charAt(indentEnd))) {
      if (source.charAt(indentEnd) == '\n') {
        break;
      }

      indentEnd++;
    }
    return source.substring(lineStart, indentEnd);
  }
}
