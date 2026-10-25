package io.github.aglibs.lathe.server.analysis;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

final class ImportQuickFixProvider implements CodeActionProvider {

  @Override
  public List<Either<Command, CodeAction>> provide(
      final CodeActionRequest request,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex) {
    return importActions(
        request,
        analysis,
        alreadyImported ->
            ImportCandidates.resolve(
                request.payload().name(),
                request.diag().getRange().getStart(),
                analysis,
                typeIndex,
                alreadyImported));
  }

  // An unresolved member the file's scope statically imports elsewhere: one action per owner.
  static List<Either<Command, CodeAction>> staticImportActions(
      final CodeActionRequest request,
      final AttributedFileAnalysis analysis,
      final LearnedStaticImports learned) {
    return importActions(
        request,
        analysis,
        alreadyImported ->
            StaticImportCandidates.importSpellings(learned, analysis, request.payload().name()));
  }

  private static List<Either<Command, CodeAction>> importActions(
      final CodeActionRequest request,
      final AttributedFileAnalysis analysis,
      final Function<Set<String>, List<String>> spellings) {
    final var importAnalyzer = new ImportAnalyzer(analysis);
    final Optional<ImportLayout> layout = importAnalyzer.layout();
    if (layout.isEmpty()) {
      return List.of();
    }

    return spellings.apply(importAnalyzer.importedQualifiedNames()).stream()
        .map(
            spelling ->
                Either.<Command, CodeAction>forRight(importAction(request, spelling, layout.get())))
        .toList();
  }

  private static CodeAction importAction(
      final CodeActionRequest request, final String fqName, final ImportLayout layout) {
    final var action = new CodeAction();
    action.setTitle("Import '%s'".formatted(fqName));
    action.setKind(CodeActionKind.QuickFix);
    action.setDiagnostics(List.of(request.diag()));

    final var edit = new WorkspaceEdit();
    edit.setChanges(Map.of(request.uri(), layout.edits(List.of(fqName))));
    action.setEdit(edit);
    return action;
  }
}
