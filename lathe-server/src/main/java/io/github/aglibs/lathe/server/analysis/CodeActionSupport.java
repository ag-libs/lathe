package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.TextEdit;

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

  // An import edit for the fully-qualified name, or null when none is needed (already imported,
  // java.lang, same package) or the name is null. Shared by the code-action providers.
  static TextEdit importEditFor(final AttributedFileAnalysis analysis, final String fqn) {
    return fqn == null ? null : new ImportAnalyzer(analysis).importEdit(fqn);
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

  static TreePath enclosingClass(final TreePath path) {
    TreePath current = path;
    while (current != null) {
      if (current.getLeaf() instanceof ClassTree) {
        return current;
      }

      current = current.getParentPath();
    }
    return null;
  }

  // The variable declaration enclosing the cursor, bounded at the method/class boundary so a caret
  // in a method body never climbs to a field. Null when no declaration encloses the path.
  static TreePath enclosingVariable(final TreePath path) {
    TreePath current = path;
    while (current != null) {
      if (current.getLeaf() instanceof VariableTree) {
        return current;
      }

      if (current.getLeaf() instanceof MethodTree || current.getLeaf() instanceof ClassTree) {
        return null;
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

  // The innermost enclosing StatementTree, bounded at a method / lambda / anonymous-class boundary
  // (an expression that reaches such a boundary before any statement cannot receive a preceding
  // declaration, e.g. an expression-bodied lambda). Null when no statement encloses the path.
  static TreePath nearestEnclosingStatement(final TreePath path) {
    TreePath current = path;
    while (current != null) {
      if (current.getLeaf() instanceof StatementTree) {
        return current;
      }

      if (current.getLeaf() instanceof LambdaExpressionTree
          || isAnonymousClass(current)
          || current.getLeaf() instanceof MethodTree) {
        return null;
      }

      current = current.getParentPath();
    }
    return null;
  }

  // The leading whitespace of the line containing `offset` — the indentation an inserted line must
  // reproduce. Shared by the code-action providers that splice new lines.
  static String lineIndent(final String source, final int offset) {
    int lineStart = offset;
    while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
      lineStart--;
    }

    int indentEnd = lineStart;
    while (indentEnd < source.length() && Character.isWhitespace(source.charAt(indentEnd))) {
      if (source.charAt(indentEnd) == '\n') {
        break;
      }

      indentEnd++;
    }
    return source.substring(lineStart, indentEnd);
  }

  // One indent level as written in this file (code-action requests carry no formatting settings):
  // the statement's indent minus its block owner's indent. Falls back to two spaces.
  static String indentUnit(
      final String source,
      final TreePath statementPath,
      final CompilationUnitTree cu,
      final SourcePositions positions) {
    final long stmtStart = positions.getStartPosition(cu, statementPath.getLeaf());
    final TreePath ownerPath =
        statementPath.getParentPath() == null
            ? null
            : statementPath.getParentPath().getParentPath();
    if (stmtStart < 0 || ownerPath == null) {
      return "  ";
    }

    final long ownerStart = positions.getStartPosition(cu, ownerPath.getLeaf());
    if (ownerStart < 0) {
      return "  ";
    }

    final String stmtIndent = lineIndent(source, (int) stmtStart);
    final String ownerIndent = lineIndent(source, (int) ownerStart);
    return stmtIndent.startsWith(ownerIndent) && stmtIndent.length() > ownerIndent.length()
        ? stmtIndent.substring(ownerIndent.length())
        : "  ";
  }

  // Indent a block whose first line has no leading indent so it sits one level (step) inside
  // `indent`; later lines keep their own nesting, shifted by one step.
  static String reindent(final String block, final String indent, final String step) {
    final String[] lines = block.split("\n", -1);
    lines[0] = indent + step + lines[0];
    for (int i = 1; i < lines.length; i++) {
      lines[i] = lines[i].isBlank() ? "" : step + lines[i];
    }

    return String.join("\n", lines);
  }

  // Only kinds that can be written explicitly qualify: `var`/inferred types can be anonymous,
  // intersection, or captured type variables that have no source spelling.
  static boolean isDenotable(final TypeMirror type) {
    return switch (type.getKind()) {
      case DECLARED -> !isAnonymous((DeclaredType) type);
      case ARRAY, BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE -> true;
      default -> false;
    };
  }

  private static boolean isAnonymous(final DeclaredType type) {
    return type.asElement() instanceof final TypeElement te
        && te.getNestingKind() == NestingKind.ANONYMOUS;
  }

  private static boolean isAnonymousClass(final TreePath path) {
    if (!(path.getLeaf() instanceof NewClassTree newClassTree)) {
      return false;
    }
    return newClassTree.getClassBody() != null;
  }
}
