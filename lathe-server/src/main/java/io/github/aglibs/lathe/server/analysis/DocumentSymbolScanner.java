package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

final class DocumentSymbolScanner extends TreePathScanner<Void, Void> {

  private final Trees trees;
  private final CompilationUnitTree compilationUnit;
  private final String source;
  private final List<DocumentSymbol> roots = new ArrayList<>();
  private final ArrayDeque<DocumentSymbol> stack = new ArrayDeque<>();

  private DocumentSymbolScanner(
      final Trees trees, final CompilationUnitTree compilationUnit, final String source) {
    this.trees = trees;
    this.compilationUnit = compilationUnit;
    this.source = source;
  }

  static List<DocumentSymbol> scan(
      final Trees trees, final CompilationUnitTree compilationUnit, final String source) {
    final var scanner = new DocumentSymbolScanner(trees, compilationUnit, source);
    scanner.scan(compilationUnit, null);
    return scanner.roots;
  }

  @Override
  public Void visitClass(final ClassTree node, final Void unused) {
    final var symbol = symbol(node, node.getSimpleName().toString(), kind(node), false);
    add(symbol);
    stack.push(symbol);
    super.visitClass(node, unused);
    stack.pop();
    return null;
  }

  @Override
  public Void visitMethod(final MethodTree node, final Void unused) {
    final boolean constructor = node.getName().contentEquals("<init>");
    final String name =
        constructor && !stack.isEmpty() ? stack.peek().getName() : node.getName().toString();
    final var symbol =
        symbol(node, name, constructor ? SymbolKind.Constructor : SymbolKind.Method, false);
    add(symbol);
    stack.push(symbol);
    super.visitMethod(node, unused);
    stack.pop();
    return null;
  }

  @Override
  public Void visitVariable(final VariableTree node, final Void unused) {
    final var parent = getCurrentPath().getParentPath();
    if (parent != null && parent.getLeaf() instanceof ClassTree && !stack.isEmpty()) {
      add(symbol(node, node.getName().toString(), SymbolKind.Field, true));
    }

    return super.visitVariable(node, unused);
  }

  private void add(final DocumentSymbol symbol) {
    if (stack.isEmpty()) {
      roots.add(symbol);
    } else {
      final var parent = stack.peek();
      final List<DocumentSymbol> children =
          parent.getChildren() != null ? parent.getChildren() : new ArrayList<>();
      children.add(symbol);
      parent.setChildren(children);
    }
  }

  private DocumentSymbol symbol(
      final Tree node, final String name, final SymbolKind kind, final boolean useLastName) {
    final var positions = trees.getSourcePositions();
    final long start = positions.getStartPosition(compilationUnit, node);
    final long end = positions.getEndPosition(compilationUnit, node);
    return new DocumentSymbol(
        name, kind, range(start, end), selectionRange(start, end, name, useLastName));
  }

  private Range range(final long start, final long end) {
    return new Range(
        SourceLocator.offsetToPosition(compilationUnit, start),
        SourceLocator.offsetToPosition(compilationUnit, end));
  }

  private Range selectionRange(
      final long start, final long end, final String name, final boolean useLastName) {
    final long nameStart =
        useLastName
            ? SourceLocator.findLastIdentifierBetween(source, start, end, name)
            : SourceLocator.findIdentifierFrom(source, start, name);
    final long safeStart = nameStart >= 0L ? nameStart : start;
    return range(safeStart, safeStart + name.length());
  }

  private static SymbolKind kind(final ClassTree node) {
    return switch (node.getKind()) {
      case ANNOTATION_TYPE, INTERFACE -> SymbolKind.Interface;
      case ENUM -> SymbolKind.Enum;
      case RECORD -> SymbolKind.Struct;
      default -> SymbolKind.Class;
    };
  }
}
