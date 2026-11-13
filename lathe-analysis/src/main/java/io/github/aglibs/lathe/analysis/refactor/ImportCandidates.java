package io.github.aglibs.lathe.analysis.refactor;

import com.sun.source.tree.Scope;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.CursorElements;
import io.github.aglibs.lathe.analysis.Positions;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.analysis.internal.IndexTypes;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import org.eclipse.lsp4j.Position;

// Resolves an unresolved simple type name to the fully-qualified names that are valid imports at a
// source position: in a real package, accessible from that scope, and not already imported. Shared
// by the single-name import quick fix and the "add missing imports" command. A nested type is never
// imported itself: it is written qualified from its top-level type, which is imported instead.
public final class ImportCandidates {

  private static final int NESTED_TYPE_LIMIT = 10;

  private ImportCandidates() {}

  public static List<String> resolve(
      final String simpleName,
      final Scope scope,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex,
      final Set<String> alreadyImported) {
    return typeIndex.searchExact(simpleName).stream()
        .filter(
            entry ->
                entry.simpleName().equals(simpleName)
                    && entry.isTopLevel()
                    && !entry.packageName().isEmpty()
                    && !alreadyImported.contains(entry.binaryName()))
        .filter(entry -> IndexTypes.usable(entry, analysis, scope, typeIndex))
        .map(TypeIndexEntry::binaryName)
        .distinct()
        .toList();
  }

  // The nested types of this simple name accessible in the scope, to write qualified (Map.Entry),
  // the most used first; one without a class file yet has no element to write from, so it is left
  // out. A common name (Builder) is nested in many types, so only the first few are offered.
  public static List<TypeElement> nestedTypes(
      final String simpleName,
      final Scope scope,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex) {
    return typeIndex.searchExact(simpleName).stream()
        .filter(entry -> entry.simpleName().equals(simpleName) && !entry.isTopLevel())
        .map(entry -> analysis.elements().getTypeElement(entry.qualifiedName()))
        .filter(Objects::nonNull)
        .filter(
            type -> scope == null ? publicChain(type) : analysis.trees().isAccessible(scope, type))
        .distinct()
        .limit(NESTED_TYPE_LIMIT)
        .toList();
  }

  // Without a scope to check access from, only a type public all the way out is offered.
  private static boolean publicChain(final TypeElement type) {
    return Stream.iterate(
            type,
            Objects::nonNull,
            t -> t.getEnclosingElement() instanceof final TypeElement outer ? outer : null)
        .allMatch(t -> t.getModifiers().contains(Modifier.PUBLIC));
  }

  // The scope at a diagnostic's position; none where javac has no path there.
  public static Scope scopeAt(final Position at, final AttributedFileAnalysis analysis) {
    final long offset = Positions.toOffset(analysis.tree(), at.getLine(), at.getCharacter());
    final TreePath path = CursorElements.pathAt(analysis.trees(), analysis.tree(), offset);
    return path != null ? analysis.trees().getScope(path) : null;
  }
}
