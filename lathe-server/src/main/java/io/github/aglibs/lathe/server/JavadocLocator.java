package io.github.aglibs.lathe.server;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;

final class JavadocLocator {

  private static final Logger LOG = Logger.getLogger(JavadocLocator.class.getName());

  private JavadocLocator() {}

  static Optional<String> locate(
      final Element element, final Trees trees, final List<Path> sourceRoots) {
    if (element == null) {
      return Optional.empty();
    }

    final var samePath = trees.getPath(element);
    if (samePath != null) {
      LOG.fine(() -> "[javadoc] same-file %s".formatted(element));
      return Optional.ofNullable(trees.getDocComment(samePath));
    }

    return DefinitionLocator.findSourceFile(element, sourceRoots)
        .flatMap(
            file -> {
              LOG.fine(() -> "[javadoc] reactor %s → %s".formatted(element, file));
              return SourceParser.parse(
                  file,
                  (parsedTrees, cu) -> {
                    final var path = findDeclPath(cu, element);
                    return path != null ? parsedTrees.getDocComment(path) : null;
                  });
            });
  }

  private static TreePath findDeclPath(
      final com.sun.source.tree.CompilationUnitTree cu, final Element target) {
    final var result = new AtomicReference<TreePath>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitClass(final ClassTree t, final Void v) {
        if ((target.getKind().isClass() || target.getKind().isInterface())
            && t.getSimpleName().contentEquals(target.getSimpleName())) {
          result.set(getCurrentPath());
        }
        return super.visitClass(t, v);
      }

      @Override
      public Void visitMethod(final MethodTree t, final Void v) {
        if ((target.getKind() == ElementKind.METHOD || target.getKind() == ElementKind.CONSTRUCTOR)
            && t.getName().contentEquals(ctorName(target))
            && t.getParameters().size() == ((ExecutableElement) target).getParameters().size()) {
          result.set(getCurrentPath());
        }
        return super.visitMethod(t, v);
      }

      @Override
      public Void visitVariable(final VariableTree t, final Void v) {
        if (target.getKind() == ElementKind.FIELD
            && t.getName().contentEquals(target.getSimpleName())
            && getCurrentPath().getParentPath().getLeaf() instanceof ClassTree) {
          result.set(getCurrentPath());
        }
        return super.visitVariable(t, v);
      }
    }.scan(cu, null);
    return result.get();
  }

  private static CharSequence ctorName(final Element element) {
    return element.getKind() == ElementKind.CONSTRUCTOR
        ? element.getEnclosingElement().getSimpleName()
        : element.getSimpleName();
  }
}
