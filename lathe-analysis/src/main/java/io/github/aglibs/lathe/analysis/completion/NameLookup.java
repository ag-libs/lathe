package io.github.aglibs.lathe.analysis.completion;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

// Declarations found by name in an attributed snapshot: a class, a method around the cursor, a
// local variable declared before the cursor line, a field. For a snapshot whose positions no
// longer match the text being completed (attributed before the last keystrokes), where the names
// the sentinel parse reports are what still identifies them.
final class NameLookup {

  static final Logger LOG = Logger.getLogger(NameLookup.class.getName());

  private NameLookup() {}

  static TypeElement findClassElement(
      final String simpleName, final AttributedFileAnalysis snapshot) {
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

  static TreePath findMethodPath(
      final String className,
      final String methodName,
      final int cursorOffset,
      final AttributedFileAnalysis snapshot) {
    if (methodName == null) {
      return null;
    }

    final var result = new AtomicReference<TreePath>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitMethod(final MethodTree node, final Void unused) {
        if (!methodName.equals(node.getName().toString())) {
          return null;
        }

        final var parentLeaf = getCurrentPath().getParentPath().getLeaf();
        if (className != null
            && !(parentLeaf instanceof final ClassTree cls
                && className.equals(cls.getSimpleName().toString()))) {
          return null;
        }

        final var current = getCurrentPath();
        if (cursorOffset >= 0) {
          final var pos = snapshot.trees().getSourcePositions();
          final long start = pos.getStartPosition(snapshot.tree(), node);
          final long end = pos.getEndPosition(snapshot.tree(), node);
          if (cursorOffset >= start && cursorOffset <= end) {
            result.set(current);
            return null;
          }
        }

        if (result.get() == null) {
          result.set(current);
        }

        return null;
      }
    }.scan(snapshot.tree(), null);
    return result.get();
  }

  static TypeMirror scanForLocalDeclaration(
      final String name,
      final String enclosingClass,
      final String enclosingMethod,
      final int cursorLine,
      final AttributedFileAnalysis snapshot) {
    if (enclosingMethod == null) {
      return null;
    }

    final var methodPath = findMethodPath(enclosingClass, enclosingMethod, -1, snapshot);
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

  static TypeMirror findFieldType(
      final String name, final String className, final AttributedFileAnalysis snapshot) {
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
