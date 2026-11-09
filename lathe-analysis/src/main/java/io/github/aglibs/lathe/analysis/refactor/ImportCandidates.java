package io.github.aglibs.lathe.analysis.refactor;

import com.sun.source.tree.Scope;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.CursorElements;
import io.github.aglibs.lathe.analysis.Positions;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.element.TypeElement;
import org.eclipse.lsp4j.Position;

// Resolves an unresolved simple type name to the fully-qualified names that are valid imports at a
// source position: in a real package, accessible from that scope, and not already imported. Shared
// by the single-name import quick fix and the "add missing imports" command. A nested type is never
// imported itself: it is written qualified from its top-level type, which is imported instead.
public final class ImportCandidates {

  private ImportCandidates() {}

  public static List<String> resolve(
      final String simpleName,
      final Position at,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex,
      final Set<String> alreadyImported) {
    final Scope scope = scopeAt(at, analysis);
    return typeIndex.searchExact(simpleName).stream()
        .filter(
            entry ->
                entry.simpleName().equals(simpleName)
                    && entry.isTopLevel()
                    && !entry.packageName().isEmpty())
        .map(entry -> "%s.%s".formatted(entry.packageName(), entry.simpleName()))
        .distinct()
        .filter(fqName -> !alreadyImported.contains(fqName))
        .filter(fqName -> isImportable(fqName, scope, analysis, typeIndex))
        .toList();
  }

  // The nested types of this simple name accessible at the position, to write qualified
  // (Map.Entry).
  // A nested type without a class file yet has no element to write from, so it is left out.
  public static List<TypeElement> nestedTypes(
      final String simpleName,
      final Position at,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex) {
    final Scope scope = scopeAt(at, analysis);
    return typeIndex.searchExact(simpleName).stream()
        .filter(entry -> entry.simpleName().equals(simpleName) && !entry.isTopLevel())
        .map(entry -> analysis.elements().getTypeElement(entry.qualifiedName()))
        .filter(Objects::nonNull)
        .filter(type -> scope == null || analysis.trees().isAccessible(scope, type))
        .distinct()
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

  static Scope scopeAt(final Position at, final AttributedFileAnalysis analysis) {
    final long offset = Positions.toOffset(analysis.tree(), at.getLine(), at.getCharacter());
    final TreePath path = CursorElements.pathAt(analysis.trees(), analysis.tree(), offset);
    return path != null ? analysis.trees().getScope(path) : null;
  }
}
