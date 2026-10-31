package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssertTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
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
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.ArrayList;
import java.util.HashMap;
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
    final Span span = Span.of(cu, selection);
    if (span == null) {
      return null;
    }

    final long start = span.start();
    final long end = span.end();
    final List<TreePath> within =
        start < end ? outermostTreesWithin(trees, cu, start, end) : List.of();
    final TreePath spanned = within.size() == 1 ? within.get(0) : null;
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

  // The run of whole statements a selection spans, or empty: the outermost trees inside it must be
  // consecutive statements of one block or `case:` list. The indentation, line breaks and `;`
  // around them don't matter, as for an expression.
  static List<TreePath> selectedStatements(
      final Trees trees, final CompilationUnitTree cu, final Range selection) {
    final Span span = Span.of(cu, selection);
    if (span == null || span.start() >= span.end()) {
      return List.of();
    }

    final List<TreePath> within = outermostTreesWithin(trees, cu, span.start(), span.end());
    if (within.isEmpty() || !within.stream().allMatch(p -> p.getLeaf() instanceof StatementTree)) {
      return List.of();
    }

    final List<? extends StatementTree> siblings =
        statementsOf(within.get(0).getParentPath().getLeaf());
    final int first = siblings.indexOf(within.get(0).getLeaf());
    final boolean consecutive =
        first >= 0
            && first + within.size() <= siblings.size()
            && IntStream.range(0, within.size())
                .allMatch(i -> siblings.get(first + i) == within.get(i).getLeaf());
    return consecutive ? List.copyOf(within) : List.of();
  }

  private static List<? extends StatementTree> statementsOf(final Tree parent) {
    if (parent instanceof final BlockTree block) {
      return block.getStatements();
    }

    return parent instanceof final CaseTree caseTree && caseTree.getStatements() != null
        ? caseTree.getStatements()
        : List.of();
  }

  // A selection's source offsets, or null when a position lies outside the file.
  private record Span(long start, long end) {

    Span {
      ValidCheck.check().assertTrue(start >= 0 && end >= 0, "offsets").validate();
    }

    static Span of(final CompilationUnitTree cu, final Range selection) {
      final long start =
          SourceLocator.toOffset(
              cu, selection.getStart().getLine(), selection.getStart().getCharacter());
      final long end =
          SourceLocator.toOffset(
              cu, selection.getEnd().getLine(), selection.getEnd().getCharacter());
      return start >= 0 && end >= 0 ? new Span(start, end) : null;
    }
  }

  // The outermost trees whose spans lie inside [start, end].
  private static List<TreePath> outermostTreesWithin(
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
    return List.copyOf(within);
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

  // A run of consecutive statements (or one expression) to replace with a call, and the local each
  // of the region's locals stands for there; a local missing from the map stands for itself.
  record Site(List<TreePath> run, Map<VariableElement, VariableElement> locals) {

    Site {
      ValidCheck.check().notNull(run, "run").notNull(locals, "locals").validate();
      run = List.copyOf(run);
      locals = Map.copyOf(locals);
    }

    VariableElement local(final VariableElement regionLocal) {
      return locals.getOrDefault(regionLocal, regionLocal);
    }
  }

  // Every run of statements in the class's own members (not nested class bodies) that matches the
  // region's statements one for one, a local of the region standing for one local of the copy each,
  // in text order and overlapping neither the region nor each other. The locals' types and the
  // copy's data flow are the caller's to check.
  static List<Site> statementCopies(
      final TreePath classPath, final List<TreePath> region, final Trees trees) {
    final List<List<TreePath>> runs = new ArrayList<>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitClass(final ClassTree tree, final Void unused) {
        return tree == classPath.getLeaf() ? super.visitClass(tree, unused) : null;
      }

      @Override
      public Void visitBlock(final BlockTree tree, final Void unused) {
        addRuns(tree.getStatements());
        return super.visitBlock(tree, unused);
      }

      @Override
      public Void visitCase(final CaseTree tree, final Void unused) {
        if (tree.getStatements() != null) {
          addRuns(tree.getStatements());
        }

        return super.visitCase(tree, unused);
      }

      private void addRuns(final List<? extends StatementTree> statements) {
        IntStream.rangeClosed(0, statements.size() - region.size())
            .mapToObj(
                first ->
                    statements.subList(first, first + region.size()).stream()
                        .map(statement -> child(getCurrentPath(), statement))
                        .toList())
            .forEach(runs::add);
      }
    }.scan(classPath, null);

    final Set<Tree> taken = new HashSet<>();
    region.forEach(statement -> taken.add(statement.getLeaf()));
    final List<Site> copies = new ArrayList<>();
    for (final List<TreePath> run : runs) {
      final var equivalence = new Equivalence(trees, true);
      if (run.stream().noneMatch(statement -> taken.contains(statement.getLeaf()))
          && IntStream.range(0, run.size())
              .allMatch(k -> equivalence.equivalent(region.get(k), run.get(k)))) {
        run.forEach(statement -> taken.add(statement.getLeaf()));
        copies.add(new Site(run, equivalence.locals));
      }
    }

    return copies;
  }

  private static boolean equivalent(final TreePath a, final TreePath b, final Trees trees) {
    return new Equivalence(trees, false).equivalent(a, b);
  }

  // Structural equality (same kind, operator, literal value, child shape) plus semantic equality:
  // every identifier / member-select must resolve to the same Element, which is what distinguishes
  // `a.x` from `b.x`. Unsupported kinds are conservatively unequal, so exotic code simply gains no
  // replace-all action. For statements it also matches statement kinds and side effects, and lets
  // a local stand for another, one-to-one, recording the pairs.
  private static final class Equivalence {

    private static final Set<ElementKind> LOCAL_KINDS =
        Set.of(
            ElementKind.LOCAL_VARIABLE,
            ElementKind.PARAMETER,
            ElementKind.EXCEPTION_PARAMETER,
            ElementKind.RESOURCE_VARIABLE,
            ElementKind.BINDING_VARIABLE);

    private final Trees trees;
    private final boolean statements;
    private final Map<VariableElement, VariableElement> locals = new HashMap<>();

    Equivalence(final Trees trees, final boolean statements) {
      this.trees = trees;
      this.statements = statements;
    }

    boolean equivalent(final TreePath a, final TreePath b) {
      final Tree ta = a.getLeaf();
      final Tree tb = b.getLeaf();
      if (ta.getKind() != tb.getKind()) {
        return false;
      }

      if (ta instanceof IdentifierTree) {
        return sameElement(a, b);
      }

      if (ta instanceof final LiteralTree la) {
        return Objects.equals(la.getValue(), ((LiteralTree) tb).getValue());
      }

      if (ta instanceof final ParenthesizedTree pa) {
        return equivalent(
            child(a, pa.getExpression()), child(b, ((ParenthesizedTree) tb).getExpression()));
      }

      if (ta instanceof final MemberSelectTree ma) {
        final var mb = (MemberSelectTree) tb;
        return ma.getIdentifier().contentEquals(mb.getIdentifier())
            && sameElement(a, b)
            && equivalent(child(a, ma.getExpression()), child(b, mb.getExpression()));
      }

      if (ta instanceof final MethodInvocationTree ia) {
        final var ib = (MethodInvocationTree) tb;
        return ia.getTypeArguments().isEmpty()
            && ib.getTypeArguments().isEmpty()
            && equivalent(child(a, ia.getMethodSelect()), child(b, ib.getMethodSelect()))
            && childrenEquivalent(a, ia.getArguments(), b, ib.getArguments());
      }

      if (ta instanceof final BinaryTree ba) {
        final var bb = (BinaryTree) tb;
        return equivalent(child(a, ba.getLeftOperand()), child(b, bb.getLeftOperand()))
            && equivalent(child(a, ba.getRightOperand()), child(b, bb.getRightOperand()));
      }

      if (ta instanceof final UnaryTree ua && (statements || isPureUnary(ta.getKind()))) {
        return equivalent(child(a, ua.getExpression()), child(b, ((UnaryTree) tb).getExpression()));
      }

      if (ta instanceof final NewClassTree na) {
        final var nb = (NewClassTree) tb;
        return na.getClassBody() == null
            && nb.getClassBody() == null
            && na.getTypeArguments().isEmpty()
            && nb.getTypeArguments().isEmpty()
            && sameElement(a, b)
            && childrenEquivalent(a, na.getArguments(), b, nb.getArguments());
      }

      if (ta instanceof final ArrayAccessTree aa) {
        final var ab = (ArrayAccessTree) tb;
        return equivalent(child(a, aa.getExpression()), child(b, ab.getExpression()))
            && equivalent(child(a, aa.getIndex()), child(b, ab.getIndex()));
      }

      if (ta instanceof final ConditionalExpressionTree ca) {
        final var cb = (ConditionalExpressionTree) tb;
        return equivalent(child(a, ca.getCondition()), child(b, cb.getCondition()))
            && equivalent(child(a, ca.getTrueExpression()), child(b, cb.getTrueExpression()))
            && equivalent(child(a, ca.getFalseExpression()), child(b, cb.getFalseExpression()));
      }

      if (ta instanceof final TypeCastTree ka) {
        final var kb = (TypeCastTree) tb;
        return ka.getType().toString().equals(kb.getType().toString())
            && equivalent(child(a, ka.getExpression()), child(b, kb.getExpression()));
      }

      return statements && statementEquivalent(a, b);
    }

    private boolean statementEquivalent(final TreePath a, final TreePath b) {
      final Tree ta = a.getLeaf();
      final Tree tb = b.getLeaf();
      if (ta instanceof final AssignmentTree sa) {
        final var sb = (AssignmentTree) tb;
        return equivalent(child(a, sa.getVariable()), child(b, sb.getVariable()))
            && equivalent(child(a, sa.getExpression()), child(b, sb.getExpression()));
      }

      if (ta instanceof final CompoundAssignmentTree sa) {
        final var sb = (CompoundAssignmentTree) tb;
        return equivalent(child(a, sa.getVariable()), child(b, sb.getVariable()))
            && equivalent(child(a, sa.getExpression()), child(b, sb.getExpression()));
      }

      if (ta instanceof final ExpressionStatementTree ea) {
        return equivalent(
            child(a, ea.getExpression()), child(b, ((ExpressionStatementTree) tb).getExpression()));
      }

      if (ta instanceof final BlockTree ba) {
        return childrenEquivalent(a, ba.getStatements(), b, ((BlockTree) tb).getStatements());
      }

      if (ta instanceof final VariableTree va) {
        final var vb = (VariableTree) tb;
        return va.getModifiers().getFlags().equals(vb.getModifiers().getFlags())
            && va.getModifiers().getAnnotations().isEmpty()
            && vb.getModifiers().getAnnotations().isEmpty()
            && sameElement(a, b)
            && optionalEquivalent(a, va.getInitializer(), b, vb.getInitializer());
      }

      if (ta instanceof final IfTree ia) {
        final var ib = (IfTree) tb;
        return equivalent(child(a, ia.getCondition()), child(b, ib.getCondition()))
            && equivalent(child(a, ia.getThenStatement()), child(b, ib.getThenStatement()))
            && optionalEquivalent(a, ia.getElseStatement(), b, ib.getElseStatement());
      }

      if (ta instanceof final ReturnTree ra) {
        return optionalEquivalent(a, ra.getExpression(), b, ((ReturnTree) tb).getExpression());
      }

      if (ta instanceof final ThrowTree ra) {
        return equivalent(child(a, ra.getExpression()), child(b, ((ThrowTree) tb).getExpression()));
      }

      if (ta instanceof final EnhancedForLoopTree fa) {
        final var fb = (EnhancedForLoopTree) tb;
        return equivalent(child(a, fa.getVariable()), child(b, fb.getVariable()))
            && equivalent(child(a, fa.getExpression()), child(b, fb.getExpression()))
            && equivalent(child(a, fa.getStatement()), child(b, fb.getStatement()));
      }

      if (ta instanceof final WhileLoopTree wa) {
        final var wb = (WhileLoopTree) tb;
        return equivalent(child(a, wa.getCondition()), child(b, wb.getCondition()))
            && equivalent(child(a, wa.getStatement()), child(b, wb.getStatement()));
      }

      return false;
    }

    private boolean optionalEquivalent(
        final TreePath a, final Tree ta, final TreePath b, final Tree tb) {
      return ta == null ? tb == null : tb != null && equivalent(child(a, ta), child(b, tb));
    }

    private boolean childrenEquivalent(
        final TreePath a,
        final List<? extends Tree> as,
        final TreePath b,
        final List<? extends Tree> bs) {
      return as.size() == bs.size()
          && IntStream.range(0, as.size())
              .allMatch(i -> equivalent(child(a, as.get(i)), child(b, bs.get(i))));
    }

    private boolean sameElement(final TreePath a, final TreePath b) {
      final Element ea = trees.getElement(a);
      final Element eb = trees.getElement(b);
      if (statements && isLocal(ea) && isLocal(eb)) {
        return standsFor((VariableElement) ea, (VariableElement) eb);
      }

      return ea != null && ea.equals(eb);
    }

    private boolean standsFor(final VariableElement a, final VariableElement b) {
      final VariableElement known = locals.get(a);
      if (known != null) {
        return known.equals(b);
      }

      if (locals.containsValue(b)) {
        return false;
      }

      locals.put(a, b);
      return true;
    }

    private static boolean isLocal(final Element element) {
      return element instanceof VariableElement && LOCAL_KINDS.contains(element.getKind());
    }
  }

  private static TreePath child(final TreePath parent, final Tree node) {
    return new TreePath(parent, node);
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
