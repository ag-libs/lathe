package io.github.aglibs.lathe.analysis.refactor;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.CursorElements;
import io.github.aglibs.lathe.analysis.Positions;
import io.github.aglibs.lathe.analysis.TreePaths;
import io.github.aglibs.lathe.analysis.internal.SourceTypeNames;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

public final class CodeActionSupport {

  private static final Logger LOG = Logger.getLogger(CodeActionSupport.class.getName());

  private CodeActionSupport() {}

  // The text of the compilation unit's source file, or empty when it cannot be read (logged).
  static Optional<String> source(final CompilationUnitTree cu) {
    try {
      return Optional.of(cu.getSourceFile().getCharContent(false).toString());
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING,
          e,
          () -> "[codeAction] %s read failed".formatted(cu.getSourceFile().toUri()));
      return Optional.empty();
    }
  }

  static String typeSimpleName(final TypeMirror type) {
    if (type instanceof DeclaredType dt) {
      return ((TypeElement) dt.asElement()).getSimpleName().toString();
    }
    if (type instanceof ArrayType at) {
      final String component = typeSimpleName(at.getComponentType());
      return component != null ? component + "[]" : null;
    }

    return switch (type.getKind()) {
      case BOOLEAN, BYTE, CHAR, DOUBLE, FLOAT, INT, LONG, SHORT ->
          type.getKind().toString().toLowerCase();
      default -> null;
    };
  }

  public static TreePath pathAt(
      final AttributedFileAnalysis analysis, final int line, final int character) {
    final CompilationUnitTree cu = analysis.tree();
    final long offset = Positions.toOffset(cu, line, character);
    return CursorElements.pathAt(analysis.trees(), cu, offset);
  }

  // A type as code written at `path` names it, and the imports that name needs.
  record SourceType(String text, List<TextEdit> imports) {

    SourceType {
      ValidCheck.check().notNull(text, "text").notNull(imports, "imports").validate();
      imports = List.copyOf(imports);
    }
  }

  static SourceType sourceType(
      final AttributedFileAnalysis analysis, final TreePath path, final TypeMirror type) {
    final var target = (TypeElement) analysis.trees().getElement(TreePaths.enclosingClass(path));
    final var names = new SourceTypeNames(analysis, target);
    return new SourceType(names.formatter().format(type), names.importEditsFor(Stream.of(type)));
  }

  static boolean isInsideClosure(final TreePath path) {
    TreePath current = path;
    while (current != null) {
      if (current.getLeaf() instanceof LambdaExpressionTree
          || TreePaths.isAnonymousClass(current)) {
        return true;
      }

      current = current.getParentPath();
    }
    return false;
  }

  // The outermost statement around path inside its body (a method's, a lambda's): never the body
  // block itself, which a try cannot replace.
  static TreePath enclosingStatementToWrap(final TreePath path) {
    TreePath current = path;
    TreePath statement = null;
    while (current != null) {
      if (TreePaths.isBodyBoundary(current)) {
        return statement;
      }

      final TreePath parent = current.getParentPath();
      if (current.getLeaf() instanceof StatementTree
          && !(parent != null && TreePaths.isBodyBoundary(parent))) {
        statement = current;
      }

      current = parent;
    }
    return null;
  }

  // The offset of the class body's `{`, or -1, past its header: annotations, type parameters,
  // extends, implements, permits, a record's components.
  static int classBodyBrace(
      final ClassTree cls,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final String source) {
    return indexAfter(
        '{',
        cls,
        Stream.<Stream<? extends Tree>>of(
                Stream.of(cls.getModifiers(), cls.getExtendsClause()),
                cls.getTypeParameters().stream(),
                cls.getImplementsClause().stream(),
                cls.getPermitsClause().stream(),
                recordComponents(cls))
            .flatMap(trees -> trees),
        cu,
        positions,
        source);
  }

  // The offset of the first `c` past the owner's header trees, or -1. For punctuation no tree
  // carries (a class body's `{`, an empty parameter list's `(`): searched for from the owner's
  // start, it could be one inside an annotation argument, as in @SuppressWarnings({"x"}).
  static int indexAfter(
      final char c,
      final Tree owner,
      final Stream<? extends Tree> header,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final String source) {
    final long headerEnd =
        header
            .filter(Objects::nonNull)
            .mapToLong(tree -> positions.getEndPosition(cu, tree))
            .max()
            .orElse(-1);
    final long from = Math.max(headerEnd, positions.getStartPosition(cu, owner));
    return from < 0 ? -1 : source.indexOf(c, (int) from);
  }

  // A record may declare no instance field, so its non-static fields are its header's components.
  private static Stream<? extends Tree> recordComponents(final ClassTree cls) {
    return cls.getKind() != Tree.Kind.RECORD
        ? Stream.empty()
        : cls.getMembers().stream()
            .filter(
                member ->
                    member instanceof final VariableTree field
                        && !field.getModifiers().getFlags().contains(Modifier.STATIC));
  }

  // Inserts a member declaration before the body's first real member (javac's default constructor
  // sits before the `{` and is skipped), or just inside the `{` of an empty body.
  static TextEdit insertBeforeFirstMember(
      final ClassTree cls,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final String source,
      final int brace,
      final String declaration) {
    final long anchor =
        cls.getMembers().stream()
            .mapToLong(member -> positions.getStartPosition(cu, member))
            .filter(start -> start > brace)
            .min()
            .orElse(-1);
    if (anchor >= 0) {
      final var pos = Positions.offsetToPosition(cu, anchor);
      return new TextEdit(
          new Range(pos, pos), "%s\n%s".formatted(declaration, lineIndent(source, (int) anchor)));
    }

    final var pos = Positions.offsetToPosition(cu, brace + 1);
    final String classIndent = lineIndent(source, (int) positions.getStartPosition(cu, cls));
    return new TextEdit(new Range(pos, pos), "\n%s  %s".formatted(classIndent, declaration));
  }

  // The last field of a class body (by source position), or null when it declares none — the anchor
  // that keeps an inserted field or constructor grouped after the existing field block. `brace` is
  // the offset of the class body's opening brace, so nested-type fields do not count.
  static VariableTree lastField(
      final ClassTree cls,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final int brace) {
    return cls.getMembers().stream()
        .filter(VariableTree.class::isInstance)
        .map(VariableTree.class::cast)
        .filter(field -> positions.getStartPosition(cu, field) > brace)
        .max(Comparator.comparingLong(field -> positions.getStartPosition(cu, field)))
        .orElse(null);
  }

  // The leading whitespace of the line containing `offset` — the indentation an inserted line must
  // reproduce. Shared by the code-action providers that splice new lines.
  static String lineIndent(final String source, final int offset) {
    int lineStart = offset;
    while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
      lineStart--;
    }

    int indentEnd = lineStart;
    while (indentEnd < source.length() && Character.isWhitespace(source.charAt(indentEnd))) {
      if (source.charAt(indentEnd) == '\n') {
        break;
      }

      indentEnd++;
    }
    return source.substring(lineStart, indentEnd);
  }

  // One indent level as written in this file (code-action requests carry no formatting settings):
  // the statement's indent minus its block owner's indent. Falls back to two spaces.
  static String indentUnit(
      final String source,
      final TreePath statementPath,
      final CompilationUnitTree cu,
      final SourcePositions positions) {
    final long stmtStart = positions.getStartPosition(cu, statementPath.getLeaf());
    final TreePath ownerPath =
        statementPath.getParentPath() == null
            ? null
            : statementPath.getParentPath().getParentPath();
    if (stmtStart < 0 || ownerPath == null) {
      return "  ";
    }

    final long ownerStart = positions.getStartPosition(cu, ownerPath.getLeaf());
    if (ownerStart < 0) {
      return "  ";
    }

    return indentStep(lineIndent(source, (int) stmtStart), lineIndent(source, (int) ownerStart));
  }

  // A block moved to another indent: its first line starts at a tree (no leading indent), later
  // lines carry `from`, the indent of the line it started on; every line gets `to` instead.
  static String rebased(final String block, final String from, final String to) {
    final String[] lines = block.split("\n", -1);
    return IntStream.range(0, lines.length)
        .mapToObj(
            i -> {
              if (i == 0) {
                return to + lines[0];
              }

              if (lines[i].isBlank()) {
                return "";
              }

              return lines[i].startsWith(from) ? to + lines[i].substring(from.length()) : lines[i];
            })
        .collect(Collectors.joining("\n"));
  }

  // One indent level: how much deeper `inner` is than `outer`, else two spaces.
  static String indentStep(final String inner, final String outer) {
    return inner.startsWith(outer) && inner.length() > outer.length()
        ? inner.substring(outer.length())
        : "  ";
  }

  // Indent a block whose first line has no leading indent so it sits one level (step) inside
  // `indent`; later lines keep their own nesting, shifted by one step.
  static String reindent(final String block, final String indent, final String step) {
    final String[] lines = block.split("\n", -1);
    lines[0] = indent + step + lines[0];
    for (int i = 1; i < lines.length; i++) {
      lines[i] = lines[i].isBlank() ? "" : step + lines[i];
    }

    return String.join("\n", lines);
  }

  // Only types that can be written explicitly qualify, at any depth: `var`/inferred types can be
  // anonymous, intersection (List.of(1, "a") is a List<Serializable & Comparable<…>>), or captured
  // type variables, none of which has a source spelling.
  static boolean isDenotable(final TypeMirror type) {
    return switch (type.getKind()) {
      case DECLARED ->
          !isAnonymous((DeclaredType) type)
              && ((DeclaredType) type)
                  .getTypeArguments().stream().allMatch(CodeActionSupport::isDenotableArgument);
      case ARRAY -> isDenotable(((ArrayType) type).getComponentType());
      case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE -> true;
      case TYPEVAR -> SourceVersion.isIdentifier(((TypeVariable) type).asElement().getSimpleName());
      default -> false;
    };
  }

  private static boolean isDenotableArgument(final TypeMirror argument) {
    if (argument instanceof final WildcardType wildcard) {
      return Stream.of(wildcard.getExtendsBound(), wildcard.getSuperBound())
          .filter(Objects::nonNull)
          .allMatch(CodeActionSupport::isDenotableArgument);
    }

    return isDenotable(argument);
  }

  private static boolean isAnonymous(final DeclaredType type) {
    return type.asElement() instanceof final TypeElement te
        && te.getNestingKind() == NestingKind.ANONYMOUS;
  }
}
