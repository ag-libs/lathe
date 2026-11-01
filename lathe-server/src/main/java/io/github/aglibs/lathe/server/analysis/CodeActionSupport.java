package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

final class CodeActionSupport {

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

  static String typeFqn(final TypeMirror type) {
    if (type instanceof DeclaredType dt) {
      return ((TypeElement) dt.asElement()).getQualifiedName().toString();
    }
    return null;
  }

  // An import edit for the fully-qualified name, or null when none is needed (already imported,
  // java.lang, same package) or the name is null. Shared by the code-action providers.
  static TextEdit importEditFor(final AttributedFileAnalysis analysis, final String fqn) {
    return fqn == null ? null : new ImportAnalyzer(analysis).importEdit(fqn);
  }

  static TreePath pathAt(
      final AttributedFileAnalysis analysis, final int line, final int character) {
    final CompilationUnitTree cu = analysis.tree();
    final long offset = SourceLocator.toOffset(cu, line, character);
    return SourceLocator.pathAt(analysis.trees(), cu, offset);
  }

  static TreePath enclosingMethod(final TreePath path) {
    TreePath current = path;
    while (current != null) {
      if (current.getLeaf() instanceof MethodTree) {
        return current;
      }

      current = current.getParentPath();
    }
    return null;
  }

  static TreePath enclosingClass(final TreePath path) {
    TreePath current = path;
    while (current != null) {
      if (current.getLeaf() instanceof ClassTree) {
        return current;
      }

      current = current.getParentPath();
    }
    return null;
  }

  // Import edits for every type the given types mention, each needed import once.
  // The imports code written into `target` needs for these types: the top-level type each written
  // name starts from (see sourceTypeFormatter).
  static List<TextEdit> importEditsFor(
      final AttributedFileAnalysis analysis,
      final TypeElement target,
      final Stream<? extends TypeMirror> types) {
    final var importAnalyzer = new ImportAnalyzer(analysis);
    return types
        .flatMap(CodeActionSupport::typesIn)
        .filter(DeclaredType.class::isInstance)
        .map(type -> ((DeclaredType) type).asElement())
        .filter(TypeElement.class::isInstance)
        .map(type -> nameRoot(analysis, target, (TypeElement) type))
        .filter(root -> root.getNestingKind() != NestingKind.MEMBER)
        .distinct()
        .filter(type -> !declaredIn(analysis, type) && !clashes(analysis, type))
        .map(type -> type.getQualifiedName().toString())
        .map(importAnalyzer::importEdit)
        .filter(Objects::nonNull)
        .toList();
  }

  // For code written into `target`, a class of the file: a nested type `target` cannot see by its
  // simple name is written from the nearest enclosing type it can (`ClassModel.Builder`, as Java
  // code usually does; a bare `Module` could be java.lang's, two `Builder`s would clash); the name
  // it starts from is written qualified when the file already gives that simple name another type
  // (by an import, a declaration or its package), else simply.
  static TypeDisplayFormatter sourceTypeFormatter(
      final AttributedFileAnalysis analysis, final TypeElement target) {
    return new TypeDisplayFormatter(analysis.types(), type -> sourceName(analysis, target, type));
  }

  private static String sourceName(
      final AttributedFileAnalysis analysis, final TypeElement target, final TypeElement type) {
    final TypeElement root = nameRoot(analysis, target, type);
    final String rootName =
        (clashes(analysis, root) ? root.getQualifiedName() : root.getSimpleName()).toString();
    return rootName
        + type.getQualifiedName().toString().substring(root.getQualifiedName().length());
  }

  // The type a written name starts from: the type itself, or for a nested type `target` cannot see
  // by its simple name, the nearest enclosing type it can (or the top-level type).
  private static TypeElement nameRoot(
      final AttributedFileAnalysis analysis, final TypeElement target, final TypeElement type) {
    return type.getNestingKind() == NestingKind.MEMBER && !memberTypeInScope(analysis, target, type)
        ? nameRoot(analysis, target, (TypeElement) type.getEnclosingElement())
        : type;
  }

  // A member type `target` sees by its simple name: one of its own or an enclosing class's members,
  // declared or inherited.
  private static boolean memberTypeInScope(
      final AttributedFileAnalysis analysis, final TypeElement target, final TypeElement type) {
    return Stream.iterate(
            target,
            Objects::nonNull,
            c -> c.getEnclosingElement() instanceof final TypeElement outer ? outer : null)
        .anyMatch(c -> analysis.elements().getAllMembers(c).contains(type));
  }

  private static boolean clashes(final AttributedFileAnalysis analysis, final TypeElement type) {
    final String simpleName = type.getSimpleName().toString();
    final String qualifiedName = type.getQualifiedName().toString();
    return new ImportAnalyzer(analysis)
            .importedQualifiedNames().stream()
                .anyMatch(
                    imported ->
                        !imported.equals(qualifiedName) && imported.endsWith("." + simpleName))
        || declaredTypeNames(analysis).contains(simpleName) && !declaredIn(analysis, type)
        || inOwnPackage(analysis, simpleName).filter(sibling -> !sibling.equals(type)).isPresent();
  }

  // The type of the file's own package with this simple name: it is in scope there without an
  // import, and an import of another one would shadow it for the whole file.
  private static Optional<TypeElement> inOwnPackage(
      final AttributedFileAnalysis analysis, final String simpleName) {
    final ExpressionTree packageName = analysis.tree().getPackageName();
    final String name =
        packageName == null ? simpleName : "%s.%s".formatted(packageName, simpleName);
    return Optional.ofNullable(analysis.elements().getTypeElement(name));
  }

  private static Set<String> declaredTypeNames(final AttributedFileAnalysis analysis) {
    final Set<String> names = new HashSet<>();
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitClass(final ClassTree tree, final Void unused) {
        names.add(tree.getSimpleName().toString());
        return super.visitClass(tree, unused);
      }
    }.scan(analysis.tree(), null);
    return names;
  }

  // A type of the file itself is in scope by its simple name, and importing a private one would
  // not even compile.
  private static boolean declaredIn(final AttributedFileAnalysis analysis, final TypeElement type) {
    final TreePath path = analysis.trees().getPath(type);
    return path != null && path.getCompilationUnit() == analysis.tree();
  }

  // A type and every type in it: generic arguments, array components, wildcard bounds.
  static Stream<TypeMirror> typesIn(final TypeMirror type) {
    final Stream<? extends TypeMirror> nested;
    if (type instanceof final DeclaredType declared) {
      nested = declared.getTypeArguments().stream();
    } else if (type instanceof final ArrayType array) {
      nested = Stream.of(array.getComponentType());
    } else if (type instanceof final WildcardType wildcard) {
      nested =
          Stream.of(wildcard.getExtendsBound(), wildcard.getSuperBound()).filter(Objects::nonNull);
    } else {
      nested = Stream.empty();
    }

    return Stream.concat(Stream.of(type), nested.flatMap(CodeActionSupport::typesIn));
  }

  // The variable declaration enclosing the cursor, bounded at the method/class boundary so a caret
  // in a method body never climbs to a field. Null when no declaration encloses the path.
  static TreePath enclosingVariable(final TreePath path) {
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

  // The source offset of a local declaration's `var` keyword, or -1 when it is not a `var` local.
  // Read from source, not the type tree: attribution replaces the `var` node with the inferred type
  // and whether that node keeps a source position varies by JDK (none through 26, positioned from
  // 27), so only the source text is stable across versions. Bounded to before the initializer,
  // where a modifier or the variable name can never be `var`.
  static long varKeywordStart(
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
    final String content = source(cu).orElse(null);
    if (content == null) {
      return -1;
    }

    final long varStart = SourceLocator.findIdentifierFrom(content, declStart, "var");
    if (varStart < 0 || (bound >= 0 && varStart >= bound)) {
      return -1;
    }

    return varStart;
  }

  static boolean isInsideClosure(final TreePath path) {
    TreePath current = path;
    while (current != null) {
      if (current.getLeaf() instanceof LambdaExpressionTree || isAnonymousClass(current)) {
        return true;
      }

      current = current.getParentPath();
    }
    return false;
  }

  static TreePath enclosingStatementToWrap(final TreePath path) {
    TreePath current = path;
    TreePath statement = null;
    while (current != null) {
      if (current.getLeaf() instanceof StatementTree) {
        statement = current;
      }

      if (current.getLeaf() instanceof LambdaExpressionTree
          || isAnonymousClass(current)
          || current.getLeaf() instanceof MethodTree) {
        return statement;
      }

      current = current.getParentPath();
    }
    return null;
  }

  // The innermost enclosing StatementTree, bounded at a method / lambda / anonymous-class boundary
  // (an expression that reaches such a boundary before any statement cannot receive a preceding
  // declaration, e.g. an expression-bodied lambda). Null when no statement encloses the path.
  static TreePath nearestEnclosingStatement(final TreePath path) {
    TreePath current = path;
    while (current != null) {
      if (current.getLeaf() instanceof StatementTree) {
        return current;
      }

      if (current.getLeaf() instanceof LambdaExpressionTree
          || isAnonymousClass(current)
          || current.getLeaf() instanceof MethodTree) {
        return null;
      }

      current = current.getParentPath();
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
      final var pos = SourceLocator.offsetToPosition(cu, anchor);
      return new TextEdit(
          new Range(pos, pos), "%s\n%s".formatted(declaration, lineIndent(source, (int) anchor)));
    }

    final var pos = SourceLocator.offsetToPosition(cu, brace + 1);
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

  private static boolean isAnonymousClass(final TreePath path) {
    if (!(path.getLeaf() instanceof NewClassTree newClassTree)) {
      return false;
    }
    return newClassTree.getClassBody() != null;
  }
}
