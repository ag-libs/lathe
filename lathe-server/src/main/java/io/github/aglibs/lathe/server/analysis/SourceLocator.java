package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import org.eclipse.lsp4j.Position;

public final class SourceLocator {

  private SourceLocator() {}

  public static long toOffset(final CompilationUnitTree cu, final int line, final int character) {
    return cu.getLineMap().getPosition(line + 1, character + 1);
  }

  public static Position offsetToPosition(final CompilationUnitTree cu, final long offset) {
    final var lineMap = cu.getLineMap();
    return new Position(
        (int) lineMap.getLineNumber(offset) - 1, (int) lineMap.getColumnNumber(offset) - 1);
  }

  public static TreePath pathAt(
      final Trees trees, final CompilationUnitTree cu, final long offset) {
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

  public static Position offsetToPosition(final String content, final long offset) {
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

  public static Optional<Position> declarationNamePosition(
      final Trees trees, final CompilationUnitTree cu, final TreePath path, final String name)
      throws IOException {
    if (path == null) {
      return Optional.empty();
    }
    final long declStart = trees.getSourcePositions().getStartPosition(cu, path.getLeaf());
    if (declStart == Diagnostic.NOPOS) {
      return Optional.empty();
    }
    final CharSequence content = cu.getSourceFile().getCharContent(false);
    final long idx = findIdentifierFrom(content.toString(), declStart, name);
    final long nameOffset = idx >= 0 ? idx : declStart;
    return Optional.of(offsetToPosition(cu, nameOffset));
  }

  public static Element elementAt(final Trees trees, final TreePath path) {
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

  public static TreePath declarationPath(final CompilationUnitTree cu, final Element target) {
    if (target == null) {
      return null;
    }
    final var result = new AtomicReference<TreePath>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitClass(final ClassTree tree, final Void unused) {
        if ((target.getKind().isClass() || target.getKind().isInterface())
            && tree.getSimpleName().contentEquals(target.getSimpleName())) {
          result.set(getCurrentPath());
        }
        return super.visitClass(tree, unused);
      }

      @Override
      public Void visitMethod(final MethodTree tree, final Void unused) {
        if ((target.getKind() == ElementKind.METHOD || target.getKind() == ElementKind.CONSTRUCTOR)
            && tree.getName().contentEquals(declarationName(target))
            && matchParameters(
                tree.getParameters(), ((ExecutableElement) target).getParameters())) {
          result.set(getCurrentPath());
        }
        return super.visitMethod(tree, unused);
      }

      @Override
      public Void visitVariable(final VariableTree tree, final Void unused) {
        if ((target.getKind() == ElementKind.FIELD || target.getKind() == ElementKind.ENUM_CONSTANT)
            && tree.getName().contentEquals(target.getSimpleName())
            && getCurrentPath().getParentPath().getLeaf() instanceof ClassTree) {
          result.set(getCurrentPath());
        }
        return super.visitVariable(tree, unused);
      }
    }.scan(cu, null);
    return result.get();
  }

  private static boolean matchParameters(
      final List<? extends VariableTree> treeParams,
      final List<? extends VariableElement> targetParams) {
    if (treeParams.size() != targetParams.size()) {
      return false;
    }

    return IntStream.range(0, treeParams.size())
        .allMatch(
            i -> {
              final String treeType = simplifyType(treeParams.get(i).getType().toString());
              final String targetType = simplifyType(targetParams.get(i).asType().toString());
              return treeType.equals(targetType);
            });
  }

  private static String simplifyType(final String typeStr) {
    return typeStr.replaceAll("[a-zA-Z0-9_]+\\.", "").replaceAll("\\s+", "").replace("...", "[]");
  }

  public static CharSequence declarationName(final Element element) {
    return element.getKind() == ElementKind.CONSTRUCTOR
        ? element.getEnclosingElement().getSimpleName()
        : element.getSimpleName();
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

  public static VariableElement parameterElementAt(final Trees trees, final TreePath path) {
    if (path == null) {
      return null;
    }
    final var leafElement = trees.getElement(path);
    if (leafElement != null && masksParameterContext(leafElement.getKind())) {
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

  private static boolean masksParameterContext(final ElementKind kind) {
    return switch (kind) {
      case ANNOTATION_TYPE, CLASS, ENUM, ENUM_CONSTANT, FIELD, INTERFACE, RECORD -> true;
      default -> false;
    };
  }

  public static int identifierEnd(final String content, final int start) {
    int i = start;
    while (i < content.length() && Character.isJavaIdentifierPart(content.charAt(i))) {
      i++;
    }
    return i;
  }

  public static int identifierStart(final String content, final int end) {
    int i = end;
    while (i > 0 && Character.isJavaIdentifierPart(content.charAt(i - 1))) {
      i--;
    }
    return i;
  }

  public static long findIdentifierFrom(
      final String content, final long fromOffset, final String name) {
    final int nameLen = name.length();
    final int limit = content.length() - nameLen;
    for (int i = (int) fromOffset; i <= limit; i++) {
      if (!content.regionMatches(i, name, 0, nameLen)) {
        continue;
      }

      final boolean leftBound = i == 0 || !Character.isJavaIdentifierPart(content.charAt(i - 1));
      final boolean rightBound =
          i + nameLen >= content.length()
              || !Character.isJavaIdentifierPart(content.charAt(i + nameLen));
      if (leftBound && rightBound) {
        return i;
      }
    }
    return -1;
  }

  private static int indexIn(final List<? extends Tree> list, final Tree target) {
    return IntStream.range(0, list.size())
        .filter(i -> list.get(i) == target)
        .findFirst()
        .orElse(-1);
  }
}
