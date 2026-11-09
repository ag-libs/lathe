package io.github.aglibs.lathe.analysis.completion;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.CursorElements;
import io.github.aglibs.lathe.analysis.internal.SourceTypeNames;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;

// The type of the receiver a member access completes on (`receiver.|`), the preceding expression's
// type, and the scope at the cursor. Prefers the attributed tree at the receiver's position; see
// resolveReceiver for the text fallback a stale snapshot needs.
final class ReceiverResolver {

  private static final Logger LOG = Logger.getLogger(ReceiverResolver.class.getName());

  private ReceiverResolver() {}

  static ResolvedReceiver resolveReceiver(
      final ParsedSentinel sentinel, final int cursorLine, final AttributedFileAnalysis snapshot) {

    // Primary: AST-based resolution using the position from the injected parse
    if (sentinel.receiverEndOffset() >= 0) {
      final var resolved = resolveByPosition(sentinel.receiverEndOffset(), snapshot);
      if (resolved != null) {
        return resolved;
      }
    }

    // Fallback, the one place completion reads Java from text (AGENTS.md allows it so scoped):
    // completion fires right after the dot, against a snapshot attributed before it, whose tree may
    // hold no receiver at the completion's position. The receiver text the sentinel injection
    // captured is classified only as far as needed (a string literal, this/super, a qualified or
    // capitalised type name, a simple name); anything more (a call, an index, a cast) gives up and
    // waits for the reattributed snapshot. Names resolve through javac: elements by name, and the
    // declarations NameLookup finds in the snapshot's trees. ReceiverResolverTest pins each case.
    final var text = sentinel.receiverText();
    if (text == null) {
      return null;
    }

    if (text.startsWith("\"")) {
      final var el = snapshot.elements().getTypeElement("java.lang.String");
      return el != null ? new ResolvedReceiver(el.asType(), false) : null;
    }

    if ("this".equals(text)) {
      final var classEl = NameLookup.findClassElement(sentinel.enclosingClass(), snapshot);
      return classEl != null ? new ResolvedReceiver(classEl.asType(), false) : null;
    }

    if ("super".equals(text)) {
      final var classEl = NameLookup.findClassElement(sentinel.enclosingClass(), snapshot);
      return classEl != null ? new ResolvedReceiver(classEl.getSuperclass(), false) : null;
    }

    // Complex expressions — position-based already tried above, cannot do better
    if (text.indexOf('(') >= 0 || text.indexOf('[') >= 0 || text.indexOf(' ') >= 0) {
      return null;
    }

    // Dotted name — FQN type lookup (type reference = static access)
    if (text.indexOf('.') >= 0) {
      final var el = snapshot.elements().getTypeElement(text);
      return el != null ? new ResolvedReceiver(el.asType(), true) : null;
    }

    // Capitalized simple name — type reference (static access); if no type found, fall through
    // to field lookup, since all-uppercase names like LOGGER are common static field names.
    if (Character.isUpperCase(text.charAt(0))) {
      final var el = snapshot.elements().getTypeElement(text);
      if (el != null) {
        return new ResolvedReceiver(el.asType(), true);
      }

      final var langEl = snapshot.elements().getTypeElement("java.lang." + text);
      if (langEl != null) {
        return new ResolvedReceiver(langEl.asType(), true);
      }
    }

    // Simple name — local variable, parameter, or field (instance access)
    final var localType =
        NameLookup.scanForLocalDeclaration(
            text, sentinel.enclosingClass(), sentinel.enclosingMethod(), cursorLine, snapshot);
    if (localType != null) {
      return new ResolvedReceiver(localType, false);
    }

    final var fieldType = NameLookup.findFieldType(text, sentinel.enclosingClass(), snapshot);
    if (fieldType != null) {
      return new ResolvedReceiver(fieldType, false);
    }

    return null;
  }

  // How code at the scope names a type: written into its enclosing class, or none outside every
  // class (an import, a header).
  static SourceTypeNames typeNames(final AttributedFileAnalysis analysis, final Scope scope) {
    return new SourceTypeNames(analysis, scope == null ? null : scope.getEnclosingClass());
  }

  static Scope resolveScope(final AttributedFileAnalysis snapshot, final int cursorOffset) {
    if (snapshot.tree() == null) {
      return null;
    }

    final var path = CursorElements.pathAt(snapshot.trees(), snapshot.tree(), cursorOffset);
    if (path == null) {
      return null;
    }

    try {
      return snapshot.trees().getScope(path);
    } catch (final IllegalArgumentException e) {
      LOG.log(
          Level.FINE,
          e,
          () -> "[completion] scope resolve failed at offset %d".formatted(cursorOffset));
      return null;
    }
  }

  static TypeMirror resolvePrecedingExpressionType(
      final int tokenStart, final AttributedFileAnalysis snapshot) {
    final TreePath expressionPath = precedingExpressionPath(tokenStart, snapshot);
    return expressionPath != null ? expressionType(expressionPath, snapshot) : null;
  }

  private static TypeMirror expressionType(
      final TreePath expressionPath, final AttributedFileAnalysis snapshot) {
    final TypeMirror type = snapshot.trees().getTypeMirror(expressionPath);
    if (usableExpressionType(type)) {
      return type;
    }

    if (expressionPath.getLeaf() instanceof final ParenthesizedTree parenthesized) {
      return expressionType(new TreePath(expressionPath, parenthesized.getExpression()), snapshot);
    }

    final Element element = snapshot.trees().getElement(expressionPath);
    if (element instanceof final ExecutableElement executable) {
      return executable.getReturnType();
    }

    return element != null ? element.asType() : type;
  }

  private static TreePath precedingExpressionPath(
      final int tokenStart, final AttributedFileAnalysis snapshot) {
    if (snapshot == null || snapshot.trees() == null || snapshot.tree() == null) {
      return null;
    }

    final var positions = snapshot.trees().getSourcePositions();
    final var result = new AtomicReference<TreePath>();
    final var resultEnd = new AtomicLong(Long.MIN_VALUE);
    final var resultSpan = new AtomicLong(Long.MAX_VALUE);
    new TreePathScanner<Void, Void>() {
      @Override
      public Void scan(final Tree tree, final Void unused) {
        if (tree == null) {
          return null;
        }

        final long start = positions.getStartPosition(snapshot.tree(), tree);
        final long end = positions.getEndPosition(snapshot.tree(), tree);
        if (tree instanceof ExpressionTree
            && start >= 0
            && end >= start
            && end <= tokenStart
            && sharesCompletionContainer(new TreePath(getCurrentPath(), tree), tokenStart, snapshot)
            && isBetterExpression(end, end - start, resultEnd.get(), resultSpan.get())) {
          result.set(new TreePath(getCurrentPath(), tree));
          resultEnd.set(end);
          resultSpan.set(end - start);
        }

        return super.scan(tree, unused);
      }
    }.scan(snapshot.tree(), null);
    return result.get();
  }

  private static boolean sharesCompletionContainer(
      final TreePath expressionPath, final int tokenStart, final AttributedFileAnalysis snapshot) {
    final var positions = snapshot.trees().getSourcePositions();
    for (TreePath current = expressionPath.getParentPath();
        current != null;
        current = current.getParentPath()) {
      final Tree leaf = current.getLeaf();
      final long start = positions.getStartPosition(snapshot.tree(), leaf);
      final long end = positions.getEndPosition(snapshot.tree(), leaf);
      if (start > tokenStart || tokenStart > end) {
        continue;
      }

      return switch (leaf.getKind()) {
        case COMPILATION_UNIT, CLASS, METHOD, BLOCK -> false;
        default -> true;
      };
    }

    return false;
  }

  private static boolean isBetterExpression(
      final long end, final long span, final long bestEnd, final long bestSpan) {
    return end > bestEnd || (end == bestEnd && span < bestSpan);
  }

  private static boolean usableExpressionType(final TypeMirror type) {
    return type != null && type.getKind() != TypeKind.ERROR && type.getKind() != TypeKind.NONE;
  }

  private static ResolvedReceiver resolveByPosition(
      final int dotOffset, final AttributedFileAnalysis snapshot) {
    if (dotOffset < 0 || snapshot.tree() == null) {
      return null;
    }

    final var sourcePositions = snapshot.trees().getSourcePositions();
    final var cu = snapshot.tree();
    final var result = new AtomicReference<ResolvedReceiver>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitMethodInvocation(final MethodInvocationTree node, final Void unused) {
        check(node);
        return super.visitMethodInvocation(node, unused);
      }

      @Override
      public Void visitMemberSelect(final MemberSelectTree node, final Void unused) {
        check(node);
        return super.visitMemberSelect(node, unused);
      }

      @Override
      public Void visitIdentifier(final IdentifierTree node, final Void unused) {
        check(node);
        return super.visitIdentifier(node, unused);
      }

      @Override
      public Void visitNewClass(final NewClassTree node, final Void unused) {
        check(node);
        return super.visitNewClass(node, unused);
      }

      @Override
      public Void visitArrayAccess(final ArrayAccessTree node, final Void unused) {
        check(node);
        return super.visitArrayAccess(node, unused);
      }

      @Override
      public Void visitParenthesized(final ParenthesizedTree node, final Void unused) {
        check(node);
        return super.visitParenthesized(node, unused);
      }

      @Override
      public Void visitLiteral(final LiteralTree node, final Void unused) {
        check(node);
        return super.visitLiteral(node, unused);
      }

      @Override
      public Void visitErroneous(final ErroneousTree node, final Void unused) {
        for (final var e : node.getErrorTrees()) {
          scan(e, unused);
        }
        return null;
      }

      private void check(final Tree node) {
        if (sourcePositions.getEndPosition(cu, node) != dotOffset) {
          return;
        }

        final TypeMirror raw = snapshot.trees().getTypeMirror(getCurrentPath());
        TypeMirror type = methodInvocationReturnType(node, raw);
        if (type == null) {
          // getTypeMirror on a method invocation yields its result type directly, so an array-
          // returning chain (`"x".toCharArray().`) arrives here as an ArrayType. Keep it — dropping
          // it to null falls through to resolving the chain's base type and offers its members.
          type =
              raw != null && raw.getKind() == TypeKind.ARRAY
                  ? raw
                  : effectiveDeclaredType(raw, snapshot);
        }

        if (type == null) {
          checkErrorMethodInvocation(node, raw);
          return;
        }

        final var element = snapshot.trees().getElement(getCurrentPath());
        final boolean isStatic =
            element instanceof TypeElement
                && !(node instanceof final IdentifierTree id
                    && (id.getName().contentEquals("this") || id.getName().contentEquals("super")));
        result.compareAndSet(null, new ResolvedReceiver(type, isStatic));
      }

      private TypeMirror methodInvocationReturnType(final Tree node, final TypeMirror raw) {
        if (!(node instanceof MethodInvocationTree)) {
          return null;
        }

        if (!(raw instanceof final ExecutableType executableType)) {
          return null;
        }

        return effectiveDeclaredType(executableType.getReturnType(), snapshot);
      }

      private void checkErrorMethodInvocation(final Tree node, final TypeMirror raw) {
        if (raw == null || raw.getKind() != TypeKind.ERROR) {
          return;
        }

        if (!(node instanceof final MethodInvocationTree mi)) {
          return;
        }

        final TypeMirror returnType = methodInvocationElementReturnType(mi);
        if (returnType != null) {
          result.compareAndSet(null, new ResolvedReceiver(returnType, false));
          return;
        }

        result.compareAndSet(null, new ResolvedReceiver(raw, false));
      }

      private TypeMirror methodInvocationElementReturnType(final MethodInvocationTree mi) {
        final var selectorPath = new TreePath(getCurrentPath(), mi.getMethodSelect());
        final var el = snapshot.trees().getElement(selectorPath);
        if (el instanceof final ExecutableElement ee) {
          return effectiveDeclaredType(ee.getReturnType(), snapshot);
        }

        return methodInvocationReceiverReturnType(mi);
      }

      private TypeMirror methodInvocationReceiverReturnType(final MethodInvocationTree mi) {
        if (!(mi.getMethodSelect() instanceof final MemberSelectTree ms)) {
          return null;
        }

        final var receiverPath = new TreePath(getCurrentPath(), ms.getExpression());
        final TypeMirror receiverType =
            effectiveDeclaredType(snapshot.trees().getTypeMirror(receiverPath), snapshot);
        if (receiverType == null) {
          return null;
        }

        if (!(snapshot.types().asElement(receiverType) instanceof final TypeElement receiverEl)) {
          return null;
        }

        TypeMirror matchedReturnType = null;
        for (final var member : snapshot.elements().getAllMembers(receiverEl)) {
          if (member.getKind() != ElementKind.METHOD
              || !member.getSimpleName().contentEquals(ms.getIdentifier())) {
            continue;
          }

          final var method = (ExecutableElement) member;
          if (!arityMatches(method, mi.getArguments().size())) {
            continue;
          }

          final TypeMirror returnType = effectiveDeclaredType(method.getReturnType(), snapshot);
          if (returnType != null) {
            if (matchedReturnType != null) {
              return null;
            }

            matchedReturnType = returnType;
          }
        }

        return matchedReturnType;
      }

      private boolean arityMatches(final ExecutableElement method, final int argCount) {
        final int paramCount = method.getParameters().size();
        if (method.isVarArgs()) {
          return argCount >= paramCount - 1;
        }

        return argCount == paramCount;
      }
    }.scan(cu, null);
    return result.get();
  }

  private static TypeMirror effectiveDeclaredType(
      final TypeMirror type, final AttributedFileAnalysis snapshot) {
    TypeMirror current = type;
    while (current != null) {
      if (current.getKind() == TypeKind.DECLARED) {
        return current;
      }

      if (current instanceof final TypeVariable tv) {
        current = tv.getUpperBound();
      } else if (current instanceof final WildcardType wt) {
        final TypeMirror ext = wt.getExtendsBound();
        if (ext != null) {
          current = ext;
        } else {
          final var obj = snapshot.elements().getTypeElement("java.lang.Object");
          return obj != null ? obj.asType() : null;
        }
      } else {
        return null;
      }
    }

    return null;
  }
}
