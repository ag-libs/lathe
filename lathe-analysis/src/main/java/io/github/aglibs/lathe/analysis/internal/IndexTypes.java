package io.github.aglibs.lathe.analysis.internal;

import com.sun.source.tree.Scope;
import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;

// Whether a type-index entry may be offered at a scope, the one rule completion and the import
// fixes share. A type must resolve and be accessible there: getTypeElement alone only proves the
// type observable in the module graph, not that this module reads its module, so the access check
// is what makes a real import accept it. A reactor type with no class file yet (created or renamed,
// not compiled) has no element to check, so its index entry is trusted when a tree the file sees
// declares it (WorkspaceTypeIndex.seenFrom). With no analysis or scope yet, nothing is checked.
public final class IndexTypes {

  private IndexTypes() {}

  public static boolean usable(
      final TypeIndexEntry entry,
      final AttributedFileAnalysis analysis,
      final Scope scope,
      final WorkspaceTypeIndex typeIndex) {
    if (analysis == null) {
      return true;
    }

    final var typeElement = analysis.elements().getTypeElement(entry.qualifiedName());
    if (typeElement == null) {
      return typeIndex.isVisibleReactorType(entry.binaryName());
    }

    return scope == null || analysis.trees().isAccessible(scope, typeElement);
  }
}
