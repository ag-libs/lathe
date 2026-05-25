package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

final class TypeResolver {

  private static final Logger LOG = Logger.getLogger(TypeResolver.class.getName());

  private TypeResolver() {}

  static ResolvedReceiver resolveReceiver(
      final ParsedSentinel sentinel, final int cursorLine, final FileAnalysis snapshot) {

    // Primary: AST-based resolution using the position from the injected parse
    if (sentinel.receiverEndOffset() >= 0) {
      final var resolved = resolveByPosition(sentinel.receiverEndOffset(), snapshot);
      if (resolved != null) {
        return resolved;
      }
    }

    // Fallback: text-based resolution for stale caches and trivial cases
    final var text = sentinel.receiverText();
    if (text == null) {
      return null;
    }

    if (text.startsWith("\"")) {
      final var el = snapshot.elements().getTypeElement("java.lang.String");
      return el != null ? new ResolvedReceiver(el.asType(), false) : null;
    }

    if ("this".equals(text)) {
      final var classEl = findClassElement(sentinel.enclosingClass(), snapshot);
      return classEl != null ? new ResolvedReceiver(classEl.asType(), false) : null;
    }

    if ("super".equals(text)) {
      final var classEl = findClassElement(sentinel.enclosingClass(), snapshot);
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

    // Capitalized simple name — type reference (static access)
    if (Character.isUpperCase(text.charAt(0))) {
      final var el = snapshot.elements().getTypeElement(text);
      if (el != null) {
        return new ResolvedReceiver(el.asType(), true);
      }

      final var langEl = snapshot.elements().getTypeElement("java.lang." + text);
      if (langEl != null) {
        return new ResolvedReceiver(langEl.asType(), true);
      }

      return null;
    }

    // Simple name — local variable, parameter, or field (instance access)
    final var localType =
        scanForLocalDeclaration(
            text, sentinel.enclosingClass(), sentinel.enclosingMethod(), cursorLine, snapshot);
    if (localType != null) {
      return new ResolvedReceiver(localType, false);
    }

    final var fieldType = findFieldType(text, sentinel.enclosingClass(), snapshot);
    if (fieldType != null) {
      return new ResolvedReceiver(fieldType, false);
    }

    return null;
  }

  static Scope resolveScope(final FileAnalysis snapshot, final int cursorOffset) {
    if (snapshot.tree() == null) {
      return null;
    }

    final var path = SourceLocator.pathAt(snapshot.trees(), snapshot.tree(), cursorOffset);
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

  private static ResolvedReceiver resolveByPosition(
      final int dotOffset, final FileAnalysis snapshot) {
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
      public Void visitErroneous(final ErroneousTree node, final Void unused) {
        for (final var e : node.getErrorTrees()) {
          scan(e, unused);
        }
        return null;
      }

      private void check(final Tree node) {
        if (sourcePositions.getEndPosition(cu, node) == dotOffset) {
          final var type = snapshot.trees().getTypeMirror(getCurrentPath());
          if (type != null && type.getKind() == TypeKind.DECLARED) {
            final var element = snapshot.trees().getElement(getCurrentPath());
            final boolean isStatic =
                element instanceof TypeElement
                    && !(node instanceof final IdentifierTree id
                        && (id.getName().contentEquals("this")
                            || id.getName().contentEquals("super")));
            result.compareAndSet(null, new ResolvedReceiver(type, isStatic));
          }
        }
      }
    }.scan(cu, null);
    return result.get();
  }

  private static TypeElement findClassElement(
      final String simpleName, final FileAnalysis snapshot) {
    if (simpleName == null || snapshot.tree() == null) {
      return null;
    }

    final var result = new AtomicReference<TypeElement>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitClass(final ClassTree node, final Void unused) {
        if (simpleName.equals(node.getSimpleName().toString())) {
          final var el = snapshot.trees().getElement(getCurrentPath());
          if (el instanceof final TypeElement te) {
            result.set(te);
          }
        }

        return super.visitClass(node, unused);
      }
    }.scan(snapshot.tree(), null);
    return result.get();
  }

  private static TreePath findMethodPath(
      final String className, final String methodName, final FileAnalysis snapshot) {
    final var result = new AtomicReference<TreePath>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitClass(final ClassTree node, final Void unused) {
        if (className.equals(node.getSimpleName().toString())) {
          return super.visitClass(node, unused);
        }

        return null;
      }

      @Override
      public Void visitMethod(final MethodTree node, final Void unused) {
        if (result.get() == null && methodName.equals(node.getName().toString())) {
          result.set(getCurrentPath());
        }

        return null;
      }
    }.scan(snapshot.tree(), null);
    return result.get();
  }

  private static TypeMirror scanForLocalDeclaration(
      final String name,
      final String enclosingClass,
      final String enclosingMethod,
      final int cursorLine,
      final FileAnalysis snapshot) {
    if (enclosingMethod == null) {
      return null;
    }

    final var methodPath = findMethodPath(enclosingClass, enclosingMethod, snapshot);
    if (methodPath == null) {
      return null;
    }

    final var methodTree = (MethodTree) methodPath.getLeaf();
    if (methodTree.getBody() == null) {
      return null;
    }

    final var result = new AtomicReference<TypeMirror>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitVariable(final VariableTree node, final Void unused) {
        if (name.equals(node.getName().toString())) {
          final long startPos =
              snapshot.trees().getSourcePositions().getStartPosition(snapshot.tree(), node);
          final long nodeLine = snapshot.tree().getLineMap().getLineNumber(startPos) - 1;
          if (nodeLine <= cursorLine) {
            final var el = snapshot.trees().getElement(getCurrentPath());
            if (el != null) {
              result.set(el.asType());
            }
          }
        }

        return super.visitVariable(node, unused);
      }
    }.scan(methodPath, null);
    return result.get();
  }

  private static TypeMirror findFieldType(
      final String name, final String className, final FileAnalysis snapshot) {
    final var classEl = findClassElement(className, snapshot);
    if (classEl == null) {
      return null;
    }

    return classEl.getEnclosedElements().stream()
        .filter(el -> el.getKind() == ElementKind.FIELD)
        .filter(el -> name.equals(el.getSimpleName().toString()))
        .findFirst()
        .map(Element::asType)
        .orElse(null);
  }
}
