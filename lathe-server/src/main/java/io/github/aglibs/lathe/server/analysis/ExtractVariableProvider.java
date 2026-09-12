package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionStatementTree;
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
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import javax.lang.model.element.Element;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// Request-driven refactor: introduce a local for the selected expression and replace the
// expression with a reference to it. Sibling of ReplaceVarProvider, invoked from the code-action
// range rather than a diagnostic. Offers a base single-occurrence action and, when the same
// expression recurs safely in scope, a second replace-all-occurrences action.
final class ExtractVariableProvider {

  private static final Logger LOG = Logger.getLogger(ExtractVariableProvider.class.getName());

  List<Either<Command, CodeAction>> provide(
      final String uri, final Range range, final AttributedFileAnalysis analysis) {
    final CompilationUnitTree cu = analysis.tree();
    if (cu == null) {
      return List.of();
    }

    final var trees = analysis.trees();
    final long startOffset =
        SourceLocator.toOffset(cu, range.getStart().getLine(), range.getStart().getCharacter());
    final long endOffset =
        SourceLocator.toOffset(cu, range.getEnd().getLine(), range.getEnd().getCharacter());
    if (startOffset < 0 || endOffset < 0) {
      return List.of();
    }

    final TreePath exprPath =
        coveringExpression(
            trees, cu, SourceLocator.pathAt(trees, cu, startOffset), startOffset, endOffset);
    if (exprPath == null) {
      return List.of();
    }

    final var expr = (ExpressionTree) exprPath.getLeaf();
    final TypeMirror type = trees.getTypeMirror(exprPath);
    if (type == null || !CodeActionSupport.isDenotable(type)) {
      return List.of();
    }

    final TreePath stmtPath = CodeActionSupport.nearestEnclosingStatement(exprPath);
    if (stmtPath == null
        || stmtPath.getParentPath() == null
        || !(stmtPath.getParentPath().getLeaf() instanceof BlockTree)) {
      return List.of();
    }

    if (stmtPath.getLeaf() instanceof final ExpressionStatementTree exprStmt
        && exprStmt.getExpression() == expr) {
      return List.of();
    }

    final var positions = trees.getSourcePositions();
    final long exprStart = positions.getStartPosition(cu, expr);
    final long exprEnd = positions.getEndPosition(cu, expr);
    final long stmtStart = positions.getStartPosition(cu, stmtPath.getLeaf());
    if (exprStart < 0 || exprEnd < 0 || stmtStart < 0) {
      return List.of();
    }

    final String source;
    try {
      source = cu.getSourceFile().getCharContent(false).toString();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[codeAction:extractVar] failed to read source");
      return List.of();
    }

    final String exprSource = source.substring((int) exprStart, (int) exprEnd);
    final String typeText = new TypeDisplayFormatter(analysis.types()).format(type);
    final String name =
        VariableNameSuggester.suggest(
                CodeActionSupport.typeSimpleName(type),
                elementTypeName(type),
                expr,
                takenNames(exprPath))
            .getFirst();
    final TextEdit importEdit =
        CodeActionSupport.importEditFor(analysis, CodeActionSupport.typeFqn(type));

    final var actions = new ArrayList<Either<Command, CodeAction>>();
    actions.add(
        action(
            uri,
            "Extract variable '%s'".formatted(name),
            editList(
                insertDecl(cu, source, stmtStart, typeText, name, exprSource),
                List.of(replaceEdit(cu, exprStart, exprEnd, name)),
                importEdit)));

    final Either<Command, CodeAction> replaceAll =
        replaceAllAction(uri, cu, trees, exprPath, source, typeText, name, exprSource, importEdit);
    if (replaceAll != null) {
      actions.add(replaceAll);
    }

    LOG.fine(() -> "[codeAction:extractVar] %s actions=%d".formatted(name, actions.size()));
    return actions;
  }

  // The smallest ExpressionTree on the path from the selection start whose source span covers the
  // whole selection — the expression the user means to extract.
  private static TreePath coveringExpression(
      final Trees trees,
      final CompilationUnitTree cu,
      final TreePath from,
      final long start,
      final long end) {
    final var positions = trees.getSourcePositions();
    for (TreePath p = from; p != null; p = p.getParentPath()) {
      if (!(p.getLeaf() instanceof ExpressionTree) || isMethodSelect(p)) {
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

  // ── edit + action assembly (shared by the base and replace-all paths) ──────────────────────

  private static TextEdit insertDecl(
      final CompilationUnitTree cu,
      final String source,
      final long stmtStart,
      final String typeText,
      final String name,
      final String exprSource) {
    final var pos = SourceLocator.offsetToPosition(cu, stmtStart);
    final String indent = CodeActionSupport.lineIndent(source, (int) stmtStart);
    return new TextEdit(
        new Range(pos, pos), "%s %s = %s;\n%s".formatted(typeText, name, exprSource, indent));
  }

  private static TextEdit replaceEdit(
      final CompilationUnitTree cu, final long start, final long end, final String name) {
    return new TextEdit(
        new Range(
            SourceLocator.offsetToPosition(cu, start), SourceLocator.offsetToPosition(cu, end)),
        name);
  }

  private static List<TextEdit> editList(
      final TextEdit insert, final List<TextEdit> replaces, final TextEdit importEdit) {
    final var edits = new ArrayList<TextEdit>();
    edits.add(insert);
    edits.addAll(replaces);
    if (importEdit != null) {
      edits.add(importEdit);
    }
    return edits;
  }

  private static Either<Command, CodeAction> action(
      final String uri, final String title, final List<TextEdit> edits) {
    final var action = new CodeAction();
    action.setTitle(title);
    action.setKind(CodeActionKind.RefactorExtract);
    final var workspaceEdit = new WorkspaceEdit();
    workspaceEdit.setChanges(Map.of(uri, edits));
    action.setEdit(workspaceEdit);
    return Either.forRight(action);
  }

  // ── replace-all occurrences ────────────────────────────────────────────────────────────────

  private static Either<Command, CodeAction> replaceAllAction(
      final String uri,
      final CompilationUnitTree cu,
      final Trees trees,
      final TreePath exprPath,
      final String source,
      final String typeText,
      final String name,
      final String exprSource,
      final TextEdit importEdit) {
    final TreePath methodPath = CodeActionSupport.enclosingMethod(exprPath);
    if (methodPath == null) {
      return null;
    }

    final List<TreePath> occurrences = occurrences(methodPath, exprPath, trees);
    if (occurrences.size() < 2) {
      return null;
    }

    final TreePath block = commonBlock(occurrences);
    if (block == null) {
      return null;
    }

    final var positions = trees.getSourcePositions();
    final Tree anchorStmt = earliestAnchorStatement(block, occurrences, positions, cu);
    if (anchorStmt == null) {
      return null;
    }

    final long anchorStart = positions.getStartPosition(cu, anchorStmt);
    final long firstStart =
        occurrences.stream()
            .mapToLong(o -> positions.getStartPosition(cu, o.getLeaf()))
            .min()
            .orElseThrow();
    final long lastEnd =
        occurrences.stream()
            .mapToLong(o -> positions.getEndPosition(cu, o.getLeaf()))
            .max()
            .orElseThrow();

    // The insert must strictly precede every occurrence so the edits never share an offset.
    if (anchorStart < 0 || firstStart <= anchorStart) {
      return null;
    }

    if (!valueStable(methodPath, exprPath, trees, cu, anchorStart, lastEnd)) {
      return null;
    }

    final List<TextEdit> replaces =
        occurrences.stream()
            .map(
                o ->
                    replaceEdit(
                        cu,
                        positions.getStartPosition(cu, o.getLeaf()),
                        positions.getEndPosition(cu, o.getLeaf()),
                        name))
            .toList();

    return action(
        uri,
        "Extract variable '%s' (replace all %d occurrences)".formatted(name, occurrences.size()),
        editList(
            insertDecl(cu, source, anchorStart, typeText, name, exprSource), replaces, importEdit));
  }

  // Every expression in the enclosing method structurally and semantically equal to the selection
  // (including the selection itself), so N >= 2 means the same value is computed more than once.
  private static List<TreePath> occurrences(
      final TreePath methodPath, final TreePath selectedPath, final Trees trees) {
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
    }.scan(methodPath, null);
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

  // The innermost BlockTree that encloses every occurrence (the method body always qualifies), so
  // the introduced declaration is in scope at all of them.
  private static TreePath commonBlock(final List<TreePath> occurrences) {
    for (TreePath b = occurrences.get(0); b != null; b = b.getParentPath()) {
      if (!(b.getLeaf() instanceof BlockTree)) {
        continue;
      }

      final Tree blockLeaf = b.getLeaf();
      if (occurrences.stream().allMatch(o -> hasAncestor(o, blockLeaf))) {
        return b;
      }
    }
    return null;
  }

  private static boolean hasAncestor(final TreePath path, final Tree ancestorLeaf) {
    for (TreePath p = path; p != null; p = p.getParentPath()) {
      if (p.getLeaf() == ancestorLeaf) {
        return true;
      }
    }
    return false;
  }

  // The block's earliest direct-child statement that contains an occurrence — the declaration is
  // inserted before it.
  private static Tree earliestAnchorStatement(
      final TreePath block,
      final List<TreePath> occurrences,
      final SourcePositions positions,
      final CompilationUnitTree cu) {
    final Tree blockLeaf = block.getLeaf();
    final List<Tree> statements = occurrences.stream().map(o -> childOf(o, blockLeaf)).toList();
    if (statements.stream().anyMatch(Objects::isNull)) {
      return null;
    }

    return statements.stream()
        .min(Comparator.comparingLong(stmt -> positions.getStartPosition(cu, stmt)))
        .orElse(null);
  }

  private static Tree childOf(final TreePath path, final Tree parentLeaf) {
    for (TreePath p = path; p.getParentPath() != null; p = p.getParentPath()) {
      if (p.getParentPath().getLeaf() == parentLeaf) {
        return p.getLeaf();
      }
    }
    return null;
  }

  // Refuses replace-all when any variable/field the expression reads is reassigned, compound-
  // assigned, or incremented/decremented between the anchor and the last occurrence — the only
  // silent-corruption mode (value capture). Shadowing needs no separate check: a shadowing
  // declaration makes later uses resolve to a different Element, so they are never collected as
  // occurrences in the first place.
  private static boolean valueStable(
      final TreePath methodPath,
      final TreePath selectedPath,
      final Trees trees,
      final CompilationUnitTree cu,
      final long regionStart,
      final long regionEnd) {
    final Set<Element> reads = readSet(selectedPath, trees);
    if (reads.isEmpty()) {
      return true;
    }

    final var positions = trees.getSourcePositions();
    final var violated = new AtomicBoolean(false);
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitAssignment(final AssignmentTree node, final Void unused) {
        flagIfRead(node.getVariable());
        return super.visitAssignment(node, unused);
      }

      @Override
      public Void visitCompoundAssignment(final CompoundAssignmentTree node, final Void unused) {
        flagIfRead(node.getVariable());
        return super.visitCompoundAssignment(node, unused);
      }

      @Override
      public Void visitUnary(final UnaryTree node, final Void unused) {
        if (isIncDec(node.getKind())) {
          flagIfRead(node.getExpression());
        }
        return super.visitUnary(node, unused);
      }

      private void flagIfRead(final ExpressionTree target) {
        final long start = positions.getStartPosition(cu, target);
        if (start < regionStart || start >= regionEnd) {
          return;
        }

        final Element element = trees.getElement(new TreePath(getCurrentPath(), target));
        if (element != null && reads.contains(element)) {
          violated.set(true);
        }
      }
    }.scan(methodPath, null);
    return !violated.get();
  }

  private static Set<Element> readSet(final TreePath selectedPath, final Trees trees) {
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

    switch (element.getKind()) {
      case LOCAL_VARIABLE,
          PARAMETER,
          FIELD,
          EXCEPTION_PARAMETER,
          RESOURCE_VARIABLE,
          BINDING_VARIABLE ->
          reads.add(element);
      default -> {}
    }
  }

  private static boolean isIncDec(final Tree.Kind kind) {
    return kind == Tree.Kind.PREFIX_INCREMENT
        || kind == Tree.Kind.POSTFIX_INCREMENT
        || kind == Tree.Kind.PREFIX_DECREMENT
        || kind == Tree.Kind.POSTFIX_DECREMENT;
  }

  // ── name derivation ────────────────────────────────────────────────────────────────────────

  private static Set<String> takenNames(final TreePath exprPath) {
    final TreePath methodPath = CodeActionSupport.enclosingMethod(exprPath);
    return methodPath != null ? localAndParamNames(methodPath) : Set.of();
  }

  // The simple name of the last type argument (Map<String, User> -> User), or null when the type is
  // not generic — the element/value name the suggester pluralizes for collections.
  private static String elementTypeName(final TypeMirror type) {
    if (type instanceof final DeclaredType declared && !declared.getTypeArguments().isEmpty()) {
      final List<? extends TypeMirror> args = declared.getTypeArguments();
      return CodeActionSupport.typeSimpleName(args.get(args.size() - 1));
    }

    return null;
  }

  private static Set<String> localAndParamNames(final TreePath methodPath) {
    final var names = new HashSet<String>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitVariable(final VariableTree tree, final Void unused) {
        names.add(tree.getName().toString());
        return super.visitVariable(tree, unused);
      }
    }.scan(methodPath, null);
    return names;
  }
}
