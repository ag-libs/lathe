package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssertTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// Shared machinery for the expression-extraction refactors (extract variable / constant / field):
// resolving the covering expression from a selection, finding structurally + semantically equal
// occurrences, and assembling the WorkspaceEdit. Placement, naming, and eligibility are each
// refactor's own concern.
public final class ExtractionSupport {

  private static final Logger LOG = Logger.getLogger(ExtractionSupport.class.getName());

  // CodeActionKind sub-kinds for the three extract refactors. Distinct sub-kinds let an editor bind
  // each refactor to its own shortcut (an `only` filter that matches one kind). Advertised by the
  // server capabilities and set on every action these providers emit — declared here so both sides
  // share one source of truth.
  public static final String VARIABLE_KIND = "refactor.extract.variable";
  public static final String CONSTANT_KIND = "refactor.extract.constant";
  public static final String FIELD_KIND = "refactor.extract.field";
  public static final String METHOD_KIND = "refactor.extract.method";
  public static final List<String> KINDS =
      List.of(VARIABLE_KIND, CONSTANT_KIND, FIELD_KIND, METHOD_KIND);

  private ExtractionSupport() {}

  // The expression a selection means, or null. A non-empty selection takes the one outermost tree
  // inside it, so the indentation, line breaks and `;` around an expression don't matter (a
  // line-wise selection includes them); a selected statement means its value. Otherwise, as for a
  // caret, the smallest expression covering the selection.
  static TreePath selectedExpression(
      final Trees trees, final CompilationUnitTree cu, final Range selection) {
    final long start =
        SourceLocator.toOffset(
            cu, selection.getStart().getLine(), selection.getStart().getCharacter());
    final long end =
        SourceLocator.toOffset(cu, selection.getEnd().getLine(), selection.getEnd().getCharacter());
    if (start < 0 || end < 0) {
      return null;
    }

    final TreePath spanned = start < end ? soleTreeWithin(trees, cu, start, end) : null;
    if (spanned == null) {
      return coveringExpression(trees, cu, SourceLocator.pathAt(trees, cu, start), start, end);
    }

    final TreePath value = valueOf(spanned);
    if (value == null) {
      return null;
    }

    final var positions = trees.getSourcePositions();
    return coveringExpression(
        trees,
        cu,
        value,
        positions.getStartPosition(cu, value.getLeaf()),
        positions.getEndPosition(cu, value.getLeaf()));
  }

  // The single outermost tree whose span lies inside [start, end], or null when none or several do.
  private static TreePath soleTreeWithin(
      final Trees trees, final CompilationUnitTree cu, final long start, final long end) {
    final var positions = trees.getSourcePositions();
    final var within = new ArrayList<TreePath>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void scan(final Tree tree, final Void unused) {
        if (tree == null) {
          return null;
        }

        final long s = positions.getStartPosition(cu, tree);
        final long e = positions.getEndPosition(cu, tree);
        if (s >= start && e <= end) {
          within.add(new TreePath(getCurrentPath(), tree));
          return null;
        }

        if (s >= 0 && (e <= start || s >= end)) {
          return null;
        }

        return super.scan(tree, unused);
      }
    }.scan(new TreePath(cu), null);
    return within.size() == 1 ? within.get(0) : null;
  }

  private static TreePath valueOf(final TreePath path) {
    final Tree leaf = path.getLeaf();
    if (leaf instanceof ExpressionTree) {
      return path;
    }

    if (leaf instanceof final ExpressionStatementTree statement) {
      return child(path, statement.getExpression());
    }

    if (leaf instanceof final VariableTree variable && variable.getInitializer() != null) {
      return child(path, variable.getInitializer());
    }

    if (leaf instanceof final ReturnTree ret && ret.getExpression() != null) {
      return child(path, ret.getExpression());
    }

    return null;
  }

  // The smallest ExpressionTree on the path from `from` whose source span covers [start, end].
  private static TreePath coveringExpression(
      final Trees trees,
      final CompilationUnitTree cu,
      final TreePath from,
      final long start,
      final long end) {
    final var positions = trees.getSourcePositions();
    for (TreePath p = from; p != null; p = p.getParentPath()) {
      if (!(p.getLeaf() instanceof ExpressionTree)
          || isMethodSelect(p)
          || isTypeReference(p, trees)) {
        continue;
      }

      final long s = positions.getStartPosition(cu, p.getLeaf());
      final long e = positions.getEndPosition(cu, p.getLeaf());
      if (s >= 0 && s <= start && e >= end) {
        return p;
      }
    }
    return null;
  }

  // The name part of a call (the `compute` in `compute(2)`, the `a.b` in `a.b()`) is not a value
  // expression on its own; a caret there should extract the whole invocation, not the callee name.
  private static boolean isMethodSelect(final TreePath path) {
    return path.getParentPath() != null
        && path.getParentPath().getLeaf() instanceof final MethodInvocationTree inv
        && inv.getMethodSelect() == path.getLeaf();
  }

  // A type/package qualifier is not a value; skipping it lets a caret climb to the real expression
  // instead of extracting the type reference (which would produce `Factory f = Factory;`).
  private static boolean isTypeReference(final TreePath path, final Trees trees) {
    final Element element = trees.getElement(path);
    return element instanceof TypeElement
        || (element != null && element.getKind() == ElementKind.PACKAGE);
  }

  // Every expression under `scopePath` structurally and semantically equal to the selection
  // (including the selection itself), so N >= 2 means the same value is computed more than once.
  static List<TreePath> occurrences(
      final TreePath scopePath, final TreePath selectedPath, final Trees trees) {
    final var positions = trees.getSourcePositions();
    final CompilationUnitTree cu = scopePath.getCompilationUnit();
    final var result = new ArrayList<TreePath>();
    final var skipped = new AtomicInteger();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void scan(final Tree node, final Void unused) {
        collect(node);
        return super.scan(node, unused);
      }

      private void collect(final Tree node) {
        if (!(node instanceof ExpressionTree)) {
          return;
        }

        final var candidate = new TreePath(getCurrentPath(), node);
        // An assignment's target is a place to store into, not a value: never an occurrence.
        if (!equivalent(selectedPath, candidate, trees) || isAssignmentTarget(candidate)) {
          return;
        }

        // A synthesized expression (records, enums, generated members) has no source span (NOPOS),
        // so there is nothing to rewrite; drop it and keep only real source occurrences.
        if (positions.getStartPosition(cu, node) < 0 || positions.getEndPosition(cu, node) < 0) {
          skipped.incrementAndGet();
          return;
        }

        result.add(candidate);
      }
    }.scan(scopePath, null);
    if (skipped.get() > 0) {
      final int dropped = skipped.get();
      LOG.fine(() -> "[extract] occurrences skipped=%d synthetic".formatted(dropped));
    }

    return result;
  }

  // Structural equality (same kind, operator, literal value, child shape) plus semantic equality:
  // every identifier / member-select must resolve to the same Element, which is what distinguishes
  // `a.x` from `b.x`. Unsupported kinds are conservatively unequal, so exotic expressions simply do
  // not gain a replace-all action.
  private static boolean equivalent(final TreePath a, final TreePath b, final Trees trees) {
    final Tree ta = a.getLeaf();
    final Tree tb = b.getLeaf();
    if (ta.getKind() != tb.getKind()) {
      return false;
    }

    if (ta instanceof IdentifierTree) {
      return sameElement(a, b, trees);
    }

    if (ta instanceof final LiteralTree la) {
      return Objects.equals(la.getValue(), ((LiteralTree) tb).getValue());
    }

    if (ta instanceof final ParenthesizedTree pa) {
      return equivalent(
          child(a, pa.getExpression()), child(b, ((ParenthesizedTree) tb).getExpression()), trees);
    }

    if (ta instanceof final MemberSelectTree ma) {
      final var mb = (MemberSelectTree) tb;
      return ma.getIdentifier().contentEquals(mb.getIdentifier())
          && sameElement(a, b, trees)
          && equivalent(child(a, ma.getExpression()), child(b, mb.getExpression()), trees);
    }

    if (ta instanceof final MethodInvocationTree ia) {
      final var ib = (MethodInvocationTree) tb;
      return ia.getTypeArguments().isEmpty()
          && ib.getTypeArguments().isEmpty()
          && equivalent(child(a, ia.getMethodSelect()), child(b, ib.getMethodSelect()), trees)
          && childrenEquivalent(a, ia.getArguments(), b, ib.getArguments(), trees);
    }

    if (ta instanceof final BinaryTree ba) {
      final var bb = (BinaryTree) tb;
      return equivalent(child(a, ba.getLeftOperand()), child(b, bb.getLeftOperand()), trees)
          && equivalent(child(a, ba.getRightOperand()), child(b, bb.getRightOperand()), trees);
    }

    if (ta instanceof final UnaryTree ua && isPureUnary(ta.getKind())) {
      return equivalent(
          child(a, ua.getExpression()), child(b, ((UnaryTree) tb).getExpression()), trees);
    }

    if (ta instanceof final NewClassTree na) {
      final var nb = (NewClassTree) tb;
      return na.getClassBody() == null
          && nb.getClassBody() == null
          && na.getTypeArguments().isEmpty()
          && nb.getTypeArguments().isEmpty()
          && sameElement(a, b, trees)
          && childrenEquivalent(a, na.getArguments(), b, nb.getArguments(), trees);
    }

    if (ta instanceof final ArrayAccessTree aa) {
      final var ab = (ArrayAccessTree) tb;
      return equivalent(child(a, aa.getExpression()), child(b, ab.getExpression()), trees)
          && equivalent(child(a, aa.getIndex()), child(b, ab.getIndex()), trees);
    }

    if (ta instanceof final ConditionalExpressionTree ca) {
      final var cb = (ConditionalExpressionTree) tb;
      return equivalent(child(a, ca.getCondition()), child(b, cb.getCondition()), trees)
          && equivalent(child(a, ca.getTrueExpression()), child(b, cb.getTrueExpression()), trees)
          && equivalent(
              child(a, ca.getFalseExpression()), child(b, cb.getFalseExpression()), trees);
    }

    if (ta instanceof final TypeCastTree ka) {
      final var kb = (TypeCastTree) tb;
      return ka.getType().toString().equals(kb.getType().toString())
          && equivalent(child(a, ka.getExpression()), child(b, kb.getExpression()), trees);
    }

    return false;
  }

  private static boolean childrenEquivalent(
      final TreePath a,
      final List<? extends ExpressionTree> as,
      final TreePath b,
      final List<? extends ExpressionTree> bs,
      final Trees trees) {
    if (as.size() != bs.size()) {
      return false;
    }

    return IntStream.range(0, as.size())
        .allMatch(i -> equivalent(child(a, as.get(i)), child(b, bs.get(i)), trees));
  }

  private static TreePath child(final TreePath parent, final Tree node) {
    return new TreePath(parent, node);
  }

  private static boolean sameElement(final TreePath a, final TreePath b, final Trees trees) {
    final Element ea = trees.getElement(a);
    return ea != null && ea.equals(trees.getElement(b));
  }

  // A compile-time constant (JLS 15.29), in the safe subset: literals, constant variables, and the
  // operators, parentheses and casts over them.
  static boolean isCompileTimeConstant(final TreePath path, final Trees trees) {
    final Tree node = path.getLeaf();

    if (node instanceof final LiteralTree literal) {
      return literal.getValue() != null; // the `null` literal is not a constant expression
    }

    if (node instanceof final ParenthesizedTree p) {
      return isCompileTimeConstant(child(path, p.getExpression()), trees);
    }

    if (node instanceof final UnaryTree u && isPureUnary(u.getKind())) {
      return isCompileTimeConstant(child(path, u.getExpression()), trees);
    }

    if (node instanceof final BinaryTree b) {
      return isCompileTimeConstant(child(path, b.getLeftOperand()), trees)
          && isCompileTimeConstant(child(path, b.getRightOperand()), trees);
    }

    if (node instanceof final ConditionalExpressionTree c) {
      return isCompileTimeConstant(child(path, c.getCondition()), trees)
          && isCompileTimeConstant(child(path, c.getTrueExpression()), trees)
          && isCompileTimeConstant(child(path, c.getFalseExpression()), trees);
    }

    if (node instanceof final TypeCastTree cast) {
      return isCompileTimeConstant(child(path, cast.getExpression()), trees);
    }

    if (node instanceof IdentifierTree || node instanceof MemberSelectTree) {
      return trees.getElement(path) instanceof final VariableElement variable
          && variable.getConstantValue() != null;
    }

    return false;
  }

  private static boolean isPureUnary(final Tree.Kind kind) {
    return kind == Tree.Kind.UNARY_PLUS
        || kind == Tree.Kind.UNARY_MINUS
        || kind == Tree.Kind.LOGICAL_COMPLEMENT
        || kind == Tree.Kind.BITWISE_COMPLEMENT;
  }

  // ── edit + action assembly ─────────────────────────────────────────────────────────────────

  static TextEdit replaceEdit(
      final CompilationUnitTree cu, final long start, final long end, final String name) {
    return new TextEdit(
        new Range(
            SourceLocator.offsetToPosition(cu, start), SourceLocator.offsetToPosition(cu, end)),
        name);
  }

  static List<TextEdit> editList(
      final TextEdit insert, final List<TextEdit> replaces, final TextEdit importEdit) {
    final var edits = new ArrayList<TextEdit>();
    edits.add(insert);
    edits.addAll(replaces);
    if (importEdit != null) {
      edits.add(importEdit);
    }
    return edits;
  }

  static Either<Command, CodeAction> action(
      final String uri, final String title, final String kind, final List<TextEdit> edits) {
    final var action = new CodeAction();
    action.setTitle(title);
    action.setKind(kind);
    final var workspaceEdit = new WorkspaceEdit();
    workspaceEdit.setChanges(Map.of(uri, edits));
    action.setEdit(workspaceEdit);
    return Either.forRight(action);
  }

  // ── read-set / value-stability (shared by the value + field replace-all safety gates) ────────

  // True when the expression cannot be computed before `statement`, an ancestor: it would run where
  // today it may not (the right of && or ||, a ?: branch, an if/case/catch branch, a loop body, an
  // assert), once instead of on every iteration (a loop condition), ahead of this(...)/super(...),
  // or before a variable the statement declares (a pattern binding, a for or try variable).
  static boolean pinnedInPlace(final TreePath expr, final Tree statement, final Trees trees) {
    TreePath path = expr;
    while (path.getLeaf() != statement) {
      final TreePath parent = path.getParentPath();
      if (parent == null || evaluatedOnlyInPlace(parent.getLeaf(), path.getLeaf())) {
        return true;
      }

      path = parent;
    }

    return isConstructorCall(path, trees) || declaresAny(path, readVariables(expr, trees), trees);
  }

  private static boolean evaluatedOnlyInPlace(final Tree parent, final Tree child) {
    if (parent instanceof final BinaryTree binary) {
      return child == binary.getRightOperand()
          && (binary.getKind() == Tree.Kind.CONDITIONAL_AND
              || binary.getKind() == Tree.Kind.CONDITIONAL_OR);
    }

    if (parent instanceof final ConditionalExpressionTree conditional) {
      return child != conditional.getCondition();
    }

    if (parent instanceof final IfTree ifTree) {
      return child != ifTree.getCondition();
    }

    if (parent instanceof final SwitchTree switchTree) {
      return child != switchTree.getExpression();
    }

    if (parent instanceof final SwitchExpressionTree switchExpression) {
      return child != switchExpression.getExpression();
    }

    if (parent instanceof final EnhancedForLoopTree forEach) {
      return child != forEach.getExpression();
    }

    if (parent instanceof final ForLoopTree forLoop) {
      return !forLoop.getInitializer().contains(child);
    }

    return parent instanceof WhileLoopTree
        || parent instanceof DoWhileLoopTree
        || parent instanceof CatchTree
        || parent instanceof AssertTree;
  }

  // this(...) or super(...): nothing may precede it (before Java 25), and only these invocations
  // resolve to a constructor.
  static boolean isConstructorCall(final TreePath statement, final Trees trees) {
    if (!(statement.getLeaf() instanceof final ExpressionStatementTree call)
        || !(call.getExpression() instanceof MethodInvocationTree)) {
      return false;
    }

    final Element element = trees.getElement(child(statement, call.getExpression()));
    return element != null && element.getKind() == ElementKind.CONSTRUCTOR;
  }

  private static boolean declaresAny(
      final TreePath statement, final Set<Element> variables, final Trees trees) {
    final var found = new AtomicBoolean();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitVariable(final VariableTree tree, final Void unused) {
        if (variables.contains(trees.getElement(getCurrentPath()))) {
          found.set(true);
        }

        return super.visitVariable(tree, unused);
      }
    }.scan(statement, null);
    return found.get();
  }

  // The local variables, parameters, and fields the selected expression reads.
  static Set<Element> readVariables(final TreePath selectedPath, final Trees trees) {
    final var reads = new HashSet<Element>();
    addIfVariable(reads, selectedPath, trees);
    new TreePathScanner<Void, Void>() {
      @Override
      public Void scan(final Tree node, final Void unused) {
        if (node instanceof IdentifierTree || node instanceof MemberSelectTree) {
          addIfVariable(reads, new TreePath(getCurrentPath(), node), trees);
        }
        return super.scan(node, unused);
      }
    }.scan(selectedPath, null);
    return reads;
  }

  private static void addIfVariable(
      final Set<Element> reads, final TreePath path, final Trees trees) {
    final Element element = trees.getElement(path);
    if (element == null) {
      return;
    }

    if (isLocalVariable(element) || element.getKind() == ElementKind.FIELD) {
      reads.add(element);
    }
  }

  // A variable that lives in a method's frame: a local, a parameter, a catch or try-resource
  // variable, a pattern binding.
  static boolean isLocalVariable(final Element element) {
    return switch (element.getKind()) {
      case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER, RESOURCE_VARIABLE, BINDING_VARIABLE ->
          true;
      default -> false;
    };
  }

  // True when any element in `targets` is reassigned, compound-assigned, or ++/--'d within `scope`
  // in the offset window [regionStart, regionEnd) — the value-capture risk when collapsing repeated
  // evaluations into one.
  static boolean reassignsAny(
      final TreePath scope,
      final Set<Element> targets,
      final Trees trees,
      final CompilationUnitTree cu,
      final long regionStart,
      final long regionEnd) {
    if (targets.isEmpty()) {
      return false;
    }

    final var positions = trees.getSourcePositions();
    final var violated = new AtomicBoolean(false);
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitAssignment(final AssignmentTree node, final Void unused) {
        flag(node.getVariable());
        return super.visitAssignment(node, unused);
      }

      @Override
      public Void visitCompoundAssignment(final CompoundAssignmentTree node, final Void unused) {
        flag(node.getVariable());
        return super.visitCompoundAssignment(node, unused);
      }

      @Override
      public Void visitUnary(final UnaryTree node, final Void unused) {
        if (isIncDec(node.getKind())) {
          flag(node.getExpression());
        }
        return super.visitUnary(node, unused);
      }

      private void flag(final ExpressionTree target) {
        final long start = positions.getStartPosition(cu, target);
        if (start < regionStart || start >= regionEnd) {
          return;
        }

        final Element element = trees.getElement(new TreePath(getCurrentPath(), target));
        if (element != null && targets.contains(element)) {
          violated.set(true);
        }
      }
    }.scan(scope, null);
    return violated.get();
  }

  // The variable an assignment, a compound assignment or ++/-- stores into.
  static boolean isAssignmentTarget(final TreePath path) {
    final Tree parent = path.getParentPath().getLeaf();
    final Tree leaf = path.getLeaf();
    return (parent instanceof final AssignmentTree assignment && assignment.getVariable() == leaf)
        || (parent instanceof final CompoundAssignmentTree compound
            && compound.getVariable() == leaf)
        || (parent instanceof final UnaryTree unary
            && unary.getExpression() == leaf
            && isIncDec(unary.getKind()));
  }

  static boolean isIncDec(final Tree.Kind kind) {
    return kind == Tree.Kind.PREFIX_INCREMENT
        || kind == Tree.Kind.POSTFIX_INCREMENT
        || kind == Tree.Kind.PREFIX_DECREMENT
        || kind == Tree.Kind.POSTFIX_DECREMENT;
  }
}
