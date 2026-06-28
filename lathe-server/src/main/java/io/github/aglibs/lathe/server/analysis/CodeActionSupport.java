package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.util.TreePath;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

final class CodeActionSupport {

  private CodeActionSupport() {}

  static String typeSimpleName(final TypeMirror type) {
    if (type instanceof DeclaredType dt) {
      return ((TypeElement) dt.asElement()).getSimpleName().toString();
    }
    if (type instanceof ArrayType at) {
      final String component = typeSimpleName(at.getComponentType());
      return component != null ? component + "[]" : null;
    }

    return switch (type.getKind()) {
      case BOOLEAN, BYTE, CHAR, DOUBLE, FLOAT, INT, LONG, SHORT ->
          type.getKind().toString().toLowerCase();
      default -> null;
    };
  }

  static String typeFqn(final TypeMirror type) {
    if (type instanceof DeclaredType dt) {
      return ((TypeElement) dt.asElement()).getQualifiedName().toString();
    }
    return null;
  }

  static TreePath pathAt(
      final AttributedFileAnalysis analysis, final int line, final int character) {
    final CompilationUnitTree cu = analysis.tree();
    final long offset = SourceLocator.toOffset(cu, line, character);
    return SourceLocator.pathAt(analysis.trees(), cu, offset);
  }

  static TreePath enclosingMethod(final TreePath path) {
    TreePath current = path;
    while (current != null) {
      if (current.getLeaf() instanceof MethodTree) {
        return current;
      }

      current = current.getParentPath();
    }
    return null;
  }

  static boolean isInsideClosure(final TreePath path) {
    TreePath current = path;
    while (current != null) {
      if (current.getLeaf() instanceof LambdaExpressionTree || isAnonymousClass(current)) {
        return true;
      }

      current = current.getParentPath();
    }
    return false;
  }

  static TreePath enclosingStatementToWrap(final TreePath path) {
    TreePath current = path;
    TreePath statement = null;
    while (current != null) {
      if (current.getLeaf() instanceof StatementTree) {
        statement = current;
      }

      if (current.getLeaf() instanceof LambdaExpressionTree
          || isAnonymousClass(current)
          || current.getLeaf() instanceof MethodTree) {
        return statement;
      }

      current = current.getParentPath();
    }
    return null;
  }

  private static boolean isAnonymousClass(final TreePath path) {
    if (!(path.getLeaf() instanceof NewClassTree newClassTree)) {
      return false;
    }
    return newClassTree.getClassBody() != null;
  }
}
