package io.github.aglibs.lathe.server.analysis;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

final class ImportQuickFixProvider implements CodeActionProvider {

  @Override
  public List<Either<Command, CodeAction>> provide(
      final CodeActionRequest request,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex) {
    final var importAnalyzer = new ImportAnalyzer(analysis);
    final var insertionRange = importAnalyzer.insertionRange();
    if (insertionRange == null) {
      return List.of();
    }

    final Set<String> alreadyImported = importAnalyzer.importedQualifiedNames();
    return ImportCandidates.resolve(
            request.payload().name(),
            request.diag().getRange().getStart(),
            analysis,
            typeIndex,
            alreadyImported)
        .stream()
        .map(
            fqName ->
                Either.<Command, CodeAction>forRight(importAction(request, fqName, insertionRange)))
        .toList();
  }

  private static CodeAction importAction(
      final CodeActionRequest request, final String fqName, final Range insertionRange) {
    final var action = new CodeAction();
    action.setTitle("Import '%s'".formatted(fqName));
    action.setKind(CodeActionKind.QuickFix);
    action.setDiagnostics(List.of(request.diag()));

    final var edit = new WorkspaceEdit();
    edit.setChanges(
        Map.of(
            request.uri(),
            List.of(new TextEdit(insertionRange, "import %s;\n".formatted(fqName)))));
    action.setEdit(edit);
    return action;
  }
}
