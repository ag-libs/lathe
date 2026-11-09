package io.github.aglibs.lathe.analysis;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.analysis.JavadocMarkdownPrinter.MethodDoc;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

public final class JavadocLocator {

  private static final Logger LOG = Logger.getLogger(JavadocLocator.class.getName());

  private final SourceParser parser;

  public JavadocLocator(final SourceParser parser) {
    this.parser = parser;
  }

  public Optional<DocCommentTree> locate(
      final Element element, final Trees trees, final List<Path> sourceRoots) {
    if (element == null) {
      return Optional.empty();
    }

    final var samePath = trees.getPath(element);
    if (samePath != null) {
      LOG.fine(() -> "[javadoc] same-file %s".formatted(element));
      return Optional.ofNullable(((DocTrees) trees).getDocCommentTree(samePath));
    }

    return parser.parseDeclaration(
        element, sourceRoots, (t, path) -> ((DocTrees) t).getDocCommentTree(path));
  }

  // The element's javadoc as markdown, empty when it has none. A method with no comment, or with
  // {@inheritDoc}, takes the docs of the methods it overrides, as the javadoc tool does.
  Optional<String> markdown(
      final Element element, final AttributedFileAnalysis analysis, final List<Path> sourceRoots) {
    if (!(element instanceof final ExecutableElement method)
        || method.getKind() != ElementKind.METHOD) {
      return locate(element, analysis.trees(), sourceRoots)
          .map(JavadocMarkdownPrinter::format)
          .filter(md -> !md.isBlank());
    }

    final Optional<MethodDoc> own = methodDoc(method, analysis.trees(), sourceRoots);
    final Stream<MethodDoc> inherited =
        own.isPresent() && !JavadocMarkdownPrinter.inheritsDoc(own.get().tree())
            ? Stream.empty()
            : ReferenceTarget.overriddenMethods(
                    method,
                    (TypeElement) method.getEnclosingElement(),
                    analysis.types(),
                    analysis.elements())
                .stream()
                .flatMap(
                    overridden -> methodDoc(overridden, analysis.trees(), sourceRoots).stream());
    final List<MethodDoc> chain = Stream.concat(own.stream(), inherited).toList();
    return Optional.of(JavadocMarkdownPrinter.formatChain(chain)).filter(md -> !md.isBlank());
  }

  private Optional<MethodDoc> methodDoc(
      final ExecutableElement method, final Trees trees, final List<Path> sourceRoots) {
    final var samePath = trees.getPath(method);
    if (samePath != null) {
      return Optional.ofNullable(methodDoc(trees, samePath));
    }

    return parser.parseDeclaration(method, sourceRoots, JavadocLocator::methodDoc);
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
