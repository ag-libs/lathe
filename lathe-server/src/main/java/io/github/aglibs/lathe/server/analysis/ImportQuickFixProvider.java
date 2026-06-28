package io.github.aglibs.lathe.server.analysis;

import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

final class ImportQuickFixProvider implements CodeActionProvider {

  private static final Logger LOG = Logger.getLogger(ImportQuickFixProvider.class.getName());

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
    final String simpleName = request.payload().name();
    final var actions = new ArrayList<Either<Command, CodeAction>>();

    for (final TypeIndexEntry entry : typeIndex.search(simpleName, 100)) {
      if (!entry.simpleName().equals(simpleName)) {
        continue;
      }

      buildImportAction(request, entry, analysis, typeIndex, alreadyImported, insertionRange)
          .map(Either::<Command, CodeAction>forRight)
          .ifPresent(actions::add);
    }

    return actions;
  }

  private Optional<CodeAction> buildImportAction(
      final CodeActionRequest request,
      final TypeIndexEntry entry,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex,
      final Set<String> alreadyImported,
      final Range insertionRange) {
    if (entry.packageName().isEmpty()) {
      return Optional.empty();
    }

    final String fqName = "%s.%s".formatted(entry.packageName(), entry.simpleName());
    if (alreadyImported.contains(fqName)) {
      LOG.fine(() -> "[codeAction:import] %s already imported, skipped".formatted(fqName));
      return Optional.empty();
    }

    final var typeEl = analysis.elements().getTypeElement(fqName);
    if (typeEl == null) {
      // Reactor types may not have class files on the classpath yet (e.g. created but not synced).
      // Trust the type index entry and offer the import without the accessibility check.
      if (!typeIndex.isReactorType(fqName)) {
        return Optional.empty();
      }
    } else {
      final long offset =
          SourceLocator.toOffset(
              analysis.tree(),
              request.diag().getRange().getStart().getLine(),
              request.diag().getRange().getStart().getCharacter());
      final TreePath path = SourceLocator.pathAt(analysis.trees(), analysis.tree(), offset);
      final var scope = path != null ? analysis.trees().getScope(path) : null;
      if (scope != null && !analysis.trees().isAccessible(scope, typeEl)) {
        return Optional.empty();
      }
    }

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

    LOG.fine(() -> "[codeAction:import] %s → added".formatted(fqName));
    return Optional.of(action);
  }
}
