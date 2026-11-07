package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.util.function.Predicate;

// Walks from a tree path up to its enclosing trees. Null for none, as javac's TreePath has it.
public final class TreePaths {

  private TreePaths() {}

  // The nearest tree of the kind: the path itself or an ancestor.
  public static TreePath nearest(final TreePath path, final Class<? extends Tree> kind) {
    return nearest(path, kind, ignored -> false);
  }

  // As nearest(path, kind), but none once a tree the boundary matches comes first.
  public static TreePath nearest(
      final TreePath path, final Class<? extends Tree> kind, final Predicate<TreePath> boundary) {
    for (TreePath current = path; current != null; current = current.getParentPath()) {
      if (kind.isInstance(current.getLeaf())) {
        return current;
      }

      if (boundary.test(current)) {
        return null;
      }
    }

    return null;
  }

  public static TreePath enclosingMethod(final TreePath path) {
    return nearest(path, MethodTree.class);
  }

  public static TreePath enclosingClass(final TreePath path) {
    return nearest(path, ClassTree.class);
  }

  // The variable declaration enclosing the cursor, bounded at the method/class boundary so a caret
  // in a method body never climbs to a field.
  public static TreePath enclosingVariable(final TreePath path) {
    return nearest(path, VariableTree.class, TreePaths::isMemberBoundary);
  }

  // The innermost enclosing statement, bounded at a method / lambda / anonymous-class boundary (an
  // expression that reaches such a boundary before any statement cannot receive a preceding
  // declaration, e.g. an expression-bodied lambda).
  public static TreePath nearestEnclosingStatement(final TreePath path) {
    return nearest(path, StatementTree.class, TreePaths::isBodyBoundary);
  }

  // The expression statement enclosing the path, within its method or class.
  public static TreePath enclosingExpressionStatement(final TreePath path) {
    return nearest(path, ExpressionStatementTree.class, TreePaths::isMemberBoundary);
  }

  // A method or a class: where a member's body ends.
  public static boolean isMemberBoundary(final TreePath path) {
    return path.getLeaf() instanceof MethodTree || path.getLeaf() instanceof ClassTree;
  }

  // A method, a lambda or an anonymous class: where a body that runs on its own ends.
  public static boolean isBodyBoundary(final TreePath path) {
    return path.getLeaf() instanceof LambdaExpressionTree
        || isAnonymousClass(path)
        || path.getLeaf() instanceof MethodTree;
  }

  // An anonymous class creation: `new T() { ... }`.
  public static boolean isAnonymousClass(final TreePath path) {
    return path.getLeaf() instanceof final NewClassTree newClass && newClass.getClassBody() != null;
  }

  static ModifiersTree modifiersOf(final Tree declaration) {
    if (declaration instanceof final VariableTree variable) {
      return variable.getModifiers();
    }

    if (declaration instanceof final MethodTree method) {
      return method.getModifiers();
    }

    return declaration instanceof final ClassTree type ? type.getModifiers() : null;
  }
}
