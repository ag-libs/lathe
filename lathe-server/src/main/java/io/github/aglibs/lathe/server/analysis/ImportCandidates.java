package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.Scope;
import com.sun.source.util.TreePath;
import java.util.List;
import java.util.Set;
import org.eclipse.lsp4j.Position;

// Resolves an unresolved simple type name to the fully-qualified names that are valid imports at a
// source position: in a real package, accessible from that scope, and not already imported. Shared
// by the single-name import quick fix and the "add missing imports" command.
final class ImportCandidates {

  private ImportCandidates() {}

  static List<String> resolve(
      final String simpleName,
      final Position at,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex,
      final Set<String> alreadyImported) {
    final Scope scope = scopeAt(at, analysis);
    return typeIndex.searchExact(simpleName).stream()
        .filter(entry -> entry.simpleName().equals(simpleName) && !entry.packageName().isEmpty())
        .map(entry -> "%s.%s".formatted(entry.packageName(), entry.simpleName()))
        .distinct()
        .filter(fqName -> !alreadyImported.contains(fqName))
        .filter(fqName -> isImportable(fqName, scope, analysis, typeIndex))
        .toList();
  }

  private static boolean isImportable(
      final String fqName,
      final Scope scope,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex) {
    final var typeEl = analysis.elements().getTypeElement(fqName);
    if (typeEl == null) {
      // A reactor type may have no class file yet (created but not synced); trust the index entry.
      return typeIndex.isReactorType(fqName);
    }

    return scope == null || analysis.trees().isAccessible(scope, typeEl);
  }

  private static Scope scopeAt(final Position at, final AttributedFileAnalysis analysis) {
    final long offset = SourceLocator.toOffset(analysis.tree(), at.getLine(), at.getCharacter());
    final TreePath path = SourceLocator.pathAt(analysis.trees(), analysis.tree(), offset);
    return path != null ? analysis.trees().getScope(path) : null;
  }
}
