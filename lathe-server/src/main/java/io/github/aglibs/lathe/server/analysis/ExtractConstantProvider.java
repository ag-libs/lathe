package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// Request-driven refactor: introduce a `private static final` constant for the selected
// compile-time
// constant expression and replace the occurrence(s) with a reference to it. Sibling of
// ExtractVariableProvider; the eligible set (literals, operations over them, references to other
// constants) makes the extracted field a legal static initializer and replace-all trivially safe.
final class ExtractConstantProvider {

  private static final Logger LOG = Logger.getLogger(ExtractConstantProvider.class.getName());

  private static final String DEFAULT_NAME = "CONSTANT";

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
    if (type == null
        || !CodeActionSupport.isDenotable(type)
        || !ExtractionSupport.isCompileTimeConstant(exprPath, trees)) {
      return List.of();
    }

    final TreePath classPath = CodeActionSupport.enclosingClass(exprPath);
    if (classPath == null || !holdsStaticFinal(((ClassTree) classPath.getLeaf()))) {
      return List.of();
    }

    final var positions = trees.getSourcePositions();
    final long exprStart = positions.getStartPosition(cu, expr);
    final long exprEnd = positions.getEndPosition(cu, expr);
    if (exprStart < 0 || exprEnd < 0) {
      return List.of();
    }

    final String source = CodeActionSupport.source(cu).orElse(null);
    if (source == null) {
      return List.of();
    }

    final String exprSource = source.substring((int) exprStart, (int) exprEnd);
    final var written = CodeActionSupport.sourceType(analysis, exprPath, type);
    final String name = constantName(expr, (ClassTree) classPath.getLeaf());
    final TextEdit insert =
        insertConstantEdit(
            (ClassTree) classPath.getLeaf(),
            cu,
            positions,
            source,
            written.text(),
            name,
            exprSource);
    if (insert == null) {
      return List.of();
    }

    final var actions = new ArrayList<Either<Command, CodeAction>>();
    actions.add(
        ExtractionSupport.action(
            uri,
            "Extract constant '%s'".formatted(name),
            ExtractionSupport.CONSTANT_KIND,
            ExtractionSupport.editList(
                insert,
                List.of(ExtractionSupport.replaceEdit(cu, exprStart, exprEnd, name)),
                written.imports())));

    final List<TreePath> occurrences = ExtractionSupport.occurrences(classPath, exprPath, trees);
    if (occurrences.size() >= 2) {
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
      actions.add(
          ExtractionSupport.action(
              uri,
              "Extract constant '%s' (replace all %d occurrences)"
                  .formatted(name, occurrences.size()),
              ExtractionSupport.CONSTANT_KIND,
              ExtractionSupport.editList(insert, replaces, written.imports())));
    }

    LOG.fine(() -> "[codeAction:extractConst] %s actions=%d".formatted(name, actions.size()));
    return actions;
  }

  // Only kinds where a `private static final` field is well-formed. Interface/annotation members
  // carry different implicit modifiers and are deferred.
  private static boolean holdsStaticFinal(final ClassTree cls) {
    return switch (cls.getKind()) {
      case CLASS, ENUM, RECORD -> true;
      default -> false;
    };
  }

  // Inserts the constant ahead of the first member, so any initializer can reference it. Null when
  // the body cannot be located.
  private static TextEdit insertConstantEdit(
      final ClassTree cls,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final String source,
      final String typeText,
      final String name,
      final String exprSource) {
    final int brace = CodeActionSupport.classBodyBrace(cls, cu, positions, source);
    if (brace < 0) {
      return null;
    }

    return CodeActionSupport.insertBeforeFirstMember(
        cls,
        cu,
        positions,
        source,
        brace,
        "private static final %s %s = %s;".formatted(typeText, name, exprSource));
  }

  // ── name derivation (SCREAMING_SNAKE) ────────────────────────────────────────────────────────

  private static String constantName(final ExpressionTree expr, final ClassTree cls) {
    final String base =
        expr instanceof final LiteralTree literal && literal.getValue() instanceof String text
            ? Strings.screamingSnake(text)
            : DEFAULT_NAME;
    return uniquify(base.isEmpty() ? DEFAULT_NAME : base, existingFieldNames(cls));
  }

  private static Set<String> existingFieldNames(final ClassTree cls) {
    final var names = new HashSet<String>();
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitVariable(final VariableTree tree, final Void unused) {
        names.add(tree.getName().toString());
        return super.visitVariable(tree, unused);
      }
    }.scan(cls, null);
    return names;
  }

  private static String uniquify(final String base, final Set<String> taken) {
    if (!taken.contains(base)) {
      return base;
    }

    // At most taken.size() names can collide, so 2..size+2 always yields a free one.
    return IntStream.rangeClosed(2, taken.size() + 2)
        .mapToObj(suffix -> base + "_" + suffix)
        .filter(candidate -> !taken.contains(candidate))
        .findFirst()
        .orElseThrow();
  }
}
