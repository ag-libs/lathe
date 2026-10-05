package io.github.aglibs.lathe.server.analysis;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePathScanner;
import java.util.concurrent.atomic.AtomicReference;
import javax.lang.model.element.Element;

/**
 * Resolves the element a Javadoc reference tag points at ({@code @link}, {@code @linkplain},
 * {@code @see}, {@code @throws}) at a cursor offset, using javac's structured {@link DocTrees} —
 * the comment is read as a parsed {@link DocCommentTree}, never by scanning its text.
 */
final class JavadocReferenceResolver {

  private JavadocReferenceResolver() {}

  static Element referenceAt(final AttributedFileAnalysis analysis, final long offset) {
    if (analysis == null
        || analysis.tree() == null
        || !(analysis.trees() instanceof final DocTrees docTrees)) {
      return null;
    }

    final CompilationUnitTree cu = analysis.tree();
    final var result = new AtomicReference<Element>();

    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitClass(final ClassTree node, final Void unused) {
        resolveDeclaration();
        return super.visitClass(node, unused);
      }

      @Override
      public Void visitMethod(final MethodTree node, final Void unused) {
        resolveDeclaration();
        return super.visitMethod(node, unused);
      }

      @Override
      public Void visitVariable(final VariableTree node, final Void unused) {
        resolveDeclaration();
        return super.visitVariable(node, unused);
      }

      private void resolveDeclaration() {
        if (result.get() != null) {
          return;
        }

        JavadocReferences.forEachReference(
            docTrees,
            cu,
            getCurrentPath(),
            reference -> {
              if (result.get() == null
                  && reference.element() != null
                  && reference.startOffset() <= offset
                  && offset < reference.endOffset()) {
                result.set(reference.element());
              }
            });
      }
    }.scan(cu, null);

    return result.get();
  }
}
