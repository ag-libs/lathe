package io.github.aglibs.lathe.analysis;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.analysis.JavadocMarkdownPrinter.DocChain;
import io.github.aglibs.lathe.analysis.JavadocMarkdownPrinter.MethodDoc;
import io.github.aglibs.lathe.analysis.JavadocMarkdownPrinter.SignatureDocs;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;

public final class JavadocLocator {

  private static final Logger LOG = Logger.getLogger(JavadocLocator.class.getName());

  private final SourceParser parser;

  public JavadocLocator(final SourceParser parser) {
    this.parser = parser;
  }

  public Optional<DocCommentTree> locate(
      final Element element, final Trees trees, final List<Path> sourceRoots) {
    return readDeclaration(
        element, trees, sourceRoots, (t, path) -> ((DocTrees) t).getDocCommentTree(path));
  }

  // A reader for one request's method docs: share it across what that request asks.
  DocReader reader(final AttributedFileAnalysis analysis, final List<Path> sourceRoots) {
    return new DocReader(this, analysis, sourceRoots);
  }

  // The element's javadoc as markdown, empty when it has none. As the javadoc tool does, what a
  // method's comment leaves out, the whole of it or a part, comes from the nearest overridden
  // method that has it, and {@inheritDoc} takes the matching part of one.
  Optional<String> markdown(final Element element, final DocReader reader) {
    if (!(element instanceof final ExecutableElement method)) {
      return locate(element, reader.analysis().trees(), reader.sourceRoots())
          .map(JavadocMarkdownPrinter::format)
          .flatMap(JavadocLocator::nonBlank);
    }

    return docChain(method, reader)
        .map(chain -> JavadocMarkdownPrinter.formatMethod(chain, reader::doc))
        .flatMap(JavadocLocator::nonBlank);
  }

  // A method's or constructor's description and parameter docs for signature help, inherited as
  // hover inherits them; empty when it has no doc.
  Optional<SignatureDocs> signatureDocs(final ExecutableElement method, final DocReader reader) {
    return docChain(method, reader)
        .map(chain -> JavadocMarkdownPrinter.signatureDocs(chain, reader::doc));
  }

  // The method's own doc or, when it has none, the nearest overridden method's, with the overridden
  // methods after it; empty when none of them is documented. A constructor overrides nothing.
  private Optional<DocChain> docChain(final ExecutableElement method, final DocReader reader) {
    final AttributedFileAnalysis analysis = reader.analysis();
    final List<ExecutableElement> overridden =
        method.getKind() == ElementKind.METHOD
            ? ReferenceTarget.overriddenMethods(
                method,
                (TypeElement) method.getEnclosingElement(),
                analysis.types(),
                analysis.elements())
            : List.of();
    final Optional<MethodDoc> own = reader.doc(method);
    if (own.isPresent()) {
      return Optional.of(
          new DocChain(
              own.get(),
              own.get().paramNames(),
              overridden,
              thrownNames(method),
              returnsValue(method)));
    }

    for (int i = 0; i < overridden.size(); i++) {
      final Optional<MethodDoc> doc = reader.doc(overridden.get(i));
      if (doc.isPresent()) {
        return Optional.of(
            new DocChain(
                doc.get(),
                sourceParamNames(method, reader.sourceRoots()),
                overridden.subList(i + 1, overridden.size()),
                thrownNames(method),
                returnsValue(method)));
      }
    }

    return Optional.empty();
  }

  Optional<MethodDoc> methodDoc(
      final ExecutableElement method, final Trees trees, final List<Path> sourceRoots) {
    return readDeclaration(method, trees, sourceRoots, JavadocLocator::methodDoc);
  }

  private static boolean returnsValue(final ExecutableElement method) {
    return method.getReturnType().getKind() != TypeKind.VOID;
  }

  private static List<String> thrownNames(final ExecutableElement method) {
    return method.getThrownTypes().stream()
        .map(
            type ->
                type instanceof final DeclaredType declared
                    ? declared.asElement().getSimpleName().toString()
                    : type.toString())
        .toList();
  }

  private static Optional<String> nonBlank(final String markdown) {
    return markdown.isBlank() ? Optional.empty() : Optional.of(markdown);
  }

  private List<String> sourceParamNames(
      final ExecutableElement method, final List<Path> sourceRoots) {
    final List<String> resolved = parser.resolveParamNames(method, sourceRoots);
    return resolved != null
        ? resolved
        : method.getParameters().stream().map(p -> p.getSimpleName().toString()).toList();
  }

  private <T> Optional<T> readDeclaration(
      final Element element,
      final Trees trees,
      final List<Path> sourceRoots,
      final BiFunction<Trees, TreePath, T> read) {
    if (element == null) {
      return Optional.empty();
    }

    final var samePath = trees.getPath(element);
    if (samePath != null) {
      LOG.fine(() -> "[javadoc] same-file %s".formatted(element));
      return Optional.ofNullable(read.apply(trees, samePath));
    }

    return parser.parseDeclaration(element, sourceRoots, read);
  }

  private static MethodDoc methodDoc(final Trees trees, final TreePath path) {
    final DocCommentTree tree = ((DocTrees) trees).getDocCommentTree(path);
    if (tree == null || !(path.getLeaf() instanceof final MethodTree declaration)) {
      return null;
    }

    return new MethodDoc(
        declaration.getParameters().stream().map(p -> p.getName().toString()).toList(), tree);
  }
}
