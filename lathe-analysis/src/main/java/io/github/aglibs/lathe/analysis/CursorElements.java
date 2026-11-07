package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
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
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;

// The tree path and element at a cursor: the element a position names, an enum constant, the
// constructor of `new X()`, the parameter an argument binds to, statically imported members.
public final class CursorElements {

  private CursorElements() {}

  public static TreePath pathAt(
      final Trees trees, final CompilationUnitTree cu, final long offset) {
    final var positions = trees.getSourcePositions();
    final var result = new AtomicReference<TreePath>();
    final var size = new AtomicLong(Long.MAX_VALUE);

    new TreePathScanner<Void, Void>() {
      @Override
      public Void scan(final Tree tree, final Void unused) {
        if (tree == null || GeneratedCode.isGenerated(tree)) {
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

    return enumConstantAt(trees, cu, result.get(), offset);
  }

  // A constant with a body, `RED { … }`, is a variable initialised by `new Color() { … }`, whose
  // anonymous class tree javac starts at the constant's name; on the name, before the `new`
  // itself, the tree meant is the constant.
  private static TreePath enumConstantAt(
      final Trees trees, final CompilationUnitTree cu, final TreePath path, final long offset) {
    final TreePath creation =
        path != null && path.getLeaf() instanceof ClassTree ? path.getParentPath() : null;
    if (creation == null
        || !(creation.getLeaf() instanceof NewClassTree)
        || !(creation.getParentPath().getLeaf() instanceof VariableTree)
        || offset >= trees.getSourcePositions().getStartPosition(cu, creation.getLeaf())) {
      return path;
    }

    final var element = trees.getElement(creation.getParentPath());
    return element != null && element.getKind() == ElementKind.ENUM_CONSTANT
        ? creation.getParentPath()
        : path;
  }

  public static Element elementAt(final Trees trees, final TreePath path) {
    TreePath p = path;
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

  /**
   * The invoked constructor when {@code path} sits on the type-name of a {@code new XXX(...)}
   * expression, else null. {@link #elementAt} resolves that identifier to the type element (all its
   * uses), so references/definition callers consult this first to prefer the constructor's call
   * sites. Returns null when the cursor is on the arguments or class body of the {@code new}, or
   * when the enclosing element is not a constructor.
   */
  static Element constructorAtNewClassType(final Trees trees, final TreePath path) {
    TreePath child = null;
    for (TreePath p = path; p != null; p = p.getParentPath()) {
      if (p.getLeaf() instanceof final NewClassTree nc) {
        if (child == null || child.getLeaf() != nc.getIdentifier()) {
          return null;
        }

        final var element = trees.getElement(p);
        return element != null && element.getKind() == ElementKind.CONSTRUCTOR ? element : null;
      }

      child = p;
    }

    return null;
  }

  private static Element resolveStaticImportMember(
      final Trees trees, final TreePath impPath, final ImportTree imp) {
    return staticImportMembers(trees, impPath, imp).stream().findFirst().orElse(null);
  }

  // Every member a single static import names: overloads, and a field of the same name, alike.
  public static List<Element> staticImportMembers(
      final Trees trees, final TreePath impPath, final ImportTree imp) {
    if (!(imp.getQualifiedIdentifier() instanceof final MemberSelectTree ms)) {
      return List.of();
    }
    final var msPath = new TreePath(impPath, ms);
    final var exprPath = new TreePath(msPath, ms.getExpression());
    if (!(trees.getTypeMirror(exprPath) instanceof final DeclaredType dt)) {
      return List.of();
    }
    final var memberName = ms.getIdentifier().toString();
    return dt.asElement().getEnclosedElements().stream()
        .filter(e -> e.getSimpleName().contentEquals(memberName))
        .map(Element.class::cast)
        .toList();
  }

  public static VariableElement parameterElementAt(final Trees trees, final TreePath path) {
    if (path == null) {
      return null;
    }
    final var leafElement = trees.getElement(path);
    if (leafElement != null && masksParameterContext(leafElement)) {
      return null;
    }
    TreePath argPath = path;
    TreePath parent = path.getParentPath();
    while (parent != null) {
      // Inside a method reference the cursor names its own symbol (the receiver of `x::m`, or m),
      // not the argument value the callee parameter describes.
      if (argPath.getLeaf() instanceof MemberReferenceTree) {
        return null;
      }

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

  /**
   * Suppresses the argument param-hint when the cursor's own element is a named symbol worth
   * showing on its own — types, fields, enum constants, and a record component referenced inside
   * the compact constructor. javac models that component reference as the canonical-constructor
   * {@code PARAMETER}, so without this it would show the callee's parameter instead of the
   * component (as a backing-field read elsewhere in the record body already does).
   */
  private static boolean masksParameterContext(final Element element) {
    return switch (element.getKind()) {
      case ANNOTATION_TYPE, CLASS, ENUM, ENUM_CONSTANT, FIELD, INTERFACE, RECORD -> true;
      case PARAMETER -> isRecordComponentParameter(element);
      default -> false;
    };
  }

  private static boolean isRecordComponentParameter(final Element parameter) {
    final var constructor = parameter.getEnclosingElement();
    if (constructor.getKind() != ElementKind.CONSTRUCTOR) {
      return false;
    }

    final var owner = constructor.getEnclosingElement();
    if (owner.getKind() != ElementKind.RECORD) {
      return false;
    }

    final List<? extends RecordComponentElement> components =
        ((TypeElement) owner).getRecordComponents();
    final List<? extends VariableElement> params =
        ((ExecutableElement) constructor).getParameters();
    return params.size() == components.size()
        && IntStream.range(0, params.size())
            .allMatch(
                i ->
                    params.get(i).getSimpleName().contentEquals(components.get(i).getSimpleName()));
  }

  private static int indexIn(final List<? extends Tree> list, final Tree target) {
    return IntStream.range(0, list.size())
        .filter(i -> list.get(i) == target)
        .findFirst()
        .orElse(-1);
  }
}
