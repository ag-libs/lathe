package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// Wraps an AutoCloseable local declaration in a try-with-resources, moving the declaration and the
// rest of its block into the try body. Sibling of ExtractVariableProvider and TryCatchWrapProvider.
final class TryWithResourcesProvider {

  private static final Logger LOG = Logger.getLogger(TryWithResourcesProvider.class.getName());

  List<Either<Command, CodeAction>> provide(
      final String uri, final Range range, final AttributedFileAnalysis analysis) {
    final CompilationUnitTree cu = analysis.tree();
    if (cu == null) {
      return List.of();
    }

    final var trees = analysis.trees();
    final long startOffset =
        SourceLocator.toOffset(cu, range.getStart().getLine(), range.getStart().getCharacter());
    if (startOffset < 0) {
      return List.of();
    }

    final TreePath stmtPath =
        CodeActionSupport.nearestEnclosingStatement(SourceLocator.pathAt(trees, cu, startOffset));
    if (stmtPath == null
        || !(stmtPath.getLeaf() instanceof final VariableTree decl)
        || decl.getInitializer() == null
        || stmtPath.getParentPath() == null
        || !(stmtPath.getParentPath().getLeaf() instanceof final BlockTree block)) {
      return List.of();
    }

    final Element resource = trees.getElement(stmtPath);
    if (resource == null || !isAutoCloseable(resource.asType(), analysis)) {
      return List.of();
    }

    // A try-resource is implicitly final; a later reassignment would not compile once moved in.
    if (isReassigned(trees, stmtPath.getParentPath(), resource)) {
      return List.of();
    }

    final String source;
    try {
      source = cu.getSourceFile().getCharContent(false).toString();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[codeAction:tryWithResources] failed to read source");
      return List.of();
    }

    final var positions = trees.getSourcePositions();
    final long declStart = positions.getStartPosition(cu, decl);
    final long declEnd = positions.getEndPosition(cu, decl);
    // A trailing r.close() is redundant once the resource is auto-closed; drop it from the body but
    // still consume it from the source. Within the resource's own block its name is unambiguous.
    final List<? extends StatementTree> following = statementsAfter(block, decl);
    final boolean dropClose =
        !following.isEmpty()
            && isResourceClose(following.get(following.size() - 1), decl.getName());
    final List<? extends StatementTree> body =
        dropClose ? following.subList(0, following.size() - 1) : following;
    final long spanEnd =
        following.isEmpty()
            ? declEnd
            : positions.getEndPosition(cu, following.get(following.size() - 1));
    if (declStart < 0 || declEnd < 0 || spanEnd < 0) {
      return List.of();
    }

    final String indent = CodeActionSupport.lineIndent(source, (int) declStart);
    final String step = CodeActionSupport.indentUnit(source, stmtPath, cu, positions);
    final String resourceDecl =
        withoutTrailingSemicolon(source.substring((int) declStart, (int) declEnd));
    final String wrapped =
        wrap(resourceDecl, bodySource(source, positions, cu, body), indent, step);

    final var start = SourceLocator.offsetToPosition(cu, declStart);
    final var end = SourceLocator.offsetToPosition(cu, spanEnd);
    final var edit = new WorkspaceEdit();
    edit.setChanges(Map.of(uri, List.of(new TextEdit(new Range(start, end), wrapped))));

    final var action = new CodeAction();
    action.setTitle("Surround with try-with-resources");
    action.setKind(CodeActionKind.RefactorRewrite);
    action.setEdit(edit);

    LOG.fine(() -> "[codeAction:tryWithResources] %s".formatted(resource.getSimpleName()));
    return List.of(Either.forRight(action));
  }

  private static boolean isAutoCloseable(
      final TypeMirror type, final AttributedFileAnalysis analysis) {
    final TypeElement autoCloseable = analysis.elements().getTypeElement("java.lang.AutoCloseable");
    return autoCloseable != null && analysis.types().isAssignable(type, autoCloseable.asType());
  }

  private static boolean isReassigned(
      final Trees trees, final TreePath blockPath, final Element resource) {
    final var reassigned = new AtomicBoolean(false);
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitAssignment(final AssignmentTree node, final Void unused) {
        final Element target = trees.getElement(new TreePath(getCurrentPath(), node.getVariable()));
        if (resource.equals(target)) {
          reassigned.set(true);
        }

        return super.visitAssignment(node, unused);
      }
    }.scan(blockPath, null);
    return reassigned.get();
  }

  private static boolean isResourceClose(
      final StatementTree stmt, final CharSequence resourceName) {
    return stmt instanceof final ExpressionStatementTree expr
        && expr.getExpression() instanceof final MethodInvocationTree inv
        && inv.getArguments().isEmpty()
        && inv.getMethodSelect() instanceof final MemberSelectTree select
        && select.getIdentifier().contentEquals("close")
        && select.getExpression() instanceof final IdentifierTree receiver
        && receiver.getName().contentEquals(resourceName);
  }

  private static List<? extends StatementTree> statementsAfter(
      final BlockTree block, final VariableTree decl) {
    final List<? extends StatementTree> statements = block.getStatements();
    final int index = statements.indexOf(decl);
    return index < 0 ? List.of() : statements.subList(index + 1, statements.size());
  }

  private static String bodySource(
      final String source,
      final SourcePositions positions,
      final CompilationUnitTree cu,
      final List<? extends StatementTree> body) {
    if (body.isEmpty()) {
      return "";
    }

    final long bodyStart = positions.getStartPosition(cu, body.get(0));
    final long bodyEnd = positions.getEndPosition(cu, body.get(body.size() - 1));
    return source.substring((int) bodyStart, (int) bodyEnd);
  }

  private static String wrap(
      final String resourceDecl, final String body, final String indent, final String step) {
    if (body.isEmpty()) {
      return "try (%s) {\n%s}".formatted(resourceDecl, indent);
    }

    return "try (%s) {\n%s\n%s}"
        .formatted(resourceDecl, CodeActionSupport.reindent(body, indent, step), indent);
  }

  private static String withoutTrailingSemicolon(final String declaration) {
    final String trimmed = declaration.strip();
    return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1).strip() : trimmed;
  }
}
