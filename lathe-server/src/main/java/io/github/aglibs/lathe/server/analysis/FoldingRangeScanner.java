package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;

final class FoldingRangeScanner extends TreePathScanner<Void, Void> {

  private final Trees trees;
  private final CompilationUnitTree compilationUnit;
  private final List<FoldingRange> ranges = new ArrayList<>();

  private FoldingRangeScanner(final Trees trees, final CompilationUnitTree compilationUnit) {
    this.trees = trees;
    this.compilationUnit = compilationUnit;
  }

  static List<FoldingRange> scan(final Trees trees, final CompilationUnitTree compilationUnit) {
    final var scanner = new FoldingRangeScanner(trees, compilationUnit);
    scanner.scan(compilationUnit, null);
    return scanner.ranges;
  }

  @Override
  public Void visitCompilationUnit(final CompilationUnitTree node, final Void unused) {
    addImports(node.getImports());
    return super.visitCompilationUnit(node, unused);
  }

  @Override
  public Void visitModule(final ModuleTree node, final Void unused) {
    addRegion(node);
    return super.visitModule(node, unused);
  }

  @Override
  public Void visitClass(final ClassTree node, final Void unused) {
    addRegion(node);
    return super.visitClass(node, unused);
  }

  @Override
  public Void visitMethod(final MethodTree node, final Void unused) {
    addRegion(node);
    return super.visitMethod(node, unused);
  }

  private void addImports(final List<? extends ImportTree> imports) {
    if (imports.size() < 2) {
      return;
    }

    final var positions = trees.getSourcePositions();
    final long start = positions.getStartPosition(compilationUnit, imports.getFirst());
    final long end = positions.getEndPosition(compilationUnit, imports.getLast());
    add(start, end, FoldingRangeKind.Imports);
  }

  private void addRegion(final Tree node) {
    final var positions = trees.getSourcePositions();
    final long start = positions.getStartPosition(compilationUnit, node);
    final long end = positions.getEndPosition(compilationUnit, node);
    add(start, end, FoldingRangeKind.Region);
  }

  private void add(final long startOffset, final long endOffset, final String kind) {
    final var start = SourceLocator.offsetToPosition(compilationUnit, startOffset);
    final var end = SourceLocator.offsetToPosition(compilationUnit, endOffset);
    if (end.getLine() <= start.getLine()) {
      return;
    }

    final var range = new FoldingRange(start.getLine(), end.getLine());
    range.setStartCharacter(start.getCharacter());
    range.setEndCharacter(end.getCharacter());
    range.setKind(kind);
    ranges.add(range);
  }
}
