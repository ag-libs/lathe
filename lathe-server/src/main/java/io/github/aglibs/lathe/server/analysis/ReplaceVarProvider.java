package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// Request-driven (non-diagnostic) refactor: replace a `var` local declaration with its inferred
// explicit type (CA-5). Invoked from the code-action range rather than a diagnostic.
final class ReplaceVarProvider {

  private static final Logger LOG = Logger.getLogger(ReplaceVarProvider.class.getName());

  private static final String VAR = "var";

  List<Either<Command, CodeAction>> provide(
      final String uri, final Range range, final AttributedFileAnalysis analysis) {
    final CompilationUnitTree cu = analysis.tree();
    if (cu == null) {
      return List.of();
    }

    final var trees = analysis.trees();
    final long offset =
        SourceLocator.toOffset(cu, range.getStart().getLine(), range.getStart().getCharacter());
    final TreePath varPath = enclosingVariable(SourceLocator.pathAt(trees, cu, offset));
    if (varPath == null) {
      return List.of();
    }

    final var varTree = (VariableTree) varPath.getLeaf();
    final long varStart = varTokenStart(analysis, cu, varTree);
    if (varStart < 0) {
      return List.of();
    }

    final var element = trees.getElement(varPath);
    if (element == null) {
      return List.of();
    }

    final TypeMirror type = element.asType();
    if (!isDenotable(type)) {
      return List.of();
    }

    final String typeText = new TypeDisplayFormatter(analysis.types()).format(type);
    final var replaceEdit =
        new TextEdit(
            new Range(
                SourceLocator.offsetToPosition(cu, varStart),
                SourceLocator.offsetToPosition(cu, varStart + VAR.length())),
            typeText);
    final TextEdit importEdit =
        CodeActionSupport.importEditFor(analysis, CodeActionSupport.typeFqn(type));
    final List<TextEdit> edits =
        Stream.of(replaceEdit, importEdit).filter(Objects::nonNull).toList();

    final var action = new CodeAction();
    action.setTitle("Replace 'var' with '%s'".formatted(typeText));
    action.setKind(CodeActionKind.Refactor);
    final var workspaceEdit = new WorkspaceEdit();
    workspaceEdit.setChanges(Map.of(uri, edits));
    action.setEdit(workspaceEdit);

    LOG.fine(() -> "[codeAction:replaceVar] %s".formatted(typeText));
    return List.of(Either.forRight(action));
  }

  /**
   * The source offset of the `var` keyword, or -1 when the declaration is not a {@code var} local.
   * Attribution rewrites the {@code var} type tree to the inferred type with no source position, so
   * the keyword is located from source: the whole-word {@code var} token from the declaration
   * start, bounded to before the initializer (a modifier or a variable name can never be {@code
   * var}).
   */
  private static long varTokenStart(
      final AttributedFileAnalysis analysis,
      final CompilationUnitTree cu,
      final VariableTree varTree) {
    final var positions = analysis.trees().getSourcePositions();
    final long declStart = positions.getStartPosition(cu, varTree);
    if (declStart < 0) {
      return -1;
    }

    final long bound =
        varTree.getInitializer() != null
            ? positions.getStartPosition(cu, varTree.getInitializer())
            : positions.getEndPosition(cu, varTree);
    final String content;
    try {
      content = cu.getSourceFile().getCharContent(false).toString();
    } catch (final IOException e) {
      return -1;
    }

    final long varStart = SourceLocator.findIdentifierFrom(content, declStart, VAR);
    if (varStart < 0 || (bound >= 0 && varStart >= bound)) {
      return -1;
    }

    return varStart;
  }

  private static TreePath enclosingVariable(final TreePath path) {
    TreePath current = path;
    while (current != null) {
      if (current.getLeaf() instanceof VariableTree) {
        return current;
      }
      if (current.getLeaf() instanceof MethodTree || current.getLeaf() instanceof ClassTree) {
        return null;
      }
      current = current.getParentPath();
    }
    return null;
  }

  // `var` can infer types that are not denotable (anonymous, intersection, captured type
  // variables);
  // only offer the refactor for kinds that can be written explicitly (CA-5).
  private static boolean isDenotable(final TypeMirror type) {
    return switch (type.getKind()) {
      case DECLARED -> !isAnonymous((DeclaredType) type);
      case ARRAY, BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE -> true;
      default -> false;
    };
  }

  private static boolean isAnonymous(final DeclaredType type) {
    return type.asElement() instanceof final TypeElement te
        && te.getNestingKind() == NestingKind.ANONYMOUS;
  }
}
