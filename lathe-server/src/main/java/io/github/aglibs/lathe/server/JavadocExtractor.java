package io.github.aglibs.lathe.server;

import com.sun.source.doctree.DeprecatedTree;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.EndElementTree;
import com.sun.source.doctree.LinkTree;
import com.sun.source.doctree.LiteralTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.doctree.ReturnTree;
import com.sun.source.doctree.StartElementTree;
import com.sun.source.doctree.TextTree;
import com.sun.source.doctree.ThrowsTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.ToolProvider;

final class JavadocExtractor {

  private static final Logger LOG = Logger.getLogger(JavadocExtractor.class.getName());

  private JavadocExtractor() {}

  /**
   * Extracts the rendered Javadoc for {@code element}:
   *
   * <ul>
   *   <li>Same-file / open sibling — uses the live {@code DocTrees} from the current compilation.
   *   <li>Reactor source — parses the source file found by walking {@code sourceRoots}.
   * </ul>
   *
   * For a PARAMETER element, returns the matching {@code @param} tag from the enclosing method.
   */
  static Optional<String> extract(
      final DocTrees trees, final Element element, final List<Path> sourceRoots) {
    if (element == null) {
      return Optional.empty();
    }
    if (element.getKind() == ElementKind.PARAMETER) {
      return extractParam(trees, (VariableElement) element, sourceRoots);
    }

    // Same-file / open-sibling: the element's declaration is in the live compilation
    final var declPath = trees.getPath(element);
    if (declPath != null) {
      return fromDeclPath(trees, declPath);
    }

    // Reactor fallback: find source file and parse
    return fromSourceRoots(element, element, sourceRoots);
  }

  /**
   * Returns the rendered {@code @param} tag for {@code param} from the enclosing method's Javadoc.
   * Used when the cursor is hovering over a call argument.
   */
  static Optional<String> extractParam(
      final DocTrees trees, final VariableElement param, final List<Path> sourceRoots) {
    final var enclosing = param.getEnclosingElement();
    final var paramName = param.getSimpleName().toString();

    final var methodPath = trees.getPath(enclosing);
    if (methodPath != null) {
      final var doc = trees.getDocCommentTree(methodPath);
      if (doc != null) {
        return renderParamTag(doc, paramName);
      }
      return Optional.empty();
    }

    return fromSourceRoots(enclosing, param, sourceRoots);
  }

  // ---------------------------------------------------------------------------
  // Live-compilation extraction
  // ---------------------------------------------------------------------------

  private static Optional<String> fromDeclPath(final DocTrees trees, final TreePath declPath) {
    final var doc = trees.getDocCommentTree(declPath);
    if (doc == null) {
      return Optional.empty();
    }
    final var rendered = render(doc);
    return rendered.isBlank() ? Optional.empty() : Optional.of(rendered);
  }

  // ---------------------------------------------------------------------------
  // Reactor / source-file extraction
  // ---------------------------------------------------------------------------

  /** Finds the source file for {@code lookupElement}'s top-level class and extracts Javadoc. */
  private static Optional<String> fromSourceRoots(
      final Element lookupElement, final Element targetElement, final List<Path> sourceRoots) {
    final var topLevel = topLevelClass(lookupElement);
    if (topLevel == null) {
      return Optional.empty();
    }
    return findSourceFile(topLevel.getSimpleName().toString(), sourceRoots)
        .flatMap(file -> extractFromFile(file, targetElement));
  }

  private static Optional<Path> findSourceFile(
      final String simpleName, final List<Path> sourceRoots) {
    final var fileName = simpleName + ".java";
    for (final var root : sourceRoots) {
      try (final var stream = Files.walk(root)) {
        final var found =
            stream.filter(p -> p.getFileName().toString().equals(fileName)).findFirst();
        if (found.isPresent()) {
          return found;
        }
      } catch (final IOException e) {
        LOG.log(Level.WARNING, e, () -> "[javadoc] error scanning %s".formatted(root));
      }
    }
    return Optional.empty();
  }

  private static Optional<String> extractFromFile(final Path file, final Element target) {
    final var compiler = ToolProvider.getSystemJavaCompiler();
    try (final var fm = compiler.getStandardFileManager(null, null, null)) {
      final var jfo = fm.getJavaFileObjects(file).iterator().next();
      final var task = (JavacTask) compiler.getTask(null, fm, null, null, null, List.of(jfo));
      final var cu = task.parse().iterator().next();
      final var docTrees = DocTrees.instance(task);
      return new DocScanner(docTrees, target).scan(cu, null);
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[javadoc] failed to read %s".formatted(file));
      return Optional.empty();
    }
  }

  // ---------------------------------------------------------------------------
  // AST scanner — finds the matching declaration in a parsed tree
  // ---------------------------------------------------------------------------

  private static final class DocScanner extends TreePathScanner<Optional<String>, Void> {

    private final DocTrees trees;
    private final Element target;

    DocScanner(final DocTrees trees, final Element target) {
      this.trees = trees;
      this.target = target;
    }

    @Override
    public Optional<String> visitClass(final ClassTree node, final Void v) {
      if ((target.getKind().isClass() || target.getKind().isInterface())
          && node.getSimpleName().contentEquals(target.getSimpleName())) {
        final var result = docAt(getCurrentPath());
        if (result.isPresent()) {
          return result;
        }
      }
      return super.visitClass(node, v);
    }

    @Override
    public Optional<String> visitMethod(final MethodTree node, final Void v) {
      if (matchesExecutable(node)) {
        final var result =
            target.getKind() == ElementKind.PARAMETER
                ? paramTagAt(getCurrentPath(), target.getSimpleName().toString())
                : docAt(getCurrentPath());
        if (result.isPresent()) {
          return result;
        }
      }
      return super.visitMethod(node, v);
    }

    @Override
    public Optional<String> visitVariable(final VariableTree node, final Void v) {
      if (target.getKind() == ElementKind.FIELD
          && node.getName().contentEquals(target.getSimpleName())) {
        final var result = docAt(getCurrentPath());
        if (result.isPresent()) {
          return result;
        }
      }
      return super.visitVariable(node, v);
    }

    @Override
    public Optional<String> reduce(final Optional<String> r1, final Optional<String> r2) {
      return (r1 != null && r1.isPresent()) ? r1 : (r2 != null ? r2 : Optional.empty());
    }

    private Optional<String> docAt(final TreePath path) {
      final var doc = trees.getDocCommentTree(path);
      if (doc == null) {
        return Optional.empty();
      }
      final var rendered = render(doc);
      return rendered.isBlank() ? Optional.empty() : Optional.of(rendered);
    }

    private Optional<String> paramTagAt(final TreePath path, final String paramName) {
      final var doc = trees.getDocCommentTree(path);
      return doc == null ? Optional.empty() : renderParamTag(doc, paramName);
    }

    private boolean matchesExecutable(final MethodTree node) {
      if (!(target instanceof final ExecutableElement exe)) {
        return false;
      }
      final var paramCount = exe.getParameters().size();
      if (target.getKind() == ElementKind.PARAMETER) {
        // target is a PARAMETER — find the enclosing method by name + param count
        final var enclosing = target.getEnclosingElement();
        if (enclosing.getKind() == ElementKind.CONSTRUCTOR) {
          return node.getParameters().size() == paramCount;
        }
        return node.getName().contentEquals(enclosing.getSimpleName())
            && node.getParameters().size()
                == ((ExecutableElement) enclosing).getParameters().size();
      }
      if (target.getKind() == ElementKind.CONSTRUCTOR) {
        return node.getParameters().size() == paramCount;
      }
      return node.getName().contentEquals(target.getSimpleName())
          && node.getParameters().size() == paramCount;
    }
  }

  // ---------------------------------------------------------------------------
  // DocCommentTree rendering
  // ---------------------------------------------------------------------------

  private static String render(final DocCommentTree doc) {
    final var allBody = new ArrayList<DocTree>(doc.getFirstSentence());
    allBody.addAll(doc.getBody());
    final var description = renderInline(allBody);

    final var blockSb = new StringBuilder();
    var hasParams = false;
    for (final var tag : doc.getBlockTags()) {
      switch (tag.getKind()) {
        case PARAM -> {
          final var p = (ParamTree) tag;
          if (!p.isTypeParameter()) {
            if (!hasParams) {
              blockSb.append("\n\n**Parameters:**");
              hasParams = true;
            }
            blockSb
                .append("\n- **`")
                .append(p.getName().getName())
                .append("`** — ")
                .append(renderInline(p.getDescription()));
          }
        }
        case RETURN -> {
          final var r = (ReturnTree) tag;
          blockSb.append("\n\n**Returns:** ").append(renderInline(r.getDescription()));
        }
        case THROWS, EXCEPTION -> {
          final var t = (ThrowsTree) tag;
          blockSb
              .append("\n\n**Throws** `")
              .append(t.getExceptionName())
              .append("` — ")
              .append(renderInline(t.getDescription()));
        }
        case DEPRECATED -> {
          final var d = (DeprecatedTree) tag;
          blockSb.append("\n\n**Deprecated:** ").append(renderInline(d.getBody()));
        }
        default -> {}
      }
    }

    final var sb = new StringBuilder(description);
    if (blockSb.length() > 0) {
      if (!description.isBlank()) {
        sb.append("\n\n");
      }
      sb.append(blockSb.toString().strip());
    }
    return sb.toString().strip();
  }

  private static Optional<String> renderParamTag(final DocCommentTree doc, final String paramName) {
    return doc.getBlockTags().stream()
        .filter(t -> t.getKind() == DocTree.Kind.PARAM)
        .map(t -> (ParamTree) t)
        .filter(p -> !p.isTypeParameter())
        .filter(p -> p.getName().getName().toString().equals(paramName))
        .map(p -> renderInline(p.getDescription()))
        .filter(s -> !s.isBlank())
        .findFirst();
  }

  private static String renderInline(final List<? extends DocTree> nodes) {
    final var sb = new StringBuilder();
    for (final var node : nodes) {
      switch (node.getKind()) {
        case TEXT -> sb.append(((TextTree) node).getBody());
        case CODE -> sb.append('`').append(((LiteralTree) node).getBody().getBody()).append('`');
        case LITERAL -> sb.append(((LiteralTree) node).getBody().getBody());
        case LINK, LINK_PLAIN -> {
          final var link = (LinkTree) node;
          if (!link.getLabel().isEmpty()) {
            sb.append(renderInline(link.getLabel()));
          } else {
            sb.append('`').append(link.getReference().getSignature()).append('`');
          }
        }
        case START_ELEMENT -> {
          final var name = ((StartElementTree) node).getName().toString().toLowerCase();
          if (name.equals("p") || name.equals("br")) {
            sb.append("\n\n");
          } else if (name.equals("code") || name.equals("pre")) {
            sb.append('`');
          }
        }
        case END_ELEMENT -> {
          final var name = ((EndElementTree) node).getName().toString().toLowerCase();
          if (name.equals("code") || name.equals("pre")) {
            sb.append('`');
          }
        }
        default -> sb.append(node.toString());
      }
    }
    return sb.toString().strip();
  }

  // ---------------------------------------------------------------------------

  private static TypeElement topLevelClass(final Element element) {
    var e = element;
    while (e != null) {
      if (e instanceof final TypeElement te
          && e.getEnclosingElement() != null
          && e.getEnclosingElement().getKind() == ElementKind.PACKAGE) {
        return te;
      }
      e = e.getEnclosingElement();
    }
    return null;
  }
}
