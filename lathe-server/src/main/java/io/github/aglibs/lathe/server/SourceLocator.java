package io.github.aglibs.lathe.server;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import org.eclipse.lsp4j.Position;

final class SourceLocator {

  private SourceLocator() {}

  static long toOffset(final CompilationUnitTree cu, final int line, final int character) {
    return cu.getLineMap().getPosition(line + 1, character + 1);
  }

  static Position offsetToPosition(final CompilationUnitTree cu, final long offset) {
    final var lineMap = cu.getLineMap();
    return new Position(
        (int) lineMap.getLineNumber(offset) - 1, (int) lineMap.getColumnNumber(offset) - 1);
  }

  static TreePath pathAt(final Trees trees, final CompilationUnitTree cu, final long offset) {
    final var positions = trees.getSourcePositions();
    final var result = new AtomicReference<TreePath>();
    final var size = new AtomicLong(Long.MAX_VALUE);

    new TreePathScanner<Void, Void>() {
      @Override
      public Void scan(final Tree tree, final Void unused) {
        if (tree == null) {
          return null;
        }
        final long start = positions.getStartPosition(cu, tree);
        final long end = positions.getEndPosition(cu, tree);
        if (start <= offset && offset < end) {
          final long span = end - start;
          if (span <= size.get()) {
            size.set(span);
            result.set(new TreePath(getCurrentPath(), tree));
          }
        }
        return super.scan(tree, unused);
      }
    }.scan(cu, null);

    return result.get();
  }

  static Position offsetToPosition(final String content, final long offset) {
    final int limit = (int) Math.min(offset, content.length());
    int line = 0;
    int col = 0;
    for (int i = 0; i < limit; i++) {
      if (content.charAt(i) == '\n') {
        line++;
        col = 0;
      } else {
        col++;
      }
    }
    return new Position(line, col);
  }

  static Element elementAt(final Trees trees, final TreePath path) {
    var p = path;
    while (p != null) {
      final var element = trees.getElement(p);
      if (element != null && element.getKind() != ElementKind.PACKAGE) {
        return element;
      }
      if (p.getLeaf() instanceof final MethodInvocationTree inv) {
        final var sel = trees.getElement(new TreePath(p, inv.getMethodSelect()));
        if (sel != null) {
          return sel;
        }
      }
      if (p.getLeaf() instanceof final ImportTree imp && imp.isStatic()) {
        return resolveStaticImportMember(trees, p, imp);
      }
      p = p.getParentPath();
    }
    return null;
  }

  private static Element resolveStaticImportMember(
      final Trees trees, final TreePath impPath, final ImportTree imp) {
    if (!(imp.getQualifiedIdentifier() instanceof final MemberSelectTree ms)) {
      return null;
    }
    final var msPath = new TreePath(impPath, ms);
    final var exprPath = new TreePath(msPath, ms.getExpression());
    final var typeMirror = trees.getTypeMirror(exprPath);
    if (!(typeMirror instanceof final DeclaredType dt)) {
      return null;
    }
    final var typeElem = (TypeElement) dt.asElement();
    final var memberName = ms.getIdentifier().toString();
    return typeElem.getEnclosedElements().stream()
        .filter(e -> e.getSimpleName().contentEquals(memberName))
        .findFirst()
        .orElse(null);
  }

  static VariableElement parameterElementAt(final Trees trees, final TreePath path) {
    if (path == null) {
      return null;
    }
    // Don't mask enum constants or fields — let elementAt show the value directly
    final var leafElement = trees.getElement(path);
    if (leafElement != null
        && (leafElement.getKind() == ElementKind.ENUM_CONSTANT
            || leafElement.getKind() == ElementKind.FIELD)) {
      return null;
    }
    var argPath = path;
    var parent = path.getParentPath();
    while (parent != null) {
      final var leaf = parent.getLeaf();
      if (leaf instanceof final MethodInvocationTree inv) {
        final int idx = indexIn(inv.getArguments(), argPath.getLeaf());
        if (idx < 0) {
          return null;
        }
        final var callee =
            (ExecutableElement) trees.getElement(new TreePath(parent, inv.getMethodSelect()));
        if (callee == null) {
          return null;
        }
        final var params = callee.getParameters();
        return idx < params.size() ? params.get(idx) : null;
      }
      if (leaf instanceof final NewClassTree nc) {
        final int idx = indexIn(nc.getArguments(), argPath.getLeaf());
        if (idx < 0) {
          return null;
        }
        final var callee = (ExecutableElement) trees.getElement(parent);
        if (callee == null) {
          return null;
        }
        final var params = callee.getParameters();
        return idx < params.size() ? params.get(idx) : null;
      }
      argPath = parent;
      parent = parent.getParentPath();
    }
    return null;
  }

  private static int indexIn(final List<? extends Tree> list, final Tree target) {
    return IntStream.range(0, list.size())
        .filter(i -> list.get(i) == target)
        .findFirst()
        .orElse(-1);
  }
}
