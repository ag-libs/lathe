package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

public final class SourceLocator {

  private static final String LOMBOK_GENERATED = "lombok.Generated";

  private SourceLocator() {}

  public static long toOffset(final CompilationUnitTree cu, final int line, final int character) {
    try {
      return cu.getLineMap().getPosition(line + 1, character + 1);
    } catch (final ArrayIndexOutOfBoundsException e) {
      return Diagnostic.NOPOS;
    }
  }

  public static Position offsetToPosition(final CompilationUnitTree cu, final long offset) {
    final var lineMap = cu.getLineMap();
    try {
      return new Position(
          (int) lineMap.getLineNumber(offset) - 1, (int) lineMap.getColumnNumber(offset) - 1);
    } catch (final ArrayIndexOutOfBoundsException e) {
      // A synthesized node reports NOPOS (-1), which the line map rejects. Mirror toOffset's guard
      // and fall back to the origin so one positionless node can't fail a whole feature.
      return new Position(0, 0);
    }
  }

  public static Range range(final Trees trees, final CompilationUnitTree cu, final Tree node) {
    final var positions = trees.getSourcePositions();
    final long start = positions.getStartPosition(cu, node);
    final long end = positions.getEndPosition(cu, node);
    return new Range(offsetToPosition(cu, start), offsetToPosition(cu, end));
  }

  public static long declarationStartOffset(final Trees trees, final Element element) {
    final TreePath path = trees.getPath(element);
    return path == null
        ? -1
        : trees.getSourcePositions().getStartPosition(path.getCompilationUnit(), path.getLeaf());
  }

  public static TreePath pathAt(
      final Trees trees, final CompilationUnitTree cu, final long offset) {
    final var positions = trees.getSourcePositions();
    final var result = new AtomicReference<TreePath>();
    final var size = new AtomicLong(Long.MAX_VALUE);

    new TreePathScanner<Void, Void>() {
      @Override
      public Void scan(final Tree tree, final Void unused) {
        if (tree == null || isGenerated(tree)) {
          return null;
        }
        final long start = positions.getStartPosition(cu, tree);
        final long end = positions.getEndPosition(cu, tree);
        if (start <= offset && offset < end) {
          final long span = end - start;
          if (span <= size.get()) {
            size.set(span);
            result.set(new TreePath(getCurrentPath(), tree));
          }
        }
        return super.scan(tree, unused);
      }
    }.scan(cu, null);

    return result.get();
  }

  public static Position offsetToPosition(final String content, final long offset) {
    final int limit = (int) Math.min(offset, content.length());
    int line = 0;
    int col = 0;
    for (int i = 0; i < limit; i++) {
      if (content.charAt(i) == '\n') {
        line++;
        col = 0;
      } else {
        col++;
      }
    }
    return new Position(line, col);
  }

  // Rebuild the document from the original: for each edit (in document order) emit the untouched
  // gap before it plus its replacement, then the final untouched tail. Edits never overlap, so the
  // gaps are well-defined and offsets need no adjusting for earlier edits.
  public static String applyEdits(final String content, final List<? extends TextEdit> edits) {
    final List<TextEdit> ordered =
        edits.stream()
            .map(TextEdit.class::cast)
            .sorted(Comparator.comparingInt(e -> offset(content, e.getRange().getStart())))
            .toList();
    return IntStream.rangeClosed(0, ordered.size())
        .mapToObj(i -> segment(content, ordered, i))
        .collect(Collectors.joining());
  }

  private static String segment(
      final String content, final List<TextEdit> ordered, final int index) {
    final int from = index == 0 ? 0 : offset(content, ordered.get(index - 1).getRange().getEnd());
    if (index == ordered.size()) {
      return content.substring(from); // the untouched tail after the last edit
    }

    final TextEdit edit = ordered.get(index);
    final int to = offset(content, edit.getRange().getStart());
    return "%s%s".formatted(content.substring(from, to), edit.getNewText());
  }

  private static int offset(final String content, final Position position) {
    return toOffset(content, position.getLine(), position.getCharacter());
  }

  /** The offset of a 0-based {@code line}/{@code character} in raw text, before it is parsed. */
  public static int toOffset(final String content, final int line, final int character) {
    int offset = 0;
    int currentLine = 0;
    for (int i = 0; i < content.length() && currentLine < line; i++) {
      if (content.charAt(i) == '\n') {
        currentLine++;
        offset = i + 1;
      }
    }

    return offset + character;
  }

  public static Optional<Position> declarationNamePosition(
      final Trees trees, final CompilationUnitTree cu, final TreePath path, final String name)
      throws IOException {
    if (path == null) {
      return Optional.empty();
    }
    final long declStart = trees.getSourcePositions().getStartPosition(cu, path.getLeaf());
    if (declStart == Diagnostic.NOPOS) {
      return Optional.empty();
    }
    final CharSequence content = cu.getSourceFile().getCharContent(false);
    final long idx =
        declarationNameOffset(
            content.toString(), trees.getSourcePositions(), cu, path.getLeaf(), name);
    final long nameOffset = idx >= 0 ? idx : declStart;
    return Optional.of(offsetToPosition(cu, nameOffset));
  }

  // A declaration's name offset, or -1. javac's public API gives where a declaration starts, not
  // where its name is, so the name is found in the text, searched for after the declaration's
  // modifiers and type: an annotation argument or a type argument there may spell the same word
  // (@JsonProperty("name") String name). A comment between the type and the name still can; no
  // public API reads comments, and none was found in Helidon, Dropwizard or EqualsVerifier.
  public static long declarationNameOffset(
      final String content,
      final SourcePositions positions,
      final CompilationUnitTree cu,
      final Tree declaration,
      final String name) {
    final long start = positions.getStartPosition(cu, declaration);
    if (start < 0) {
      return -1;
    }

    final long afterPrefix =
        findIdentifierFrom(content, Math.max(start, prefixEnd(positions, cu, declaration)), name);
    return afterPrefix >= 0 ? afterPrefix : findIdentifierFrom(content, start, name);
  }

  // Whether a name sits exactly at offset: an identifier, the name ending a member select or method
  // reference, a declaration's own name, or a Javadoc reference. A word in a comment or a literal
  // is none of these, though it looks like a name: javac has no tree there, and answers for the
  // enclosing one (the method, the class, the variable a string is assigned to).
  public static boolean onName(
      final AttributedFileAnalysis analysis, final TreePath path, final long offset)
      throws IOException {
    if (path != null && nameCovers(analysis, path, offset)) {
      return true;
    }

    return JavadocReferenceResolver.referenceAt(analysis, offset) != null;
  }

  private static boolean nameCovers(
      final AttributedFileAnalysis analysis, final TreePath path, final long offset)
      throws IOException {
    final Tree leaf = path.getLeaf();
    if (leaf instanceof IdentifierTree) {
      return true;
    }

    final SourcePositions positions = analysis.trees().getSourcePositions();
    final CompilationUnitTree cu = analysis.tree();
    final long end = positions.getEndPosition(cu, leaf);
    if (leaf instanceof final MemberSelectTree select) {
      return within(offset, end - select.getIdentifier().length(), select.getIdentifier().length());
    }

    if (leaf instanceof final MemberReferenceTree reference) {
      return within(offset, end - reference.getName().length(), reference.getName().length());
    }

    final String name = declaredName(path);
    if (name == null || name.isEmpty()) {
      return false; // not a declaration, or an anonymous class
    }

    final String content = cu.getSourceFile().getCharContent(false).toString();
    return within(offset, declarationNameOffset(content, positions, cu, leaf, name), name.length());
  }

  private static boolean within(final long offset, final long start, final int length) {
    return start >= 0 && offset >= start && offset < start + length;
  }

  // The name a declaration declares, as written; a constructor's is its class's.
  private static String declaredName(final TreePath path) {
    final Tree leaf = path.getLeaf();
    if (leaf instanceof final ClassTree type) {
      return type.getSimpleName().toString();
    }

    if (leaf instanceof final VariableTree variable) {
      return variable.getName().toString();
    }

    if (leaf instanceof final TypeParameterTree parameter) {
      return parameter.getName().toString();
    }

    if (!(leaf instanceof final MethodTree method)) {
      return null;
    }

    if (method.getName().contentEquals("<init>")
        && path.getParentPath().getLeaf() instanceof final ClassTree owner) {
      return owner.getSimpleName().toString();
    }

    return method.getName().toString();
  }

  // Where the part of a declaration before its name ends: its type (a variable's, a method's return
  // type), else its modifiers; -1 where javac has no position for them (var, an implicit lambda
  // parameter, a constructor without modifiers).
  private static long prefixEnd(
      final SourcePositions positions, final CompilationUnitTree cu, final Tree declaration) {
    final long typeEnd = endOf(positions, cu, typeOf(declaration));
    return typeEnd >= 0 ? typeEnd : endOf(positions, cu, modifiersOf(declaration));
  }

  private static Tree typeOf(final Tree declaration) {
    if (declaration instanceof final VariableTree variable) {
      return variable.getType();
    }

    return declaration instanceof final MethodTree method ? method.getReturnType() : null;
  }

  // Lombok marks each member it generates with @lombok.Generated, unless lombok.config turns that
  // off. Such a member takes the span of the code it comes from (a field's name, the annotation),
  // so
  // what maps source positions to trees skips it.
  public static boolean isGenerated(final Tree tree) {
    final ModifiersTree modifiers = modifiersOf(tree);
    return modifiers != null
        && modifiers.getAnnotations().stream()
            .anyMatch(
                annotation -> annotation.getAnnotationType().toString().equals(LOMBOK_GENERATED));
  }

  // The same mark on an element, which a class file keeps too (lombok.Generated has class
  // retention).
  public static boolean isGenerated(final Element element) {
    return element.getAnnotationMirrors().stream()
        .anyMatch(annotation -> annotation.getAnnotationType().toString().equals(LOMBOK_GENERATED));
  }

  // Where a member Lombok generates comes from, as written: the field an accessor reads or writes,
  // else the first class around it that Lombok did not generate. Accessors are matched by Lombok's
  // default names; other naming from lombok.config lands on the class.
  public static Element generatedSource(final Element generated) {
    Element owner = generated.getEnclosingElement();
    while (isGenerated(owner)) {
      owner = owner.getEnclosingElement();
    }

    final String name = generated.getSimpleName().toString();
    return owner.getEnclosedElements().stream()
        .filter(member -> member.getKind() == ElementKind.FIELD && !isGenerated(member))
        .filter(field -> accessorNames(field.getSimpleName().toString()).contains(name))
        .findFirst()
        .map(Element.class::cast)
        .orElse(owner);
  }

  // The members Lombok generated from a field: its getter, setter and wither.
  public static List<Element> generatedAccessors(final Element field) {
    return field.getEnclosingElement().getEnclosedElements().stream()
        .filter(member -> member.getKind() == ElementKind.METHOD && isGenerated(member))
        .filter(method -> generatedSource(method).equals(field))
        .map(Element.class::cast)
        .toList();
  }

  // Lombok's default accessor names for a field: get/is/set/with and the capitalized name, or the
  // field's own name (fluent); a boolean isActive gets isActive() and setActive().
  private static Set<String> accessorNames(final String field) {
    final String property =
        field.length() > 2 && field.startsWith("is") && Character.isUpperCase(field.charAt(2))
            ? field.substring(2)
            : Character.toUpperCase(field.charAt(0)) + field.substring(1);
    return Stream.of(field, "get" + property, "is" + property, "set" + property, "with" + property)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static ModifiersTree modifiersOf(final Tree declaration) {
    if (declaration instanceof final VariableTree variable) {
      return variable.getModifiers();
    }

    if (declaration instanceof final MethodTree method) {
      return method.getModifiers();
    }

    return declaration instanceof final ClassTree type ? type.getModifiers() : null;
  }

  private static long endOf(
      final SourcePositions positions, final CompilationUnitTree cu, final Tree tree) {
    return tree == null ? -1 : positions.getEndPosition(cu, tree);
  }

  public static Element elementAt(final Trees trees, final TreePath path) {
    TreePath p = path;
    while (p != null) {
      final var element = trees.getElement(p);
      if (element != null && element.getKind() != ElementKind.PACKAGE) {
        return element;
      }
      if (p.getLeaf() instanceof final MethodInvocationTree inv) {
        final var sel = trees.getElement(new TreePath(p, inv.getMethodSelect()));
        if (sel != null) {
          return sel;
        }
      }
      if (p.getLeaf() instanceof final ImportTree imp && imp.isStatic()) {
        return resolveStaticImportMember(trees, p, imp);
      }
      p = p.getParentPath();
    }
    return null;
  }

  /**
   * The invoked constructor when {@code path} sits on the type-name of a {@code new XXX(...)}
   * expression, else null. {@link #elementAt} resolves that identifier to the type element (all its
   * uses), so references/definition callers consult this first to prefer the constructor's call
   * sites. Returns null when the cursor is on the arguments or class body of the {@code new}, or
   * when the enclosing element is not a constructor.
   */
  static Element constructorAtNewClassType(final Trees trees, final TreePath path) {
    TreePath child = null;
    for (TreePath p = path; p != null; p = p.getParentPath()) {
      if (p.getLeaf() instanceof final NewClassTree nc) {
        if (child == null || child.getLeaf() != nc.getIdentifier()) {
          return null;
        }

        final var element = trees.getElement(p);
        return element != null && element.getKind() == ElementKind.CONSTRUCTOR ? element : null;
      }

      child = p;
    }

    return null;
  }

  public static TreePath declarationPath(final CompilationUnitTree cu, final Element target) {
    if (target == null) {
      return null;
    }
    final var result = new AtomicReference<TreePath>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitClass(final ClassTree tree, final Void unused) {
        if ((target.getKind().isClass() || target.getKind().isInterface())
            && tree.getSimpleName().contentEquals(target.getSimpleName())) {
          result.set(getCurrentPath());
        }
        return super.visitClass(tree, unused);
      }

      @Override
      public Void visitMethod(final MethodTree tree, final Void unused) {
        final boolean nameMatch =
            target.getKind() == ElementKind.CONSTRUCTOR
                ? tree.getName().contentEquals("<init>")
                : tree.getName().contentEquals(declarationName(target));
        if ((target.getKind() == ElementKind.METHOD || target.getKind() == ElementKind.CONSTRUCTOR)
            && nameMatch
            && matchParameters(
                tree.getParameters(), ((ExecutableElement) target).getParameters())) {
          result.set(getCurrentPath());
        }
        return super.visitMethod(tree, unused);
      }

      @Override
      public Void visitVariable(final VariableTree tree, final Void unused) {
        if ((target.getKind() == ElementKind.FIELD || target.getKind() == ElementKind.ENUM_CONSTANT)
            && tree.getName().contentEquals(target.getSimpleName())
            && getCurrentPath().getParentPath().getLeaf() instanceof ClassTree) {
          result.set(getCurrentPath());
        }
        return super.visitVariable(tree, unused);
      }
    }.scan(cu, null);
    return result.get();
  }

  private static boolean matchParameters(
      final List<? extends VariableTree> treeParams,
      final List<? extends VariableElement> targetParams) {
    if (treeParams.size() != targetParams.size()) {
      return false;
    }

    return IntStream.range(0, treeParams.size())
        .allMatch(
            i -> {
              final String treeType = simplifyType(treeParams.get(i).getType().toString());
              final String targetType = simplifyType(targetParams.get(i).asType().toString());
              return treeType.equals(targetType);
            });
  }

  private static String simplifyType(final String typeStr) {
    return typeStr.replaceAll("[a-zA-Z0-9_]+\\.", "").replaceAll("\\s+", "").replace("...", "[]");
  }

  public static CharSequence declarationName(final Element element) {
    return element.getKind() == ElementKind.CONSTRUCTOR
        ? element.getEnclosingElement().getSimpleName()
        : element.getSimpleName();
  }

  private static Element resolveStaticImportMember(
      final Trees trees, final TreePath impPath, final ImportTree imp) {
    if (!(imp.getQualifiedIdentifier() instanceof final MemberSelectTree ms)) {
      return null;
    }
    final var msPath = new TreePath(impPath, ms);
    final var exprPath = new TreePath(msPath, ms.getExpression());
    final var typeMirror = trees.getTypeMirror(exprPath);
    if (!(typeMirror instanceof final DeclaredType dt)) {
      return null;
    }
    final var typeElem = (TypeElement) dt.asElement();
    final var memberName = ms.getIdentifier().toString();
    return typeElem.getEnclosedElements().stream()
        .filter(e -> e.getSimpleName().contentEquals(memberName))
        .findFirst()
        .orElse(null);
  }

  public static VariableElement parameterElementAt(final Trees trees, final TreePath path) {
    if (path == null) {
      return null;
    }
    final var leafElement = trees.getElement(path);
    if (leafElement != null && masksParameterContext(leafElement)) {
      return null;
    }
    TreePath argPath = path;
    TreePath parent = path.getParentPath();
    while (parent != null) {
      // Inside a method reference the cursor names its own symbol (the receiver of `x::m`, or m),
      // not the argument value the callee parameter describes.
      if (argPath.getLeaf() instanceof MemberReferenceTree) {
        return null;
      }

      final var leaf = parent.getLeaf();
      if (leaf instanceof final MethodInvocationTree inv) {
        final int idx = indexIn(inv.getArguments(), argPath.getLeaf());
        if (idx < 0) {
          return null;
        }
        final var callee =
            (ExecutableElement) trees.getElement(new TreePath(parent, inv.getMethodSelect()));
        if (callee == null) {
          return null;
        }
        final var params = callee.getParameters();
        return idx < params.size() ? params.get(idx) : null;
      }
      if (leaf instanceof final NewClassTree nc) {
        final int idx = indexIn(nc.getArguments(), argPath.getLeaf());
        if (idx < 0) {
          return null;
        }
        final var callee = (ExecutableElement) trees.getElement(parent);
        if (callee == null) {
          return null;
        }
        final var params = callee.getParameters();
        return idx < params.size() ? params.get(idx) : null;
      }
      argPath = parent;
      parent = parent.getParentPath();
    }
    return null;
  }

  /**
   * Suppresses the argument param-hint when the cursor's own element is a named symbol worth
   * showing on its own — types, fields, enum constants, and a record component referenced inside
   * the compact constructor. javac models that component reference as the canonical-constructor
   * {@code PARAMETER}, so without this it would show the callee's parameter instead of the
   * component (as a backing-field read elsewhere in the record body already does).
   */
  private static boolean masksParameterContext(final Element element) {
    return switch (element.getKind()) {
      case ANNOTATION_TYPE, CLASS, ENUM, ENUM_CONSTANT, FIELD, INTERFACE, RECORD -> true;
      case PARAMETER -> isRecordComponentParameter(element);
      default -> false;
    };
  }

  private static boolean isRecordComponentParameter(final Element parameter) {
    final var constructor = parameter.getEnclosingElement();
    if (constructor.getKind() != ElementKind.CONSTRUCTOR) {
      return false;
    }

    final var owner = constructor.getEnclosingElement();
    if (owner.getKind() != ElementKind.RECORD) {
      return false;
    }

    final List<? extends RecordComponentElement> components =
        ((TypeElement) owner).getRecordComponents();
    final List<? extends VariableElement> params =
        ((ExecutableElement) constructor).getParameters();
    return params.size() == components.size()
        && IntStream.range(0, params.size())
            .allMatch(
                i ->
                    params.get(i).getSimpleName().contentEquals(components.get(i).getSimpleName()));
  }

  public static int identifierEnd(final String content, final int start) {
    int i = start;
    while (i < content.length() && Character.isJavaIdentifierPart(content.charAt(i))) {
      i++;
    }
    return i;
  }

  public static int identifierStart(final String content, final int end) {
    int i = end;
    while (i > 0 && Character.isJavaIdentifierPart(content.charAt(i - 1))) {
      i--;
    }
    return i;
  }

  public static long findIdentifierFrom(
      final String content, final long fromOffset, final String name) {
    final int nameLen = name.length();
    final int limit = content.length() - nameLen;
    for (int i = (int) fromOffset; i <= limit; i++) {
      if (!content.regionMatches(i, name, 0, nameLen)) {
        continue;
      }

      final boolean leftBound = i == 0 || !Character.isJavaIdentifierPart(content.charAt(i - 1));
      final boolean rightBound =
          i + nameLen >= content.length()
              || !Character.isJavaIdentifierPart(content.charAt(i + nameLen));
      if (leftBound && rightBound) {
        return i;
      }
    }
    return -1;
  }

  private static int indexIn(final List<? extends Tree> list, final Tree target) {
    return IntStream.range(0, list.size())
        .filter(i -> list.get(i) == target)
        .findFirst()
        .orElse(-1);
  }
}
