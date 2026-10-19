package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CaseLabelTree;
import com.sun.source.tree.Tree;

/**
 * javac tree checks whose API differs between JDK releases. This is the JDK 21 version, loaded from
 * the multi-release jar on a JDK 21+ runtime; src/main/java holds the JDK 17 version.
 */
public final class CompilerTrees {

  private CompilerTrees() {}

  // On JDK 21+ every case label is its own tree (constant, pattern, or default) under the CaseTree.
  public static boolean isCaseLabel(final Tree parent, final Tree child) {
    return parent instanceof CaseLabelTree;
  }
}
