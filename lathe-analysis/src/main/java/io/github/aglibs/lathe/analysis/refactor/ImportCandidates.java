package io.github.aglibs.lathe.analysis.refactor;

import com.sun.source.tree.Scope;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.CursorElements;
import io.github.aglibs.lathe.analysis.Positions;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.eclipse.lsp4j.Position;

// Resolves an unresolved simple type name to the fully-qualified names that are valid imports at a
// source position: in a real package, accessible from that scope, and not already imported. A
// nested type (`Map.Entry`) imports by its canonical name, after the top-level types. Shared by the
// single-name import quick fix and the "add missing imports" command.
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
        .filter(entry -> entry.simpleName().equals(simpleName) && !entry.packageName().isEmpty())
        .sorted(Comparator.comparing(TypeIndexEntry::isTopLevel, Comparator.reverseOrder()))
        .map(TypeIndexEntry::qualifiedName)
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
    final long offset = Positions.toOffset(analysis.tree(), at.getLine(), at.getCharacter());
    final TreePath path = CursorElements.pathAt(analysis.trees(), analysis.tree(), offset);
    return path != null ? analysis.trees().getScope(path) : null;
  }
}
