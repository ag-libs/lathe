package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// Request-driven (non-diagnostic) refactor: replace an explicit local-variable type with `var` —
// the
// inverse of ReplaceVarProvider, sharing the `refactor.rewrite` kind so one editor shortcut toggles
// either direction. Offered only for a block-level local whose initializer gives `var` a
// well-defined type; a diamond initializer is expanded (`new ArrayList<>()` ->
// `new ArrayList<String>()`) so `var` never silently infers `Object`.
final class ConvertToVarProvider {

  private static final Logger LOG = Logger.getLogger(ConvertToVarProvider.class.getName());

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
    final TreePath varPath =
        CodeActionSupport.enclosingVariable(SourceLocator.pathAt(trees, cu, offset));
    if (varPath == null) {
      return List.of();
    }

    final var varTree = (VariableTree) varPath.getLeaf();
    final Element element = trees.getElement(varPath);
    if (element == null || element.getKind() != ElementKind.LOCAL_VARIABLE) {
      return List.of();
    }

    final TreePath parentPath = varPath.getParentPath();
    if (parentPath == null || !(parentPath.getLeaf() instanceof final BlockTree block)) {
      return List.of();
    }

    final ExpressionTree init = varTree.getInitializer();
    if (init == null || !isVarLegalInitializer(init)) {
      return List.of();
    }

    final var positions = trees.getSourcePositions();
    final long typeStart = positions.getStartPosition(cu, varTree.getType());
    final long typeEnd = positions.getEndPosition(cu, varTree.getType());
    if (typeStart < 0 || typeEnd < 0 || isMultiDeclarator(block, typeStart, positions, cu)) {
      return List.of();
    }

    final TypeMirror declared = element.asType();
    final TypeMirror initType = trees.getTypeMirror(new TreePath(varPath, init));
    if (initType == null || !safeConversion(declared, initType, analysis.types())) {
      return List.of();
    }

    final List<? extends TypeMirror> diamondArgs = diamondArgs(init, initType);
    if (diamondArgs.stream().anyMatch(arg -> !CodeActionSupport.isDenotable(arg))) {
      return List.of();
    }

    final var edits = new ArrayList<TextEdit>();
    edits.add(replaceEdit(cu, typeStart, typeEnd, VAR));
    if (!diamondArgs.isEmpty()) {
      edits.add(diamondFill((NewClassTree) init, diamondArgs, cu, positions, analysis));
    }

    final var action = new CodeAction();
    action.setTitle("Convert to 'var'");
    action.setKind(CodeActionKind.RefactorRewrite);
    final var workspaceEdit = new WorkspaceEdit();
    workspaceEdit.setChanges(Map.of(uri, List.copyOf(edits)));
    action.setEdit(workspaceEdit);

    LOG.fine(() -> "[codeAction:convertToVar] %s".formatted(varTree.getName()));
    return List.of(Either.forRight(action));
  }

  // Excludes initializers that have no standalone type `var` can adopt: an array-initializer
  // shorthand (`{1, 2}`), a lambda, a method reference, or the `null` literal.
  private static boolean isVarLegalInitializer(final ExpressionTree init) {
    return switch (init.getKind()) {
      case LAMBDA_EXPRESSION, MEMBER_REFERENCE, NULL_LITERAL -> false;
      default -> !(init instanceof final NewArrayTree array && array.getType() == null);
    };
  }

  // `int a = 1, b = 2;` is two VariableTrees sharing one type token; `var` cannot spell a multi-
  // variable declaration, so refuse when a sibling in the block starts at the same type offset.
  private static boolean isMultiDeclarator(
      final BlockTree block,
      final long typeStart,
      final SourcePositions positions,
      final CompilationUnitTree cu) {
    int count = 0;
    for (final StatementTree statement : block.getStatements()) {
      if (statement instanceof final VariableTree sibling
          && positions.getStartPosition(cu, sibling.getType()) == typeStart) {
        count++;
      }
    }
    return count > 1;
  }

  // `var` adopts the initializer's type. Allow it when that equals the declared type or is a
  // reference subtype (the `List x = new ArrayList<>()` widening the user opts into). Refuse any
  // primitive mismatch (`long x = 1`, `Number n = 1`), where `var` would silently change the
  // numeric
  // type or unbox.
  private static boolean safeConversion(
      final TypeMirror declared, final TypeMirror initType, final Types types) {
    if (types.isSameType(declared, initType)) {
      return true;
    }

    return !declared.getKind().isPrimitive() && !initType.getKind().isPrimitive();
  }

  // The inferred type arguments of a diamond initializer (`new ArrayList<>()`), or empty when the
  // initializer is not a diamond — those args must be spelled back so `var` does not infer
  // `Object`.
  private static List<? extends TypeMirror> diamondArgs(
      final ExpressionTree init, final TypeMirror initType) {
    if (!(init instanceof final NewClassTree newClass)
        || !(newClass.getIdentifier() instanceof final ParameterizedTypeTree parameterized)
        || !parameterized.getTypeArguments().isEmpty()
        || !(initType instanceof final DeclaredType declaredInit)) {
      return List.of();
    }

    return declaredInit.getTypeArguments();
  }

  private static TextEdit diamondFill(
      final NewClassTree newClass,
      final List<? extends TypeMirror> args,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final AttributedFileAnalysis analysis) {
    final var parameterized = (ParameterizedTypeTree) newClass.getIdentifier();
    final long nameEnd = positions.getEndPosition(cu, parameterized.getType());
    final long idEnd = positions.getEndPosition(cu, parameterized);
    final var formatter = new TypeDisplayFormatter(analysis.types());
    final String rendered = args.stream().map(formatter::format).collect(Collectors.joining(", "));
    return replaceEdit(cu, nameEnd, idEnd, "<%s>".formatted(rendered));
  }

  private static TextEdit replaceEdit(
      final CompilationUnitTree cu, final long start, final long end, final String text) {
    return new TextEdit(
        new Range(
            SourceLocator.offsetToPosition(cu, start), SourceLocator.offsetToPosition(cu, end)),
        text);
  }
}
