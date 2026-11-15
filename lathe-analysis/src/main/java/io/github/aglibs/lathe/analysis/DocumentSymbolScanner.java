package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

final class DocumentSymbolScanner extends WrittenSourceScanner {

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
  public Void visitCompilationUnit(final CompilationUnitTree node, final Void unused) {
    if (node.getModule() != null) {
      return super.visitCompilationUnit(node, unused);
    }

    if (node.getPackageName() != null && node.getTypeDecls().isEmpty()) {
      final String name = node.getPackageName().toString();
      add(symbol(node.getPackageName(), name, SymbolKind.Package));
      return null;
    }

    return super.visitCompilationUnit(node, unused);
  }

  @Override
  public Void visitModule(final ModuleTree node, final Void unused) {
    final String name = node.getName().toString();
    add(symbol(node, name, SymbolKind.Module));
    return null;
  }

  @Override
  public Void visitClass(final ClassTree node, final Void unused) {
    final var name = node.getSimpleName().toString();
    if (name.isEmpty()) {
      // Anonymous class: javac gives it an empty simple name, which is not a valid LSP symbol name
      // (VS Code rejects a blank DocumentSymbol name). An enum constant's body lists its members
      // under the constant; any other is left out with its members.
      return isConstantBody() ? super.visitClass(node, unused) : null;
    }

    final var symbol = symbol(node, name, kind(node));
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
    final var symbol = symbol(node, name, constructor ? SymbolKind.Constructor : SymbolKind.Method);
    add(symbol);
    stack.push(symbol);
    super.visitMethod(node, unused);
    stack.pop();
    return null;
  }

  @Override
  public Void visitVariable(final VariableTree node, final Void unused) {
    final var parent = getCurrentPath().getParentPath();
    if (parent == null || !(parent.getLeaf() instanceof final ClassTree owner) || stack.isEmpty()) {
      return super.visitVariable(node, unused);
    }

    if (!isEnumConstant(node, owner)) {
      add(symbol(node, node.getName().toString(), SymbolKind.Field));
      return super.visitVariable(node, unused);
    }

    final var constant = symbol(node, node.getName().toString(), SymbolKind.EnumMember);
    add(constant);
    stack.push(constant);
    super.visitVariable(node, unused);
    stack.pop();
    return null;
  }

  // javac gives every enum constant the initializer new E(...), with E the enum itself; no other
  // field of an enum can create one.
  private static boolean isEnumConstant(final VariableTree node, final ClassTree owner) {
    return owner.getKind() == Tree.Kind.ENUM
        && node.getInitializer() instanceof final NewClassTree creation
        && creation.getIdentifier().toString().contentEquals(owner.getSimpleName());
  }

  // The anonymous class being visited is an enum constant's body: new E(...) { ... } initializing
  // a constant.
  private boolean isConstantBody() {
    final TreePath creation = getCurrentPath().getParentPath();
    final TreePath variable = creation == null ? null : creation.getParentPath();
    final TreePath owner = variable == null ? null : variable.getParentPath();
    return owner != null
        && variable.getLeaf() instanceof final VariableTree constant
        && owner.getLeaf() instanceof final ClassTree enumTree
        && isEnumConstant(constant, enumTree)
        && constant.getInitializer() == creation.getLeaf();
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

  private DocumentSymbol symbol(final Tree node, final String name, final SymbolKind kind) {
    final var positions = trees.getSourcePositions();
    final long start = positions.getStartPosition(compilationUnit, node);
    final long end = positions.getEndPosition(compilationUnit, node);
    final long nameStart =
        DeclarationNames.declarationNameOffset(source, positions, compilationUnit, node, name);
    final long safeStart = nameStart >= 0L ? nameStart : start;
    return new DocumentSymbol(
        name, kind, range(start, end), range(safeStart, safeStart + name.length()));
  }

  private Range range(final long start, final long end) {
    return new Range(
        Positions.offsetToPosition(compilationUnit, start),
        Positions.offsetToPosition(compilationUnit, end));
  }

  private static SymbolKind kind(final ClassTree node) {
    return SymbolKinds.fromTree(node.getKind());
  }
}
