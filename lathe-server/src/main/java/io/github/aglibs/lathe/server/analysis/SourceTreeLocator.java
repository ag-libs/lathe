package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

abstract class SourceTreeLocator extends TreePathScanner<Void, Void> {

  protected final Trees trees;
  protected final CompilationUnitTree cu;
  protected final String content;
  protected final SourcePositions positions;
  protected final ReferenceTarget target;
  protected final Types types;
  protected final Elements elements;

  protected SourceTreeLocator(
      final Trees trees,
      final CompilationUnitTree cu,
      final String content,
      final ReferenceTarget target,
      final Types types,
      final Elements elements) {
    this.trees = trees;
    this.cu = cu;
    this.content = content;
    this.positions = trees.getSourcePositions();
    this.target = target;
    this.types = types;
    this.elements = elements;
  }

  protected static String sourceContent(final AttributedFileAnalysis analysis) throws IOException {
    return analysis.tree().getSourceFile().getCharContent(true).toString();
  }
}
