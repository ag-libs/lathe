package io.github.aglibs.lathe.server.analysis;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.ReferenceTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.DocSourcePositions;
import com.sun.source.util.DocTreePath;
import com.sun.source.util.DocTreePathScanner;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import java.util.function.Consumer;
import javax.lang.model.element.Element;

/**
 * Visits the reference tags ({@code @link}, {@code @linkplain}, {@code @see}, {@code @throws}) of
 * one declaration's Javadoc, using javac's structured {@link DocTrees} rather than parsing the
 * comment text. Each is delivered already resolved — its source span and the element it points at.
 */
final class JavadocReferences {

  private JavadocReferences() {}

  // The source span of a Javadoc reference tag and the element it resolves to (null if unresolved).
  record JavadocReference(long startOffset, long endOffset, Element element) {}

  static void forEachReference(
      final DocTrees docTrees,
      final CompilationUnitTree cu,
      final TreePath declaration,
      final Consumer<JavadocReference> visitor) {
    final DocCommentTree doc = docTrees.getDocCommentTree(declaration);
    if (doc == null) {
      return;
    }

    final DocSourcePositions positions = docTrees.getSourcePositions();
    new DocTreePathScanner<Void, Void>() {
      @Override
      public Void visitReference(final ReferenceTree reference, final Void unused) {
        visitor.accept(
            new JavadocReference(
                positions.getStartPosition(cu, doc, reference),
                positions.getEndPosition(cu, doc, reference),
                docTrees.getElement(getCurrentPath())));
        return super.visitReference(reference, unused);
      }
    }.scan(new DocTreePath(declaration, doc), null);
  }
}
