package io.github.aglibs.lathe.analysis;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.doctree.ReferenceTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.DocSourcePositions;
import com.sun.source.util.DocTreePath;
import com.sun.source.util.DocTreePathScanner;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.function.Consumer;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

/**
 * Visits the reference tags ({@code @link}, {@code @linkplain}, {@code @see}, {@code @throws},
 * {@code @param}) of one declaration's Javadoc, using javac's structured {@link DocTrees} rather
 * than parsing the comment text. Each is delivered already resolved — its source span and the
 * element it points at.
 */
final class JavadocReferences {

  private JavadocReferences() {}

  // The source span of a Javadoc reference tag and the element it resolves to (null if unresolved).
  record JavadocReference(long startOffset, long endOffset, Element element) {
    JavadocReference {
      ValidCheck.check()
          .assertTrue(startOffset >= -1, "startOffset")
          .assertTrue(endOffset >= -1, "endOffset")
          .validate();
    }
  }

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
      public Void visitParam(final ParamTree param, final Void unused) {
        final var name = param.getName();
        final var namePath = new DocTreePath(getCurrentPath(), name);
        final var owner = docTrees.getElement(declaration);
        // DocTrees resolves method parameters but does not resolve a record header's components.
        final Element element =
            owner instanceof final TypeElement type
                    && type.getKind() == ElementKind.RECORD
                    && !param.isTypeParameter()
                ? type.getRecordComponents().stream()
                    .filter(component -> component.getSimpleName().contentEquals(name.getName()))
                    .findFirst()
                    .orElse(null)
                : docTrees.getElement(namePath);
        visitor.accept(
            new JavadocReference(
                positions.getStartPosition(cu, doc, name),
                positions.getEndPosition(cu, doc, name),
                element));
        return super.visitParam(param, unused);
      }

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
