package io.github.aglibs.lathe.server.analysis;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocSourcePositions;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.IOUtil;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;

final class FoldingRangeScanner extends TreePathScanner<Void, Void> {

  private final DocTrees trees;
  private final CompilationUnitTree compilationUnit;
  private final String content;
  private final List<FoldingRange> ranges = new ArrayList<>();

  private FoldingRangeScanner(final DocTrees trees, final CompilationUnitTree compilationUnit) {
    this.trees = trees;
    this.compilationUnit = compilationUnit;
    this.content =
        IOUtil.unchecked(() -> compilationUnit.getSourceFile().getCharContent(true).toString());
  }

  static List<FoldingRange> scan(final Trees trees, final CompilationUnitTree compilationUnit) {
    final var scanner = new FoldingRangeScanner((DocTrees) trees, compilationUnit);
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
    addJavadoc();
    addRegion(node);
    return super.visitClass(node, unused);
  }

  @Override
  public Void visitMethod(final MethodTree node, final Void unused) {
    addJavadoc();
    addRegion(node);
    return super.visitMethod(node, unused);
  }

  @Override
  public Void visitVariable(final VariableTree node, final Void unused) {
    // javac attaches doc comments only to field declarations, never to parameters or locals, so the
    // null check below naturally scopes this to fields without an explicit enclosing-kind guard.
    addJavadoc();
    return super.visitVariable(node, unused);
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

  private void addJavadoc() {
    final TreePath path = getCurrentPath();
    final DocCommentTree doc = trees.getDocCommentTree(path);
    if (doc == null) {
      return;
    }

    // DocSourcePositions reports the comment *content* span (the text after `/**`), not the
    // delimiters. javac exposes no API for the delimiter offsets, so extend the javac-anchored
    // content span outward to the enclosing `/**` and `*/` that necessarily bracket it -- a javadoc
    // body can contain neither sequence, so these lookups are unambiguous.
    final DocSourcePositions positions = trees.getSourcePositions();
    final int contentStart = (int) positions.getStartPosition(compilationUnit, doc, doc);
    final int contentEnd = (int) positions.getEndPosition(compilationUnit, doc, doc);
    final int open = content.lastIndexOf("/**", contentStart);
    final int close = content.indexOf("*/", contentEnd);
    if (open < 0 || close < 0) {
      return;
    }

    add(open, close + 2, FoldingRangeKind.Comment);
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
