package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CaseTree;
import com.sun.source.tree.Tree;

/**
 * javac tree checks whose API differs between JDK releases. This is the JDK 17 version; the
 * multi-release jar carries a JDK 21 version (src/main/java21) that a JDK 21+ runtime loads
 * instead.
 */
public final class CompilerTrees {

  private CompilerTrees() {}

  // On JDK 17 a case-label constant is a direct child of its CaseTree.
  public static boolean isCaseLabel(final Tree parent, final Tree child) {
    return parent instanceof final CaseTree caseTree && caseTree.getExpressions().contains(child);
  }
}
