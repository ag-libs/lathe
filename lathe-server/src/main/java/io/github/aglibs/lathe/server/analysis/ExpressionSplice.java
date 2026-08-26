package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.Optional;

/**
 * Splices a debugger expression into a copy of the frame's source as {@code var __LATHE_EVAL__ =
 * (EXPR);} at the breakpoint line, so javac attributes it in the exact scope of the frame — every
 * local, {@code this}, field, and inherited/static member is legally visible. After attribution the
 * initializer subtree is lifted back out. This is purely a source rewrite; the spliced statement is
 * never executed.
 */
final class ExpressionSplice {

  static final String SENTINEL = "__LATHE_EVAL__";

  private ExpressionSplice() {}

  /** The frame source with {@code var __LATHE_EVAL__ = (expression);} inserted at {@code line}. */
  static String at(final String content, final int oneBasedLine, final String expression) {
    final int lineStart = SourceLocator.toOffset(content, oneBasedLine - 1, 0);
    final String statement = "var %s = (%s);".formatted(SENTINEL, expression);
    return content.substring(0, lineStart) + statement + content.substring(lineStart);
  }

  /** The attributed initializer subtree of the spliced {@code __LATHE_EVAL__} declaration. */
  static Optional<TreePath> locate(final AttributedFileAnalysis analysis) {
    if (analysis == null || analysis.tree() == null) {
      return Optional.empty();
    }

    final var finder = new InitializerFinder();
    finder.scan(analysis.tree(), null);
    return Optional.ofNullable(finder.found);
  }

  private static final class InitializerFinder extends TreePathScanner<Void, Void> {

    private TreePath found;

    @Override
    public Void visitVariable(final VariableTree node, final Void unused) {
      if (SENTINEL.contentEquals(node.getName()) && node.getInitializer() != null) {
        found = unwrap(new TreePath(getCurrentPath(), node.getInitializer()));
      }

      return super.visitVariable(node, unused);
    }

    // The expression is spliced as (EXPR) for precedence safety; lift the real node back out so it
    // carries the resolved symbol/type (a ParenthesizedTree has none).
    private static TreePath unwrap(final TreePath initializer) {
      if (initializer.getLeaf() instanceof final ParenthesizedTree paren) {
        return new TreePath(initializer, paren.getExpression());
      }

      return initializer;
    }
  }
}
