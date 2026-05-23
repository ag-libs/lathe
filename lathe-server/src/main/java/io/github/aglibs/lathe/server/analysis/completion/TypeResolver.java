package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import java.util.concurrent.atomic.AtomicReference;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

final class TypeResolver {

  private TypeResolver() {}

  static TypeMirror resolveReceiverType(
      final ParsedSentinel sentinel,
      final int cursorLine,
      final int dotOffset,
      final FileAnalysis snapshot) {
    final var text = sentinel.receiverText();
    if (text == null) {
      return null;
    }

    if (text.startsWith("\"")) {
      return snapshot.elements().getTypeElement("java.lang.String").asType();
    }

    if ("this".equals(text)) {
      final var classEl = findClassElement(sentinel.enclosingClass(), snapshot);
      return classEl != null ? classEl.asType() : null;
    }

    if ("super".equals(text)) {
      final var classEl = findClassElement(sentinel.enclosingClass(), snapshot);
      return classEl != null ? classEl.getSuperclass() : null;
    }

    // Complex expressions (method calls, casts, array access) — resolve by source position
    if (text.indexOf('(') >= 0 || text.indexOf('[') >= 0 || text.indexOf(' ') >= 0) {
      return resolveByPosition(dotOffset, snapshot);
    }

    // Dotted name — treat as FQN only (e.g. java.util.Collections)
    if (text.indexOf('.') >= 0) {
      final var el = snapshot.elements().getTypeElement(text);
      return el != null ? el.asType() : null;
    }

    // Capitalized simple name — type reference (e.g. System, ArrayList)
    if (Character.isUpperCase(text.charAt(0))) {
      final var el = snapshot.elements().getTypeElement(text);
      if (el != null) {
        return el.asType();
      }

      final var fqn = resolveViaImports(text, snapshot);
      if (fqn != null) {
        final var importedEl = snapshot.elements().getTypeElement(fqn);
        if (importedEl != null) {
          return importedEl.asType();
        }
      }

      final var langEl = snapshot.elements().getTypeElement("java.lang." + text);
      if (langEl != null) {
        return langEl.asType();
      }

      return resolveByPosition(dotOffset, snapshot);
    }

    // Simple name — local variable, parameter, or field
    final var localType =
        scanForLocalDeclaration(
            text, sentinel.enclosingClass(), sentinel.enclosingMethod(), cursorLine, snapshot);
    if (localType != null) {
      return localType;
    }

    final var fieldType = findFieldType(text, sentinel.enclosingClass(), snapshot);
    if (fieldType != null) {
      return fieldType;
    }

    return resolveByPosition(dotOffset, snapshot);
  }

  private static TypeMirror resolveByPosition(final int dotOffset, final FileAnalysis snapshot) {
    if (dotOffset < 0 || snapshot.tree() == null) {
      return null;
    }

    final var sourcePositions = snapshot.trees().getSourcePositions();
    final var cu = snapshot.tree();
    final var result = new AtomicReference<TypeMirror>();
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
            result.compareAndSet(null, type);
          }
        }
      }
    }.scan(cu, null);
    return result.get();
  }

  private static TypeElement findClassElement(
      final String simpleName, final FileAnalysis snapshot) {
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

  private static String resolveViaImports(final String simpleName, final FileAnalysis snapshot) {
    for (final var imp : snapshot.tree().getImports()) {
      if (imp.isStatic()) {
        continue;
      }

      final var fqn = imp.getQualifiedIdentifier().toString();
      if (fqn.endsWith("." + simpleName)) {
        return fqn;
      }
    }
    return null;
  }
}
