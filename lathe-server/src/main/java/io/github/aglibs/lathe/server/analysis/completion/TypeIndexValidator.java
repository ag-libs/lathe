package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.Scope;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;

/**
 * Filters type-index candidates to those that are actually usable from the current compilation
 * context.
 *
 * <p>A candidate must both resolve and be readable. {@code Elements.getTypeElement} only proves the
 * type is <em>observable</em> in the module graph — a single-arg lookup spans every observable
 * module and ignores whether the current module <em>reads</em> the type's module, so a type pulled
 * into the graph by a dependency's non-transitive {@code requires} would pass. Readability is
 * therefore enforced with {@link com.sun.source.util.Trees#isAccessible}, the same primitive the
 * member-access ({@code CandidateGenerator}) and import ({@code ImportCompletionProvider}) paths
 * already use, so a candidate is offered only when a real {@code import} would accept it. When no
 * scope (or analysis) is available the filter stays permissive.
 */
final class TypeIndexValidator {

  private final AttributedFileAnalysis analysis;
  private final Scope scope;

  TypeIndexValidator(final AttributedFileAnalysis analysis, final Scope scope) {
    this.analysis = analysis;
    this.scope = scope;
  }

  boolean isResolvable(final TypeIndexEntry entry) {
    if (analysis == null) {
      return true;
    }

    final var typeElement = analysis.elements().getTypeElement(entry.qualifiedName());
    if (typeElement == null) {
      return false;
    }

    return scope == null || analysis.trees().isAccessible(scope, typeElement);
  }
}
