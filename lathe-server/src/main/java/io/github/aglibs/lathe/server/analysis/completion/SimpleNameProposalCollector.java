package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.CompletionItem;

final class SimpleNameProposalCollector {

  private final FileAnalysis snapshot;
  private final CompletionItemFactory itemFactory;
  private final SimpleNameProposalContext context;
  private final Set<String> seen = new LinkedHashSet<>();
  private final List<CompletionItem> items = new ArrayList<>();

  SimpleNameProposalCollector(
      final FileAnalysis snapshot,
      final CompletionItemFactory itemFactory,
      final SimpleNameProposalContext context) {
    this.snapshot = snapshot;
    this.itemFactory = itemFactory;
    this.context = context;
  }

  List<CompletionItem> collect() {
    final var methodPath =
        context.enclosingMethod() != null
            ? findScopeMethodPath(context.enclosingClass(), context.enclosingMethod())
            : null;
    final boolean staticMethod = isStaticMethod(methodPath);

    addMethodLocals(methodPath);
    addClassMembers(staticMethod);

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
          final long pos =
              snapshot.trees().getSourcePositions().getStartPosition(snapshot.tree(), node);
          if (pos >= 0 && pos < context.cursorOffset()) {
            final var el = snapshot.trees().getElement(getCurrentPath());
            addVariable(node.getName().toString(), el != null ? el.asType() : null);
          }
        }
      }.scan(methodPath, null);
    }
  }

  private void addVariable(final String name, final TypeMirror type) {
    if (name.startsWith(context.prefix()) && seen.add(name)) {
      final var item = itemFactory.variable(name);
      final var expected = context.expectedParamType();
      if (expected != null) {
        final boolean matches = type != null && snapshot.types().isAssignable(type, expected);
        item.setSortText(matches ? "0_" + name : "1_" + name);
      }
      items.add(item);
    }
  }

  private void addClassMembers(final boolean staticMethod) {
    final var classEl = findScopeClassElement(context.enclosingClass());
    if (classEl == null) {
      return;
    }

    final var declaredType = (DeclaredType) classEl.asType();
    snapshot.elements().getAllMembers(classEl).stream()
        .filter(el -> el.getKind() == ElementKind.METHOD || el.getKind() == ElementKind.FIELD)
        .filter(el -> !staticMethod || el.getModifiers().contains(Modifier.STATIC))
        .filter(el -> el.getSimpleName().toString().startsWith(context.prefix()))
        .filter(el -> seen.add(el.getSimpleName().toString()))
        .map(el -> itemFactory.member(el, declaredType))
        .forEach(items::add);
  }

  private TreePath findScopeMethodPath(final String className, final String methodName) {
    final var result = new AtomicReference<TreePath>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitClass(final ClassTree node, final Void unused) {
        return className.equals(node.getSimpleName().toString())
            ? super.visitClass(node, unused)
            : null;
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
