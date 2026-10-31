package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
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
    final TreePath exprPath = ExtractionSupport.selectedExpression(trees, cu, range);
    if (exprPath == null) {
      return List.of();
    }

    final var expr = (ExpressionTree) exprPath.getLeaf();
    final TypeMirror type = trees.getTypeMirror(exprPath);
    if (type == null || !CodeActionSupport.isDenotable(type)) {
      return List.of();
    }

    // Where no declaration can precede the expression's statement, its body becomes a block.
    final TreePath body = bodyNeedingBlock(exprPath);
    final TreePath stmtPath =
        body != null ? body : CodeActionSupport.nearestEnclosingStatement(exprPath);
    final TreePath anchorPath =
        body != null ? body : stmtPath != null ? declarationAnchor(stmtPath) : null;
    if (anchorPath == null
        || ExtractionSupport.pinnedInPlace(exprPath, anchorPath.getLeaf(), trees)) {
      return List.of();
    }

    // A whole expression statement (`a.trim();`) becomes the declaration itself; alone in a body,
    // that declaration would be unused.
    final boolean wholeStatement =
        stmtPath.getLeaf() instanceof final ExpressionStatementTree exprStmt
            && exprStmt.getExpression() == expr;
    if (wholeStatement && body != null) {
      return List.of();
    }

    final var positions = trees.getSourcePositions();
    final long exprStart = positions.getStartPosition(cu, expr);
    final long exprEnd = positions.getEndPosition(cu, expr);
    final long anchorStart = positions.getStartPosition(cu, anchorPath.getLeaf());
    if (exprStart < 0 || exprEnd < 0 || anchorStart < 0) {
      return List.of();
    }

    final String source = CodeActionSupport.source(cu).orElse(null);
    if (source == null) {
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
            .get(0);
    final TextEdit importEdit =
        CodeActionSupport.importEditFor(analysis, CodeActionSupport.typeFqn(type));
    final String declaration = "%s %s = %s;".formatted(typeText, name, exprSource);

    final List<TextEdit> edits;
    if (body != null) {
      edits =
          ExtractionSupport.editList(
              blockEdit(analysis, source, body, declaration, name, exprStart, exprEnd),
              List.of(),
              importEdit);
    } else if (wholeStatement) {
      edits =
          ExtractionSupport.editList(
              ExtractionSupport.replaceEdit(
                  cu, exprStart, exprStart, "%s %s = ".formatted(typeText, name)),
              List.of(),
              importEdit);
    } else {
      edits =
          ExtractionSupport.editList(
              insertDecl(cu, source, anchorStart, declaration),
              List.of(ExtractionSupport.replaceEdit(cu, exprStart, exprEnd, name)),
              importEdit);
    }

    final var actions = new ArrayList<Either<Command, CodeAction>>();
    actions.add(
        ExtractionSupport.action(
            uri, "Extract variable '%s'".formatted(name), ExtractionSupport.VARIABLE_KIND, edits));

    final Either<Command, CodeAction> replaceAll =
        body != null || wholeStatement
            ? null
            : replaceAllAction(uri, cu, trees, exprPath, source, declaration, name, importEdit);
    if (replaceAll != null) {
      actions.add(replaceAll);
    }

    LOG.fine(() -> "[codeAction:extractVar] %s actions=%d".formatted(name, actions.size()));
    return actions;
  }

  // The statement the declaration goes before, or null when none fits: the expression's own
  // statement in a block or a `case:` list; for a try resource or a for initializer, the try or for
  // statement itself, wherever that fits.
  private static TreePath declarationAnchor(final TreePath statement) {
    final TreePath parent = statement.getParentPath();
    if (parent == null) {
      return null;
    }

    final Tree container = parent.getLeaf();
    if (container instanceof BlockTree
        || (container instanceof final CaseTree caseTree
            && caseTree.getStatements() != null
            && caseTree.getStatements().contains(statement.getLeaf()))) {
      return statement;
    }

    if ((container instanceof final TryTree tryTree
            && tryTree.getResources().contains(statement.getLeaf()))
        || (container instanceof final ForLoopTree forLoop
            && forLoop.getInitializer().contains(statement.getLeaf()))) {
      return declarationAnchor(parent);
    }

    return null;
  }

  // The body holding the expression where no declaration can go before it, or null: an expression
  // lambda's body, a switch rule's body, or an if/else/loop body written without braces. Inside a
  // block the walk meets the block's statement first, so a block body never qualifies.
  private static TreePath bodyNeedingBlock(final TreePath exprPath) {
    for (TreePath path = exprPath; path.getParentPath() != null; path = path.getParentPath()) {
      final Tree leaf = path.getLeaf();
      final Tree parent = path.getParentPath().getLeaf();
      if (parent instanceof final LambdaExpressionTree lambda) {
        return lambda.getBody() == leaf ? path : null;
      }

      if (parent instanceof final CaseTree caseTree
          && caseTree.getCaseKind() == CaseTree.CaseKind.RULE) {
        return caseTree.getBody() == leaf ? path : null;
      }

      if (leaf instanceof StatementTree) {
        return isBodyOf(parent, leaf) ? path : null;
      }
    }
    return null;
  }

  private static boolean isBodyOf(final Tree parent, final Tree statement) {
    if (parent instanceof final IfTree ifTree) {
      return statement == ifTree.getThenStatement() || statement == ifTree.getElseStatement();
    }

    return (parent instanceof final WhileLoopTree whileLoop
            && whileLoop.getStatement() == statement)
        || (parent instanceof final DoWhileLoopTree doLoop && doLoop.getStatement() == statement)
        || (parent instanceof final ForLoopTree forLoop && forLoop.getStatement() == statement)
        || (parent instanceof final EnhancedForLoopTree forEach
            && forEach.getStatement() == statement);
  }

  // The body as a block declaring the value first: a lambda returns its value when its interface
  // method does, a switch rule yields it, and a statement stays as it is.
  private static TextEdit blockEdit(
      final AttributedFileAnalysis analysis,
      final String source,
      final TreePath body,
      final String declaration,
      final String name,
      final long exprStart,
      final long exprEnd) {
    final CompilationUnitTree cu = analysis.tree();
    final var positions = analysis.trees().getSourcePositions();
    final Tree parent = body.getParentPath().getLeaf();
    final long start = positions.getStartPosition(cu, body.getLeaf());
    final String inBody =
        "%s%s%s"
            .formatted(
                source.substring((int) start, (int) exprStart),
                name,
                source.substring(
                    (int) exprEnd, (int) positions.getEndPosition(cu, body.getLeaf())));
    // A rule's `;` follows its value, outside the body; the block replaces it too.
    final long end =
        positions.getEndPosition(cu, parent instanceof CaseTree ? parent : body.getLeaf());
    final String statement;
    if (body.getLeaf() instanceof StatementTree) {
      statement = inBody;
    } else if (parent instanceof CaseTree) {
      statement = "yield %s;".formatted(inBody);
    } else {
      statement =
          (returnsValue(body.getParentPath(), analysis) ? "return %s;" : "%s;").formatted(inBody);
    }

    final String indent = CodeActionSupport.lineIndent(source, (int) start);
    final TreePath outer = statementInBlock(body);
    final String inner =
        indent
            + (outer != null ? CodeActionSupport.indentUnit(source, outer, cu, positions) : "  ");
    return ExtractionSupport.replaceEdit(
        cu,
        start,
        end,
        "{\n%s%s\n%s%s\n%s}".formatted(inner, declaration, inner, statement, indent));
  }

  private static boolean returnsValue(
      final TreePath lambda, final AttributedFileAnalysis analysis) {
    if (!(analysis.trees().getTypeMirror(lambda) instanceof final DeclaredType type)
        || !(type.asElement() instanceof final TypeElement element)) {
      return false;
    }

    final ExecutableElement method =
        FunctionalInterfaces.singleAbstractMethod(element, analysis.elements());
    return method != null
        && ((ExecutableType) analysis.types().asMemberOf(type, method)).getReturnType().getKind()
            != TypeKind.VOID;
  }

  // The nearest statement enclosing the path that sits in a block, to read the indent unit from.
  private static TreePath statementInBlock(final TreePath path) {
    for (TreePath p = path; p.getParentPath() != null; p = p.getParentPath()) {
      if (p.getLeaf() instanceof StatementTree
          && p.getParentPath().getLeaf() instanceof BlockTree) {
        return p;
      }
    }
    return null;
  }

  private static TextEdit insertDecl(
      final CompilationUnitTree cu,
      final String source,
      final long stmtStart,
      final String declaration) {
    final var pos = SourceLocator.offsetToPosition(cu, stmtStart);
    final String indent = CodeActionSupport.lineIndent(source, (int) stmtStart);
    return new TextEdit(new Range(pos, pos), "%s\n%s".formatted(declaration, indent));
  }

  // ── replace-all occurrences ────────────────────────────────────────────────────────────────

  private static Either<Command, CodeAction> replaceAllAction(
      final String uri,
      final CompilationUnitTree cu,
      final Trees trees,
      final TreePath exprPath,
      final String source,
      final String declaration,
      final String name,
      final TextEdit importEdit) {
    final TreePath methodPath = CodeActionSupport.enclosingMethod(exprPath);
    if (methodPath == null) {
      return null;
    }

    final List<TreePath> occurrences = ExtractionSupport.occurrences(methodPath, exprPath, trees);
    if (occurrences.size() < 2) {
      return null;
    }

    final TreePath block = commonBlock(occurrences);
    if (block == null) {
      return null;
    }

    final var positions = trees.getSourcePositions();
    final Tree anchorStmt = earliestAnchorStatement(block, occurrences, positions, cu);
    // The value must already be computed at the anchor, by an occurrence the anchor
    // statement always evaluates: hoisting one that sits only in a branch or behind &&
    // would run it where it may not run.
    if (anchorStmt == null
        || occurrences.stream()
            .allMatch(o -> ExtractionSupport.pinnedInPlace(o, anchorStmt, trees))) {
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
                    ExtractionSupport.replaceEdit(
                        cu,
                        positions.getStartPosition(cu, o.getLeaf()),
                        positions.getEndPosition(cu, o.getLeaf()),
                        name))
            .toList();

    return ExtractionSupport.action(
        uri,
        "Extract variable '%s' (replace all %d occurrences)".formatted(name, occurrences.size()),
        ExtractionSupport.VARIABLE_KIND,
        ExtractionSupport.editList(
            insertDecl(cu, source, anchorStart, declaration), replaces, importEdit));
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
    final Set<Element> reads = ExtractionSupport.readVariables(selectedPath, trees);
    return !ExtractionSupport.reassignsAny(methodPath, reads, trees, cu, regionStart, regionEnd);
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
