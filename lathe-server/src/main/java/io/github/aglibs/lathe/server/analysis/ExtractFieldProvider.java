package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// Request-driven refactor: introduce a `private final` instance field initialized from the selected
// expression and replace the occurrence(s) with a reference to it. Sibling of
// ExtractVariableProvider; eligibility requires the expression to read no locals or parameters (so
// it is a legal field initializer) inside a non-static context of a class or enum (a record cannot
// declare an instance field beyond its components).
final class ExtractFieldProvider {

  private static final Logger LOG = Logger.getLogger(ExtractFieldProvider.class.getName());

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
        ExtractionSupport.coveringExpression(
            trees, cu, SourceLocator.pathAt(trees, cu, startOffset), startOffset, endOffset);
    if (exprPath == null) {
      return List.of();
    }

    final var expr = (ExpressionTree) exprPath.getLeaf();
    final TypeMirror type = trees.getTypeMirror(exprPath);
    if (type == null || !CodeActionSupport.isDenotable(type)) {
      return List.of();
    }

    final TreePath methodPath = CodeActionSupport.enclosingMethod(exprPath);
    if (methodPath == null || isStatic(methodPath, trees)) {
      return List.of();
    }

    final Set<Element> reads = ExtractionSupport.readVariables(exprPath, trees);
    if (reads.stream().anyMatch(read -> read.getKind() != ElementKind.FIELD)) {
      return List.of();
    }

    final TreePath classPath = CodeActionSupport.enclosingClass(exprPath);
    if (classPath == null || !holdsInstanceField(((ClassTree) classPath.getLeaf()))) {
      return List.of();
    }

    final var positions = trees.getSourcePositions();
    final long exprStart = positions.getStartPosition(cu, expr);
    final long exprEnd = positions.getEndPosition(cu, expr);
    if (exprStart < 0 || exprEnd < 0) {
      return List.of();
    }

    final String source;
    try {
      source = cu.getSourceFile().getCharContent(false).toString();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[codeAction:extractField] failed to read source");
      return List.of();
    }

    final var cls = (ClassTree) classPath.getLeaf();
    final String exprSource = source.substring((int) exprStart, (int) exprEnd);
    final String typeText = new TypeDisplayFormatter(analysis.types()).format(type);
    final String name = fieldName(type, expr, cls, methodPath);
    final TextEdit importEdit =
        CodeActionSupport.importEditFor(analysis, CodeActionSupport.typeFqn(type));
    final TextEdit insert = insertFieldEdit(cls, cu, positions, source, typeText, name, exprSource);
    if (insert == null) {
      return List.of();
    }

    final var actions = new ArrayList<Either<Command, CodeAction>>();
    actions.add(
        ExtractionSupport.action(
            uri,
            "Extract field '%s'".formatted(name),
            ExtractionSupport.FIELD_KIND,
            ExtractionSupport.editList(
                insert,
                List.of(ExtractionSupport.replaceEdit(cu, exprStart, exprEnd, name)),
                importEdit)));

    final Either<Command, CodeAction> replaceAll =
        replaceAllAction(uri, cu, trees, classPath, exprPath, reads, name, insert, importEdit);
    if (replaceAll != null) {
      actions.add(replaceAll);
    }

    LOG.fine(() -> "[codeAction:extractField] %s actions=%d".formatted(name, actions.size()));
    return actions;
  }

  private static Either<Command, CodeAction> replaceAllAction(
      final String uri,
      final CompilationUnitTree cu,
      final Trees trees,
      final TreePath classPath,
      final TreePath exprPath,
      final Set<Element> reads,
      final String name,
      final TextEdit insert,
      final TextEdit importEdit) {
    final List<TreePath> occurrences = ExtractionSupport.occurrences(classPath, exprPath, trees);
    if (occurrences.size() < 2) {
      return null;
    }

    final var positions = trees.getSourcePositions();
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

    // A field initializer runs once at construction. Collapsing repeated evaluations is safe only
    // when no field the expression reads is reassigned across the occurrence span.
    if (ExtractionSupport.reassignsAny(classPath, reads, trees, cu, firstStart, lastEnd)) {
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
        "Extract field '%s' (replace all %d occurrences)".formatted(name, occurrences.size()),
        ExtractionSupport.FIELD_KIND,
        ExtractionSupport.editList(insert, replaces, importEdit));
  }

  private static boolean isStatic(final TreePath methodPath, final Trees trees) {
    final Element method = trees.getElement(methodPath);
    return method == null || method.getModifiers().contains(Modifier.STATIC);
  }

  // A record's instance state is exactly its components; interface/annotation fields are implicitly
  // static — so only a class or enum can hold a `private final` instance field.
  private static boolean holdsInstanceField(final ClassTree cls) {
    return switch (cls.getKind()) {
      case CLASS, ENUM -> true;
      default -> false;
    };
  }

  // Places the field after the last existing field (so it joins the field block), or before the
  // first member of an otherwise field-less body, or just after the `{` for an empty body. Null
  // when
  // the body cannot be located.
  private static TextEdit insertFieldEdit(
      final ClassTree cls,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final String source,
      final String typeText,
      final String name,
      final String exprSource) {
    final long classStart = positions.getStartPosition(cu, cls);
    if (classStart < 0) {
      return null;
    }

    final int brace = source.indexOf('{', (int) classStart);
    if (brace < 0) {
      return null;
    }

    final String decl = "private final %s %s = %s;".formatted(typeText, name, exprSource);
    final VariableTree lastField = lastField(cls, cu, positions, brace);
    if (lastField != null) {
      final long fieldEnd = positions.getEndPosition(cu, lastField);
      final long fieldStart = positions.getStartPosition(cu, lastField);
      final var pos = SourceLocator.offsetToPosition(cu, fieldEnd);
      final String indent = CodeActionSupport.lineIndent(source, (int) fieldStart);
      return new TextEdit(new Range(pos, pos), "\n%s%s".formatted(indent, decl));
    }

    final long firstMember =
        cls.getMembers().stream()
            .mapToLong(member -> positions.getStartPosition(cu, member))
            .filter(start -> start > brace)
            .min()
            .orElse(-1);

    if (firstMember >= 0) {
      final var pos = SourceLocator.offsetToPosition(cu, firstMember);
      final String indent = CodeActionSupport.lineIndent(source, (int) firstMember);
      return new TextEdit(new Range(pos, pos), "%s\n%s".formatted(decl, indent));
    }

    final var pos = SourceLocator.offsetToPosition(cu, brace + 1);
    final String classIndent = CodeActionSupport.lineIndent(source, (int) classStart);
    return new TextEdit(new Range(pos, pos), "\n%s  %s".formatted(classIndent, decl));
  }

  private static VariableTree lastField(
      final ClassTree cls,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final int brace) {
    VariableTree last = null;
    long lastStart = -1;
    for (final Tree member : cls.getMembers()) {
      if (!(member instanceof final VariableTree field)) {
        continue;
      }

      final long start = positions.getStartPosition(cu, field);
      if (start > brace && start > lastStart) {
        lastStart = start;
        last = field;
      }
    }
    return last;
  }

  // ── name derivation (camelCase) ──────────────────────────────────────────────────────────────

  private static String fieldName(
      final TypeMirror type,
      final ExpressionTree expr,
      final ClassTree cls,
      final TreePath methodPath) {
    return VariableNameSuggester.suggest(
            CodeActionSupport.typeSimpleName(type),
            elementTypeName(type),
            expr,
            takenNames(cls, methodPath))
        .getFirst();
  }

  // The simple name of the last type argument (Map<String, User> -> User), or null when the type is
  // not generic — the element name the suggester pluralizes for collections.
  private static String elementTypeName(final TypeMirror type) {
    if (type instanceof final DeclaredType declared && !declared.getTypeArguments().isEmpty()) {
      final List<? extends TypeMirror> args = declared.getTypeArguments();
      return CodeActionSupport.typeSimpleName(args.get(args.size() - 1));
    }

    return null;
  }

  // Existing field names plus the enclosing method's locals/params, so the new field neither
  // collides with a sibling field nor is shadowed at the replacement site.
  private static Set<String> takenNames(final ClassTree cls, final TreePath methodPath) {
    final var names = new HashSet<String>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitVariable(final VariableTree tree, final Void unused) {
        names.add(tree.getName().toString());
        return super.visitVariable(tree, unused);
      }
    }.scan(methodPath, null);
    cls.getMembers().stream()
        .filter(VariableTree.class::isInstance)
        .map(member -> ((VariableTree) member).getName().toString())
        .forEach(names::add);
    return names;
  }
}
