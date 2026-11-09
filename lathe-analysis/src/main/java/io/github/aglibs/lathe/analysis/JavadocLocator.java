package io.github.aglibs.lathe.analysis;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.analysis.JavadocMarkdownPrinter.MethodDoc;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Logger;
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
    return readDeclaration(
        element, trees, sourceRoots, (t, path) -> ((DocTrees) t).getDocCommentTree(path));
  }

  // The element's javadoc as markdown, empty when it has none. A method with no comment takes the
  // nearest overridden method's doc, and {@inheritDoc} the matching part of one; a comment that
  // leaves a part out does not inherit it.
  Optional<String> markdown(
      final Element element, final AttributedFileAnalysis analysis, final List<Path> sourceRoots) {
    if (!(element instanceof final ExecutableElement method)
        || method.getKind() != ElementKind.METHOD) {
      return locate(element, analysis.trees(), sourceRoots)
          .map(JavadocMarkdownPrinter::format)
          .filter(md -> !md.isBlank());
    }

    // Both kinds of answer are kept, so a method is parsed at most once per hover.
    final var documented = new HashMap<ExecutableElement, MethodDoc>();
    final var undocumented = new HashSet<ExecutableElement>();
    final Function<ExecutableElement, Optional<MethodDoc>> docOf =
        m -> {
          if (documented.containsKey(m) || undocumented.contains(m)) {
            return Optional.ofNullable(documented.get(m));
          }

          final Optional<MethodDoc> doc =
              readDeclaration(m, analysis.trees(), sourceRoots, JavadocLocator::methodDoc);
          doc.ifPresentOrElse(found -> documented.put(m, found), () -> undocumented.add(m));
          return doc;
        };
    final List<ExecutableElement> overridden =
        ReferenceTarget.overriddenMethods(
            method,
            (TypeElement) method.getEnclosingElement(),
            analysis.types(),
            analysis.elements());
    final Optional<MethodDoc> own = docOf.apply(method);
    if (own.isPresent()) {
      return nonBlank(
          JavadocMarkdownPrinter.formatMethod(
              own.get(), own.get().paramNames(), overridden, docOf));
    }

    for (int i = 0; i < overridden.size(); i++) {
      final Optional<MethodDoc> doc = docOf.apply(overridden.get(i));
      if (doc.isPresent()) {
        return nonBlank(
            JavadocMarkdownPrinter.formatMethod(
                doc.get(),
                sourceParamNames(method, sourceRoots),
                overridden.subList(i + 1, overridden.size()),
                docOf));
      }
    }

    return Optional.empty();
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
