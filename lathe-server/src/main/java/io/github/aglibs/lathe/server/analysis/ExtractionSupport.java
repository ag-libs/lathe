package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// Shared machinery for the expression-extraction refactors (extract variable / constant / field):
// resolving the covering expression from a selection, finding structurally + semantically equal
// occurrences, and assembling the WorkspaceEdit. Placement, naming, and eligibility are each
// refactor's own concern.
final class ExtractionSupport {

  private ExtractionSupport() {}

  // The smallest ExpressionTree on the path from the selection start whose source span covers the
  // whole selection — the expression the user means to extract.
  static TreePath coveringExpression(
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
    final var result = new ArrayList<TreePath>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void scan(final Tree node, final Void unused) {
        if (node instanceof ExpressionTree) {
          final var candidate = new TreePath(getCurrentPath(), node);
          if (equivalent(selectedPath, candidate, trees)) {
            result.add(candidate);
          }
        }
        return super.scan(node, unused);
      }
    }.scan(scopePath, null);
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
      final String uri, final String title, final List<TextEdit> edits) {
    final var action = new CodeAction();
    action.setTitle(title);
    action.setKind(CodeActionKind.RefactorExtract);
    final var workspaceEdit = new WorkspaceEdit();
    workspaceEdit.setChanges(Map.of(uri, edits));
    action.setEdit(workspaceEdit);
    return Either.forRight(action);
  }
}
