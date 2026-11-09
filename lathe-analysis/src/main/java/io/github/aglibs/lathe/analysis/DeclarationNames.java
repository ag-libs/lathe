package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberReferenceTree.ReferenceMode;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.tools.Diagnostic;
import org.eclipse.lsp4j.Position;

// Where a declaration's name is in the source text, and the identifier around an offset: what
// rename, definition and the selection ranges anchor on.
public final class DeclarationNames {

  private static final Logger LOG = Logger.getLogger(DeclarationNames.class.getName());

  static final String VAR = "var";

  private DeclarationNames() {}

  public static long declarationStartOffset(final Trees trees, final Element element) {
    final TreePath path = trees.getPath(element);
    return path == null
        ? -1
        : trees.getSourcePositions().getStartPosition(path.getCompilationUnit(), path.getLeaf());
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
    return Optional.of(Positions.offsetToPosition(cu, nameOffset));
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
    return typeEnd >= 0 ? typeEnd : endOf(positions, cu, TreePaths.modifiersOf(declaration));
  }

  private static Tree typeOf(final Tree declaration) {
    if (declaration instanceof final VariableTree variable) {
      return variable.getType();
    }

    return declaration instanceof final MethodTree method ? method.getReturnType() : null;
  }

  private static long endOf(
      final SourcePositions positions, final CompilationUnitTree cu, final Tree tree) {
    return tree == null ? -1 : positions.getEndPosition(cu, tree);
  }

  public static CharSequence declarationName(final Element element) {
    return element.getKind() == ElementKind.CONSTRUCTOR
        ? element.getEnclosingElement().getSimpleName()
        : element.getSimpleName();
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

  // javac names a constructor reference `<init>`; the source says `new`
  static String referenceName(final MemberReferenceTree node) {
    return node.getMode() == ReferenceMode.NEW ? "new" : node.getName().toString();
  }

  // The source offset of a declaration's `var` keyword, or -1 when its type is written out or the
  // source cannot be read. The type tree says which: attribution replaces `var` with the inferred
  // type, which has no source position through JDK 26 and sits on the keyword from 27, while a
  // written type always has one. Only then is the keyword looked for, after the modifiers, so an
  // annotation's `"var"` or a variable named `var` is never taken for it.
  public static long varKeywordStart(
      final AttributedFileAnalysis analysis, final VariableTree varTree) {
    final var positions = analysis.trees().getSourcePositions();
    final CompilationUnitTree cu = analysis.tree();
    final Optional<String> source = source(cu);
    if (source.isEmpty()) {
      return -1;
    }

    final String content = source.get();
    final long typeStart =
        varTree.getType() == null ? -1 : positions.getStartPosition(cu, varTree.getType());
    if (typeStart >= 0) {
      return findIdentifierFrom(content, typeStart, VAR) == typeStart ? typeStart : -1;
    }

    final long from =
        Math.max(
            positions.getStartPosition(cu, varTree),
            positions.getEndPosition(cu, varTree.getModifiers()));
    final long bound =
        varTree.getInitializer() != null
            ? positions.getStartPosition(cu, varTree.getInitializer())
            : positions.getEndPosition(cu, varTree);
    final long varStart = from < 0 ? -1 : findIdentifierFrom(content, from, VAR);
    return varStart < 0 || (bound >= 0 && varStart >= bound) ? -1 : varStart;
  }

  // The compilation unit's source text, empty when it cannot be read (logged).
  private static Optional<String> source(final CompilationUnitTree cu) {
    try {
      return Optional.of(cu.getSourceFile().getCharContent(false).toString());
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING, e, () -> "[names] %s read failed".formatted(cu.getSourceFile().toUri()));
      return Optional.empty();
    }
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
}
