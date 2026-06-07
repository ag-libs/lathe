package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

final class SimpleNameProvider {

  private final AttributedFileAnalysis snapshot;
  private final CandidateFactory itemFactory;
  private final SimpleNameContext context;
  private final Set<String> seen = new LinkedHashSet<>();
  private final List<CompletionCandidate> items = new ArrayList<>();

  SimpleNameProvider(
      final AttributedFileAnalysis snapshot,
      final CandidateFactory itemFactory,
      final SimpleNameContext context) {
    this.snapshot = snapshot;
    this.itemFactory = itemFactory;
    this.context = context;
  }

  List<CompletionCandidate> collect() {
    final var methodPath =
        context.enclosingMethod() != null
            ? findScopeMethodPath(
                context.enclosingClass(), context.enclosingMethod(), context.cursorOffset())
            : null;
    final boolean staticMethod = isStaticMethod(methodPath);

    addMethodLocals(methodPath);
    addClassMembers(staticMethod);
    addStaticImportMembers();

    return items;
  }

  private boolean isStaticMethod(final TreePath methodPath) {
    return methodPath != null
        && ((MethodTree) methodPath.getLeaf()).getModifiers().getFlags().contains(Modifier.STATIC);
  }

  private void addMethodLocals(final TreePath methodPath) {
    if (methodPath == null) {
      return;
    }

    final var methodTree = (MethodTree) methodPath.getLeaf();
    final var methodEl = snapshot.trees().getElement(methodPath);
    if (methodEl instanceof final ExecutableElement execEl) {
      execEl.getParameters().forEach(p -> addVariable(p.getSimpleName().toString(), p.asType()));
    } else {
      methodTree.getParameters().forEach(p -> addVariable(p.getName().toString(), null));
    }

    if (methodTree.getBody() != null) {
      new TreePathScanner<Void, Void>() {
        @Override
        public Void visitVariable(final VariableTree node, final Void unused) {
          addVariableIfVisible(node);
          return super.visitVariable(node, unused);
        }

        @Override
        public Void visitBindingPattern(final BindingPatternTree node, final Void unused) {
          addVariableIfVisible(node.getVariable());
          return super.visitBindingPattern(node, unused);
        }

        private void addVariableIfVisible(final VariableTree node) {
          final var positions = snapshot.trees().getSourcePositions();
          final long start = positions.getStartPosition(snapshot.tree(), node);
          final long end = positions.getEndPosition(snapshot.tree(), node);
          if (start < 0 || start >= context.cursorOffset() || end < 0) {
            return;
          }

          if (end < context.cursorOffset()) {
            final var el = snapshot.trees().getElement(getCurrentPath());
            addVariable(node.getName().toString(), el != null ? el.asType() : null);
          }
        }
      }.scan(methodPath, null);
    }
  }

  private void addVariable(final String name, final TypeMirror type) {
    if (name.startsWith(context.prefix()) && seen.add(name)) {
      items.add(itemFactory.variableCandidate(name, type));
    }
  }

  private void addMember(final Element el, final DeclaredType declaredType) {
    if (seen.add(memberKey(el))) {
      items.add(itemFactory.memberCandidate(el, declaredType));
    }
  }

  private static String memberKey(final Element el) {
    if (el instanceof final ExecutableElement executable) {
      final var params =
          executable.getParameters().stream().map(param -> param.asType().toString()).toList();
      return "%s(%s)".formatted(executable.getSimpleName(), String.join(",", params));
    }

    return el.getSimpleName().toString();
  }

  private void addClassMembers(final boolean staticMethod) {
    final var classEl = findScopeClassElement(context.enclosingClass());
    if (classEl == null) {
      return;
    }

    addClassMembers(classEl, staticMethod);
    addEnclosingStaticMembers(classEl);
  }

  private void addEnclosingStaticMembers(final TypeElement classEl) {
    for (Element owner = classEl.getEnclosingElement();
        owner instanceof final TypeElement typeElement;
        owner = owner.getEnclosingElement()) {
      addClassMembers(typeElement, true);
    }
  }

  private void addClassMembers(final TypeElement classEl, final boolean staticOnly) {
    final var declaredType = (DeclaredType) classEl.asType();
    snapshot.elements().getAllMembers(classEl).stream()
        .filter(
            el ->
                el.getKind() == ElementKind.METHOD
                    || el.getKind() == ElementKind.FIELD
                    || el.getKind() == ElementKind.CLASS
                    || el.getKind() == ElementKind.INTERFACE
                    || el.getKind() == ElementKind.ENUM
                    || el.getKind() == ElementKind.RECORD)
        .filter(el -> !staticOnly || el.getModifiers().contains(Modifier.STATIC))
        .filter(el -> el.getSimpleName().toString().startsWith(context.prefix()))
        .forEach(el -> addMember(el, declaredType));
  }

  private TreePath findScopeMethodPath(
      final String className, final String methodName, final long cursorOffset) {
    final var result = new AtomicReference<TreePath>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitClass(final ClassTree node, final Void unused) {
        return super.visitClass(node, unused);
      }

      @Override
      public Void visitMethod(final MethodTree node, final Void unused) {
        if (!methodName.equals(node.getName().toString())) {
          return null;
        }

        final var parentLeaf = getCurrentPath().getParentPath().getLeaf();
        if (!(parentLeaf instanceof final ClassTree cls)
            || !className.equals(cls.getSimpleName().toString())) {
          return null;
        }

        final var current = getCurrentPath();
        final var pos = snapshot.trees().getSourcePositions();
        final long start = pos.getStartPosition(snapshot.tree(), node);
        final long end = pos.getEndPosition(snapshot.tree(), node);
        if (cursorOffset >= start && cursorOffset <= end) {
          result.set(current);
        } else if (result.get() == null) {
          result.set(current);
        }

        return null;
      }
    }.scan(snapshot.tree(), null);
    return result.get();
  }

  private void addStaticImportMembers() {
    if (snapshot.tree() == null) {
      return;
    }

    for (final var imp : snapshot.tree().getImports()) {
      if (!imp.isStatic()) {
        continue;
      }

      if (!(imp.getQualifiedIdentifier() instanceof final MemberSelectTree memberSelect)) {
        continue;
      }

      final var memberName = memberSelect.getIdentifier().toString();
      final var typeName = memberSelect.getExpression().toString();
      final var typeEl = snapshot.elements().getTypeElement(typeName);
      if (typeEl == null) {
        continue;
      }

      final boolean wildcard = "*".equals(memberName);
      final var declaredType = (DeclaredType) typeEl.asType();
      snapshot.elements().getAllMembers(typeEl).stream()
          .filter(el -> staticImportAllows(el, wildcard, memberName))
          .filter(el -> el.getSimpleName().toString().startsWith(context.prefix()))
          .forEach(el -> addMember(el, declaredType));
    }
  }

  private static boolean staticImportAllows(
      final Element el, final boolean wildcard, final String memberName) {
    return staticImportableMember(el)
        && (wildcard || memberName.equals(el.getSimpleName().toString()));
  }

  private static boolean staticImportableMember(final Element el) {
    return switch (el.getKind()) {
      case ENUM_CONSTANT -> true;
      case METHOD, FIELD -> el.getModifiers().contains(Modifier.STATIC);
      default -> false;
    };
  }

  private TypeElement findScopeClassElement(final String simpleName) {
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
}
