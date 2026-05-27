package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;

/**
 * Filters type-index candidates to those that are actually resolvable from the current compilation
 * context.
 *
 * <p>Visibility is determined solely by {@code Elements.getTypeElement}: if javac cannot resolve
 * the qualified name from the current compilation unit (because the type is in a module that is not
 * readable, or on a JAR that is not on the classpath), it returns {@code null} and the candidate is
 * excluded. This follows JPMS module-boundary semantics automatically — platform packages ({@code
 * java.util}, {@code java.io}, …) and dependency packages alike are handled uniformly without
 * special-casing.
 */
final class TypeIndexValidator {

  private final AttributedFileAnalysis analysis;

  TypeIndexValidator(final AttributedFileAnalysis analysis) {
    this.analysis = analysis;
  }

  boolean isResolvable(final TypeIndexEntry entry) {
    if (analysis == null) {
      return true;
    }

    return analysis.elements().getTypeElement(entry.qualifiedName()) != null;
  }
}
