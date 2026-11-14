package io.github.aglibs.lathe.analysis.refactor;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Scope;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.CodeActionRequest;
import io.github.aglibs.lathe.analysis.Positions;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.analysis.internal.VariableNameSuggester;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import javax.lang.model.element.TypeElement;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public final class TryCatchWrapProvider {

  private static final Logger LOG = Logger.getLogger(TryCatchWrapProvider.class.getName());

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
    final TreePath statementPath = CodeActionSupport.enclosingStatementToWrap(path);
    if (statementPath == null) {
      return List.of();
    }

    final CompilationUnitTree cu = analysis.tree();
    final var positions = analysis.trees().getSourcePositions();
    final long statementStart = positions.getStartPosition(cu, statementPath.getLeaf());
    final long statementEnd = positions.getEndPosition(cu, statementPath.getLeaf());
    if (statementStart < 0 || statementEnd < 0) {
      return List.of();
    }

    final String source = CodeActionSupport.source(cu).orElse(null);
    if (source == null) {
      return List.of();
    }

    final TypeElement caught = analysis.elements().getTypeElement(request.payload().name());
    if (caught == null) {
      return List.of();
    }

    final var written = CodeActionSupport.sourceType(analysis, statementPath, caught.asType());

    final String original = source.substring((int) statementStart, (int) statementEnd).strip();
    final String indent = CodeActionSupport.lineIndent(source, (int) statementStart);
    final String step = CodeActionSupport.indentUnit(source, statementPath, cu, positions);
    final String wrapped =
        """
        try {
        %s
        %s} catch (%s %s) {
        %s}"""
            .formatted(
                CodeActionSupport.reindent(original, indent, step),
                indent,
                written.text(),
                catchName(analysis, statementPath),
                indent);

    final var start = Positions.offsetToPosition(cu, statementStart);
    final var end = Positions.offsetToPosition(cu, statementEnd);
    final var edit = new WorkspaceEdit();
    final var edits = new ArrayList<TextEdit>();
    edits.add(new TextEdit(new Range(start, end), wrapped));
    edits.addAll(written.imports());
    edit.setChanges(Map.of(request.uri(), List.copyOf(edits)));

    final var action = new CodeAction();
    action.setTitle("Wrap in try/catch");
    action.setKind(CodeActionKind.QuickFix);
    action.setDiagnostics(List.of(diag));
    action.setEdit(edit);

    LOG.fine(() -> "[codeAction:tryCatch] %s".formatted(request.payload().name()));
    return List.of(Either.forRight(action));
  }

  // `e`, unless a local or parameter in scope at the statement already has that name.
  private static String catchName(final AttributedFileAnalysis analysis, final TreePath statement) {
    final Set<String> visible = new HashSet<>();
    for (Scope scope = analysis.trees().getScope(statement);
        scope != null;
        scope = scope.getEnclosingScope()) {
      scope.getLocalElements().forEach(element -> visible.add(element.getSimpleName().toString()));
    }

    return VariableNameSuggester.uniquify("e", Set.copyOf(visible));
  }
}
