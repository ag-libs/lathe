package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExportsTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.OpensTree;
import com.sun.source.tree.RequiresTree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;

public final class TokenScanner extends TreePathScanner<Void, Void> {

  public static final List<String> TOKEN_TYPES =
      List.of(
          "enumMember",
          "method",
          "property",
          "typeParameter",
          "annotation",
          "namespace",
          "class",
          "interface",
          "enum",
          "parameter",
          "variable");

  public static final List<String> TOKEN_MODIFIERS = List.of("declaration", "static", "deprecated");

  private final Trees trees;
  private final CompilationUnitTree cu;
  private final SourcePositions positions;
  private final String content;
  private final List<SemanticToken> tokens = new ArrayList<>();

  private TokenScanner(final Trees trees, final CompilationUnitTree cu, final String content) {
    this.trees = trees;
    this.cu = cu;
    this.positions = trees.getSourcePositions();
    this.content = content;
  }

  public static List<SemanticToken> scan(final Trees trees, final CompilationUnitTree cu)
      throws IOException {
    final var scanner =
        new TokenScanner(trees, cu, cu.getSourceFile().getCharContent(true).toString());
    scanner.scan(cu, null);
    scanner.tokens.sort(
        Comparator.comparingInt(SemanticToken::line).thenComparingInt(SemanticToken::character));
    return List.copyOf(scanner.tokens);
  }

  public static int[] encode(final List<SemanticToken> tokens) {
    final var data = new int[tokens.size() * 5];
    int prevLine = 0;
    int prevChar = 0;
    int i = 0;
    for (final var tok : tokens) {
      final int deltaLine = tok.line() - prevLine;
      final int deltaChar = deltaLine == 0 ? tok.character() - prevChar : tok.character();
      data[i++] = deltaLine;
      data[i++] = deltaChar;
      data[i++] = tok.length();
      data[i++] = TOKEN_TYPES.indexOf(tok.type());
      final int modBits =
          tok.modifiers().stream()
              .mapToInt(TOKEN_MODIFIERS::indexOf)
              .filter(idx -> idx >= 0)
              .reduce(0, (acc, idx) -> acc | (1 << idx));
      data[i++] = modBits;
      prevLine = tok.line();
      prevChar = tok.character();
    }
    return data;
  }

  @Override
  public Void visitCompilationUnit(final CompilationUnitTree node, final Void ignored) {
    if (node.getModule() == null && node.getTypeDecls().isEmpty()) {
      addNamespaceToken(node.getPackageName());
    }

    return super.visitCompilationUnit(node, ignored);
  }

  @Override
  public Void visitModule(final ModuleTree node, final Void ignored) {
    addNamespaceToken(node.getName());
    for (final var directive : node.getDirectives()) {
      switch (directive.getKind()) {
        case REQUIRES -> addNamespaceToken(((RequiresTree) directive).getModuleName());
        case EXPORTS -> addNamespaceToken(((ExportsTree) directive).getPackageName());
        case OPENS -> addNamespaceToken(((OpensTree) directive).getPackageName());
        default -> {}
      }
    }
    return super.visitModule(node, ignored);
  }

  private void addNamespaceToken(final ExpressionTree tree) {
    if (tree == null) {
      return;
    }
    final long start = positions.getStartPosition(cu, tree);
    final long end = positions.getEndPosition(cu, tree);
    if (start >= 0 && end > start) {
      addToken(start, (int) (end - start), "namespace", Set.of());
    }
  }

  @Override
  public Void visitAnnotation(final AnnotationTree node, final Void ignored) {
    final var typeTree = node.getAnnotationType();
    final String simpleName;
    if (typeTree instanceof final IdentifierTree id) {
      simpleName = id.getName().toString();
    } else if (typeTree instanceof final MemberSelectTree ms) {
      simpleName = ms.getIdentifier().toString();
    } else {
      return super.visitAnnotation(node, ignored);
    }
    final long annotationStart = positions.getStartPosition(cu, node);
    if (annotationStart >= 0) {
      final long namePos = SourceLocator.findIdentifierFrom(content, annotationStart, simpleName);
      if (namePos >= 0) {
        addToken(namePos, simpleName.length(), "annotation", Set.of());
      }
    }
    return super.visitAnnotation(node, ignored);
  }

  @Override
  public Void visitTypeParameter(final TypeParameterTree node, final Void ignored) {
    final long startPos = positions.getStartPosition(cu, node);
    if (startPos >= 0) {
      addToken(startPos, node.getName().length(), "typeParameter", Set.of("declaration"));
    }
    return super.visitTypeParameter(node, ignored);
  }

  @Override
  public Void visitClass(final ClassTree node, final Void ignored) {
    final var element = trees.getElement(getCurrentPath());
    final var name = node.getSimpleName().toString();
    final String type = element == null ? null : typeTokenType(element.getKind());
    if (type != null && !name.isEmpty()) {
      emitDeclaration(positions.getStartPosition(cu, node), name, type, Set.of());
    }
    return super.visitClass(node, ignored);
  }

  @Override
  public Void visitMethod(final MethodTree node, final Void ignored) {
    if (node.getName().contentEquals("<init>")) {
      return super.visitMethod(node, ignored);
    }
    final var element = trees.getElement(getCurrentPath());
    if (element != null) {
      emitDeclaration(
          positions.getStartPosition(cu, node),
          node.getName().toString(),
          "method",
          interestingModifiers(element));
    }
    return super.visitMethod(node, ignored);
  }

  @Override
  public Void visitVariable(final VariableTree node, final Void ignored) {
    // Synthetic declarations (a record component's implicit parameter and field share the header
    // offset; the parameter carries no end position) — skip without descending to avoid emitting
    // duplicate tokens for the component name and its type.
    if (positions.getEndPosition(cu, node) < 0) {
      return null;
    }

    final var element = trees.getElement(getCurrentPath());
    if (element == null) {
      return super.visitVariable(node, ignored);
    }

    final var kind = element.getKind();
    final String type = declarationTokenType(kind);
    if (type != null) {
      final Set<String> baseModifiers =
          kind == ElementKind.ENUM_CONSTANT ? Set.of() : interestingModifiers(element);
      emitDeclaration(
          positions.getStartPosition(cu, node), node.getName().toString(), type, baseModifiers);
    }
    return super.visitVariable(node, ignored);
  }

  // Emits a declaration-site token: locates the name from the declaration start, then tags it with
  // `declaration` on top of any element modifiers. Shared by class, method, and variable decls.
  private void emitDeclaration(
      final long declStart, final String name, final String type, final Set<String> baseModifiers) {
    final long namePos = SourceLocator.findIdentifierFrom(content, declStart, name);
    if (namePos < 0) {
      return;
    }

    final Set<String> modifiers =
        Stream.concat(baseModifiers.stream(), Stream.of("declaration"))
            .collect(Collectors.toUnmodifiableSet());
    addToken(namePos, name.length(), type, modifiers);
  }

  @Override
  public Void visitIdentifier(final IdentifierTree node, final Void ignored) {
    final var name = node.getName().toString();
    if (name.equals("this") || name.equals("super")) {
      return null;
    }
    final var element = trees.getElement(getCurrentPath());
    if (element != null) {
      emitReference(element, positions.getStartPosition(cu, node), name);
    }
    return null;
  }

  @Override
  public Void visitMemberSelect(final MemberSelectTree node, final Void ignored) {
    scan(node.getExpression(), null);
    // The exact element of this selector — not SourceLocator.elementAt, which climbs a package
    // qualifier up to the enclosing type and would mislabel `java.util` in `java.util.List` as a
    // type. A package selector resolves to a PACKAGE here and emits no token.
    final var element = trees.getElement(getCurrentPath());
    if (element != null) {
      final var name = node.getIdentifier().toString();
      emitReference(element, positions.getEndPosition(cu, node) - name.length(), name);
    }
    return null;
  }

  // Emits a reference-site token only when the source at the computed range actually spells the
  // name. This rejects synthetic identifiers whose position points elsewhere — notably an
  // annotation's implicit `value` element, which resolves to a method but sits on the argument.
  private void emitReference(final Element element, final long pos, final String name) {
    if (pos >= 0
        && pos + name.length() <= content.length()
        && content.regionMatches((int) pos, name, 0, name.length())) {
      emitIfInteresting(element, pos, name.length());
    }
  }

  private void emitIfInteresting(final Element element, final long pos, final int length) {
    final var kind = element.getKind();
    final String typeToken = typeTokenType(kind);
    if (typeToken != null) {
      addToken(pos, length, typeToken, Set.of());
      return;
    }

    switch (kind) {
      case TYPE_PARAMETER -> addToken(pos, length, "typeParameter", Set.of());
      case ENUM_CONSTANT -> addToken(pos, length, "enumMember", Set.of());
      case PARAMETER -> addToken(pos, length, "parameter", Set.of());
      case LOCAL_VARIABLE, RESOURCE_VARIABLE, BINDING_VARIABLE, EXCEPTION_PARAMETER ->
          addToken(pos, length, "variable", Set.of());
      case FIELD -> addToken(pos, length, "property", interestingModifiers(element));
      case METHOD -> addToken(pos, length, "method", interestingModifiers(element));
      default -> {}
    }
  }

  // The token type for a type declaration or reference, or null for non-type elements.
  private static String typeTokenType(final ElementKind kind) {
    return switch (kind) {
      case CLASS, RECORD -> "class";
      case INTERFACE, ANNOTATION_TYPE -> "interface";
      case ENUM -> "enum";
      default -> null;
    };
  }

  // The token type for a variable declaration (`visitVariable`), or null for other declarations.
  private static String declarationTokenType(final ElementKind kind) {
    return switch (kind) {
      case ENUM_CONSTANT -> "enumMember";
      case FIELD -> "property";
      case PARAMETER -> "parameter";
      case LOCAL_VARIABLE, RESOURCE_VARIABLE, BINDING_VARIABLE, EXCEPTION_PARAMETER -> "variable";
      default -> null;
    };
  }

  private static Set<String> interestingModifiers(final Element element) {
    final var mods = new HashSet<String>();
    if (element.getModifiers().contains(Modifier.STATIC)) {
      mods.add("static");
    }
    if (isDeprecated(element)) {
      mods.add("deprecated");
    }
    return Set.copyOf(mods);
  }

  private static boolean isDeprecated(final Element element) {
    return element.getAnnotationMirrors().stream()
        .anyMatch(a -> a.getAnnotationType().toString().equals("java.lang.Deprecated"));
  }

  private void addToken(
      final long absoluteOffset, final int length, final String type, final Set<String> modifiers) {
    if (absoluteOffset < 0 || length <= 0) {
      return;
    }
    final var lspPos = SourceLocator.offsetToPosition(cu, absoluteOffset);
    tokens.add(new SemanticToken(lspPos.getLine(), lspPos.getCharacter(), length, type, modifiers));
  }
}
