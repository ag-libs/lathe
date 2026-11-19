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
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;

/**
 * Visits the reference tags ({@code @link}, {@code @linkplain}, {@code @see}, {@code @throws},
 * {@code @param}) of one declaration's Javadoc, using javac's structured {@link DocTrees} rather
 * than parsing the comment text. Each is delivered already resolved — its source span and the
 * element it points at. A member reference's qualifier and parameter types come before the
 * reference itself, so the first span holding a position is the narrowest.
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
        final long start = positions.getStartPosition(cu, doc, reference);
        final long end = positions.getEndPosition(cu, doc, reference);
        final Element element = docTrees.getElement(getCurrentPath());
        final String signature = reference.getSignature();
        if (signature.length() == end - start) {
          qualifierType(docTrees, getCurrentPath(), signature, start, visitor);
          if (element instanceof final ExecutableElement method) {
            forEachParameterType(signature, start, method, visitor);
          }
        }

        visitor.accept(new JavadocReference(start, end, element));
        return super.visitReference(reference, unused);
      }
    }.scan(new DocTreePath(declaration, doc), null);
  }

  // DocTrees gives the parts of a member reference no tree or position of their own, so a part's
  // element comes from javac and its span is that element's simple name, found as an identifier in
  // the signature text, as ReferenceLocator finds every Javadoc mention.

  // The type before '#', resolved by javac as a reference of its own in the same comment, so an
  // inherited member's qualifier is the subtype written, not the member's owner, and a member that
  // does not resolve keeps its type's span.
  private static void qualifierType(
      final DocTrees docTrees,
      final DocTreePath reference,
      final String signature,
      final long start,
      final Consumer<JavadocReference> visitor) {
    final int hash = signature.indexOf('#');
    if (hash <= 0) {
      return;
    }

    final ReferenceTree qualifier;
    try {
      qualifier = docTrees.getDocTreeFactory().newReferenceTree(signature.substring(0, hash));
    } catch (final IllegalArgumentException e) {
      // javac parsed the whole signature; its part before '#' failing alone only loses the span.
      return;
    }

    final Element type = docTrees.getElement(new DocTreePath(reference.getParentPath(), qualifier));
    if (type instanceof TypeElement) {
      nameSpan(signature, 0, hash, type, start, visitor);
    }
  }

  // Each parameter type, in order after '(': the resolved method's parameter types, whose names
  // are found one after the other, so a qualifier or a parameter name stays the member's.
  private static void forEachParameterType(
      final String signature,
      final long start,
      final ExecutableElement method,
      final Consumer<JavadocReference> visitor) {
    int from = signature.indexOf('(') + 1;
    if (from == 0) {
      return;
    }

    for (final VariableElement parameter : method.getParameters()) {
      final Element type = declaredElement(parameter.asType());
      if (type != null) {
        from = nameSpan(signature, from, signature.length(), type, start, visitor);
      }
    }
  }

  // Reports the element's simple name, the first one in [from, limit) of the signature, and returns
  // where the search goes on: past the name, or from again when it is not there.
  private static int nameSpan(
      final String signature,
      final int from,
      final int limit,
      final Element element,
      final long start,
      final Consumer<JavadocReference> visitor) {
    final String name = element.getSimpleName().toString();
    final long at = DeclarationNames.findIdentifierFrom(signature, from, name);
    if (at < 0 || at + name.length() > limit) {
      return from;
    }

    visitor.accept(new JavadocReference(start + at, start + at + name.length(), element));
    return (int) at + name.length();
  }

  // The class or interface a parameter type names in Javadoc, through any array and, for a type
  // variable, its erasure (the first bound), as javac matched it; null for a primitive.
  private static Element declaredElement(final TypeMirror type) {
    TypeMirror erased = type;
    while (!(erased instanceof DeclaredType)) {
      if (erased instanceof final ArrayType array) {
        erased = array.getComponentType();
      } else if (erased instanceof final TypeVariable variable) {
        erased = variable.getUpperBound();
      } else if (erased instanceof final IntersectionType intersection) {
        erased = intersection.getBounds().get(0);
      } else {
        return null;
      }
    }

    return ((DeclaredType) erased).asElement();
  }
}
