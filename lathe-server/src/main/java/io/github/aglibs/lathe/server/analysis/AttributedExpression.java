package io.github.aglibs.lathe.server.analysis;

import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import io.github.aglibs.validcheck.ValidCheck;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * A debugger expression attributed in the scope of a suspended frame (Stage 1 of expression
 * evaluation): the {@link TreePath} to the expression node, plus the javac {@link Trees}/{@link
 * Elements}/{@link Types} needed to resolve each sub-node to a symbol/type. The interpreter walks
 * {@code expression} bottom-up, bridging each resolved symbol to a JDI handle.
 */
public record AttributedExpression(
    TreePath expression, Trees trees, Elements elements, Types types) {

  public AttributedExpression {
    ValidCheck.check()
        .notNull(expression, "expression")
        .notNull(trees, "trees")
        .notNull(elements, "elements")
        .notNull(types, "types")
        .validate();
  }
}
