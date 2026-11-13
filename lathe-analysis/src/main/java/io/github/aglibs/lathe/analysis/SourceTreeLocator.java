package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.io.IOException;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

abstract class SourceTreeLocator extends WrittenSourceScanner {

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

  // The offset of `name` as written at `node`, or -1. Found by text from a reliable anchor instead
  // of end-minus-name.length(): a node's end position can be approximate in generated sources,
  // slicing the range onto a neighbouring identifier. Anchoring on the receiver's end also skips
  // the name inside the receiver (`amount.amount()`, `b.add(1).add(2)`).
  protected long nameStart(final Tree node, final String name) {
    final long anchor = nameAnchor(node);
    if (anchor < 0) {
      return -1;
    }

    return DeclarationNames.findIdentifierFrom(content, anchor, name);
  }

  private long nameAnchor(final Tree node) {
    if (node instanceof final MemberSelectTree ms) {
      return positions.getEndPosition(cu, ms.getExpression());
    }

    if (node instanceof final MemberReferenceTree mr) {
      return positions.getEndPosition(cu, mr.getQualifierExpression());
    }

    return positions.getStartPosition(cu, node);
  }

  protected static String sourceContent(final AttributedFileAnalysis analysis) throws IOException {
    return analysis.tree().getSourceFile().getCharContent(true).toString();
  }
}
