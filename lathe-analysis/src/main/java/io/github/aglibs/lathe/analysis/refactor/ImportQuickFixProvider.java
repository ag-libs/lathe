package io.github.aglibs.lathe.analysis.refactor;

import com.sun.source.tree.Scope;
import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.CodeActionRequest;
import io.github.aglibs.lathe.analysis.ImportAnalyzer;
import io.github.aglibs.lathe.analysis.ImportLayout;
import io.github.aglibs.lathe.analysis.LearnedStaticImports;
import io.github.aglibs.lathe.analysis.StaticImportCandidates;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.analysis.internal.SourceTypeNames;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.lang.model.element.TypeElement;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public final class ImportQuickFixProvider {

  public List<Either<Command, CodeAction>> provide(
      final CodeActionRequest request,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex) {
    final List<Either<Command, CodeAction>> imports =
        importActions(
            request,
            analysis,
            alreadyImported ->
                ImportCandidates.resolve(
                    request.payload().name(),
                    request.diag().getRange().getStart(),
                    analysis,
                    typeIndex,
                    alreadyImported));
    return Stream.concat(imports.stream(), qualifyActions(request, analysis, typeIndex).stream())
        .toList();
  }

  // A nested type is written qualified from a type the code can name (Map.Entry), whose import is
  // added if it needs one, rather than imported itself.
  private static List<Either<Command, CodeAction>> qualifyActions(
      final CodeActionRequest request,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex) {
    final Position at = request.diag().getRange().getStart();
    final Scope scope = ImportCandidates.scopeAt(at, analysis);
    if (scope == null || scope.getEnclosingClass() == null) {
      return List.of();
    }

    final TypeElement target = scope.getEnclosingClass();
    return ImportCandidates.nestedTypes(request.payload().name(), scope, analysis, typeIndex)
        .stream()
        .map(
            type ->
                Either.<Command, CodeAction>forRight(
                    qualifyAction(request, analysis, target, type)))
        .toList();
  }

  private static CodeAction qualifyAction(
      final CodeActionRequest request,
      final AttributedFileAnalysis analysis,
      final TypeElement target,
      final TypeElement type) {
    final String written = SourceTypeNames.sourceName(analysis, target, type);
    final String qualifiedName = type.getQualifiedName().toString();
    final var action = new CodeAction();
    action.setTitle(
        written.equals(qualifiedName)
            ? "Qualify as '%s'".formatted(written)
            : "Qualify as '%s' (%s)".formatted(written, qualifiedName));
    action.setKind(CodeActionKind.QuickFix);
    action.setDiagnostics(List.of(request.diag()));

    final List<TextEdit> edits =
        Stream.concat(
                SourceTypeNames.importEditsFor(analysis, target, Stream.of(type.asType())).stream(),
                Stream.of(new TextEdit(request.diag().getRange(), written)))
            .toList();
    final var edit = new WorkspaceEdit();
    edit.setChanges(Map.of(request.uri(), edits));
    action.setEdit(edit);
    return action;
  }

  // An unresolved member the file's scope statically imports elsewhere: one action per owner.
  public static List<Either<Command, CodeAction>> staticImportActions(
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
    edit.setChanges(Map.of(request.uri(), List.of(layout.edit(fqName))));
    action.setEdit(edit);
    return action;
  }
}
