package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.AnnotationTree;
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
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;

public final class TokenScanner extends TreePathScanner<Void, Void> {

  public static final List<String> TOKEN_TYPES =
      List.of("enumMember", "method", "property", "typeParameter", "annotation", "namespace");

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
  public Void visitMethod(final MethodTree node, final Void ignored) {
    if (node.getName().contentEquals("<init>")) {
      return super.visitMethod(node, ignored);
    }
    final var element = trees.getElement(getCurrentPath());
    if (element == null) {
      return super.visitMethod(node, ignored);
    }
    final var mods = interestingModifiers(element);
    if (!mods.isEmpty()) {
      final var name = node.getName().toString();
      final long namePos =
          SourceLocator.findIdentifierFrom(content, positions.getStartPosition(cu, node), name);
      if (namePos >= 0) {
        mods.add("declaration");
        addToken(namePos, name.length(), "method", mods);
      }
    }
    return super.visitMethod(node, ignored);
  }

  @Override
  public Void visitVariable(final VariableTree node, final Void ignored) {
    final var element = trees.getElement(getCurrentPath());
    if (element == null) {
      return super.visitVariable(node, ignored);
    }
    final var kind = element.getKind();
    if (kind != ElementKind.ENUM_CONSTANT && kind != ElementKind.FIELD) {
      return super.visitVariable(node, ignored);
    }
    final var mods =
        kind == ElementKind.ENUM_CONSTANT ? new HashSet<String>() : interestingModifiers(element);
    if (kind == ElementKind.ENUM_CONSTANT || !mods.isEmpty()) {
      final var name = node.getName().toString();
      final long namePos =
          SourceLocator.findIdentifierFrom(content, positions.getStartPosition(cu, node), name);
      if (namePos >= 0) {
        final String type = kind == ElementKind.ENUM_CONSTANT ? "enumMember" : "property";
        mods.add("declaration");
        addToken(namePos, name.length(), type, mods);
      }
    }
    return super.visitVariable(node, ignored);
  }

  @Override
  public Void visitIdentifier(final IdentifierTree node, final Void ignored) {
    final var name = node.getName().toString();
    if (name.equals("this") || name.equals("super")) {
      return null;
    }
    final var element = trees.getElement(getCurrentPath());
    if (element != null) {
      emitIfInteresting(element, positions.getStartPosition(cu, node), name.length());
    }
    return null;
  }

  @Override
  public Void visitMemberSelect(final MemberSelectTree node, final Void ignored) {
    scan(node.getExpression(), null);
    final var element = SourceLocator.elementAt(trees, getCurrentPath());
    if (element != null) {
      final long endPos = positions.getEndPosition(cu, node);
      final var name = node.getIdentifier().toString();
      final long nameStart = endPos - name.length();
      if (endPos >= 0 && nameStart >= 0) {
        emitIfInteresting(element, nameStart, name.length());
      }
    }
    return null;
  }

  private void emitIfInteresting(final Element element, final long pos, final int length) {
    final var kind = element.getKind();
    if (kind == ElementKind.TYPE_PARAMETER) {
      addToken(pos, length, "typeParameter", Set.of());
    } else if (kind == ElementKind.ENUM_CONSTANT) {
      addToken(pos, length, "enumMember", Set.of());
    } else if (kind == ElementKind.FIELD || kind == ElementKind.METHOD) {
      final var mods = interestingModifiers(element);
      if (!mods.isEmpty()) {
        addToken(pos, length, kind == ElementKind.FIELD ? "property" : "method", mods);
      }
    }
  }

  private static HashSet<String> interestingModifiers(final Element element) {
    final var mods = new HashSet<String>();
    if (element.getModifiers().contains(Modifier.STATIC)) {
      mods.add("static");
    }
    if (isDeprecated(element)) {
      mods.add("deprecated");
    }
    return mods;
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
