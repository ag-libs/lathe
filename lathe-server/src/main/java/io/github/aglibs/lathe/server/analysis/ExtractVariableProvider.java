package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
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
// range rather than a diagnostic.
final class ExtractVariableProvider {

  private static final Logger LOG = Logger.getLogger(ExtractVariableProvider.class.getName());

  private static final String DEFAULT_NAME = "value";

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
    final String indent = CodeActionSupport.lineIndent(source, (int) stmtStart);
    final String typeText = new TypeDisplayFormatter(analysis.types()).format(type);
    final String name = variableName(expr, type, exprPath, trees);

    final var insertPos = SourceLocator.offsetToPosition(cu, stmtStart);
    final var insertEdit =
        new TextEdit(
            new Range(insertPos, insertPos),
            "%s %s = %s;\n%s".formatted(typeText, name, exprSource, indent));
    final var replaceEdit =
        new TextEdit(
            new Range(
                SourceLocator.offsetToPosition(cu, exprStart),
                SourceLocator.offsetToPosition(cu, exprEnd)),
            name);
    final TextEdit importEdit =
        CodeActionSupport.importEditFor(analysis, CodeActionSupport.typeFqn(type));
    final List<TextEdit> edits =
        Stream.of(insertEdit, replaceEdit, importEdit).filter(Objects::nonNull).toList();

    final var action = new CodeAction();
    action.setTitle("Extract variable '%s'".formatted(name));
    action.setKind(CodeActionKind.RefactorExtract);
    final var workspaceEdit = new WorkspaceEdit();
    workspaceEdit.setChanges(Map.of(uri, edits));
    action.setEdit(workspaceEdit);

    LOG.fine(() -> "[codeAction:extractVar] %s".formatted(name));
    return List.of(Either.forRight(action));
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

  private static String variableName(
      final ExpressionTree expr,
      final TypeMirror type,
      final TreePath exprPath,
      final Trees trees) {
    return uniquify(legalize(baseName(expr, type)), exprPath);
  }

  private static String baseName(final ExpressionTree expr, final TypeMirror type) {
    if (expr instanceof final MethodInvocationTree inv) {
      final String method = invocationName(inv);
      if (method != null) {
        return stripAccessorPrefix(method);
      }
    }

    final String simpleName = CodeActionSupport.typeSimpleName(type);
    return simpleName != null ? decapitalize(simpleName) : DEFAULT_NAME;
  }

  private static String invocationName(final MethodInvocationTree inv) {
    final ExpressionTree select = inv.getMethodSelect();
    if (select instanceof final IdentifierTree id) {
      return id.getName().toString();
    }

    if (select instanceof final MemberSelectTree ms) {
      return ms.getIdentifier().toString();
    }
    return null;
  }

  // getFoo() -> foo, isReady() -> ready; only when a capitalized remainder follows the prefix.
  private static String stripAccessorPrefix(final String method) {
    for (final String prefix : List.of("get", "is")) {
      if (method.length() > prefix.length()
          && method.startsWith(prefix)
          && Character.isUpperCase(method.charAt(prefix.length()))) {
        return decapitalize(method.substring(prefix.length()));
      }
    }
    return method;
  }

  private static String decapitalize(final String name) {
    if (name.isEmpty()) {
      return name;
    }
    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  private static String legalize(final String base) {
    if (base.isEmpty() || !SourceVersion.isIdentifier(base) || SourceVersion.isKeyword(base)) {
      return DEFAULT_NAME;
    }
    return base;
  }

  private static String uniquify(final String base, final TreePath exprPath) {
    final TreePath methodPath = CodeActionSupport.enclosingMethod(exprPath);
    final Set<String> taken = methodPath != null ? localAndParamNames(methodPath) : Set.of();
    if (!taken.contains(base)) {
      return base;
    }

    int suffix = 1;
    while (taken.contains(base + suffix)) {
      suffix++;
    }
    return base + suffix;
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
