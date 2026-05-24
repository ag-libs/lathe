package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

final class ProposalGenerator {

  private static final Logger LOG = Logger.getLogger(ProposalGenerator.class.getName());

  private final FileAnalysis snapshot;
  private final Types types;

  ProposalGenerator(final FileAnalysis snapshot) {
    this.snapshot = snapshot;
    this.types = snapshot.types();
  }

  List<CompletionItem> proposeMemberAccess(
      final TypeMirror receiverType,
      final String prefix,
      final boolean isStaticAccess,
      final Scope scope) {
    if (!(receiverType instanceof final DeclaredType declaredType)) {
      return List.of();
    }

    final var element = types.asElement(declaredType);
    if (!(element instanceof final TypeElement typeEl)) {
      return List.of();
    }

    return snapshot.elements().getAllMembers(typeEl).stream()
        .filter(
            el ->
                el.getKind() == ElementKind.METHOD
                    || el.getKind() == ElementKind.FIELD
                    || el.getKind() == ElementKind.ENUM_CONSTANT)
        .filter(el -> !isStaticAccess || el.getModifiers().contains(Modifier.STATIC))
        .filter(el -> isAccessible(el, declaredType, scope))
        .filter(el -> el.getSimpleName().toString().startsWith(prefix))
        .map(el -> toCompletionItem(el, declaredType))
        .toList();
  }

  List<CompletionItem> proposeNestedTypes(final TypeElement outer, final String prefix) {
    return outer.getEnclosedElements().stream()
        .filter(
            el ->
                el.getKind() == ElementKind.CLASS
                    || el.getKind() == ElementKind.INTERFACE
                    || el.getKind() == ElementKind.ENUM
                    || el.getKind() == ElementKind.RECORD)
        .filter(el -> el.getSimpleName().toString().startsWith(prefix))
        .map(
            el -> {
              final var item = new CompletionItem();
              item.setLabel(el.getSimpleName().toString());
              item.setKind(
                  el.getKind() == ElementKind.INTERFACE
                      ? CompletionItemKind.Interface
                      : CompletionItemKind.Class);
              return item;
            })
        .toList();
  }

  List<CompletionItem> proposeSimpleName(
      final String enclosingClass,
      final String enclosingMethod,
      final String prefix,
      final int cursorOffset) {
    final var seen = new LinkedHashSet<String>();
    final var items = new ArrayList<CompletionItem>();

    // 1. Parameters and local variables from the enclosing method
    if (enclosingMethod != null && enclosingClass != null) {
      final var methodPath = findScopeMethodPath(enclosingClass, enclosingMethod);
      if (methodPath != null) {
        final var methodTree = (MethodTree) methodPath.getLeaf();
        for (final var param : methodTree.getParameters()) {
          final var name = param.getName().toString();
          if (name.startsWith(prefix) && seen.add(name)) {
            items.add(varItem(name));
          }
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
              if (pos >= 0 && pos < cursorOffset) {
                final var name = node.getName().toString();
                if (name.startsWith(prefix) && seen.add(name)) {
                  items.add(varItem(name));
                }
              }
            }
          }.scan(methodPath, null);
        }
      }
    }

    // 2. Fields and methods of the enclosing class
    if (enclosingClass != null) {
      final var classEl = findScopeClassElement(enclosingClass);
      if (classEl != null) {
        final var declaredType = (DeclaredType) classEl.asType();
        snapshot.elements().getAllMembers(classEl).stream()
            .filter(el -> el.getKind() == ElementKind.METHOD || el.getKind() == ElementKind.FIELD)
            .filter(el -> el.getSimpleName().toString().startsWith(prefix))
            .filter(el -> seen.add(el.getSimpleName().toString()))
            .map(el -> toCompletionItem(el, declaredType))
            .forEach(items::add);
      }
    }

    return items;
  }

  private static CompletionItem varItem(final String name) {
    final var item = new CompletionItem();
    item.setLabel(name);
    item.setKind(CompletionItemKind.Variable);
    return item;
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

  private CompletionItem toCompletionItem(final Element el, final DeclaredType receiverType) {
    final var item = new CompletionItem();
    if (el.getKind() == ElementKind.METHOD) {
      final var method = (ExecutableElement) el;
      final List<? extends TypeMirror> paramTypes = resolveParamTypes(method, receiverType);
      final var params =
          paramTypes.stream().map(this::simpleTypeName).collect(Collectors.joining(", "));
      item.setLabel(el.getSimpleName() + "(" + params + ")");
      item.setKind(CompletionItemKind.Method);
    } else {
      item.setLabel(el.getSimpleName().toString());
      item.setKind(CompletionItemKind.Field);
    }

    return item;
  }

  private boolean isAccessible(
      final Element el, final DeclaredType receiverType, final Scope scope) {
    if (scope == null) {
      return true;
    }

    try {
      return snapshot.trees().isAccessible(scope, el, receiverType);
    } catch (final IllegalArgumentException e) {
      LOG.fine(
          () ->
              "[proposal] isAccessible failed for %s on %s: %s"
                  .formatted(el.getSimpleName(), receiverType, e.getMessage()));
      return true;
    }
  }

  private List<? extends TypeMirror> resolveParamTypes(
      final ExecutableElement method, final DeclaredType receiverType) {
    try {
      return ((ExecutableType) types.asMemberOf(receiverType, method)).getParameterTypes();
    } catch (final IllegalArgumentException e) {
      LOG.fine(
          () ->
              "[proposal] asMemberOf failed for %s on %s: %s"
                  .formatted(method.getSimpleName(), receiverType, e.getMessage()));
      return method.getParameters().stream().map(VariableElement::asType).toList();
    }
  }

  private String simpleTypeName(final TypeMirror type) {
    final var el = types.asElement(type);
    return el != null ? el.getSimpleName().toString() : type.toString();
  }
}
