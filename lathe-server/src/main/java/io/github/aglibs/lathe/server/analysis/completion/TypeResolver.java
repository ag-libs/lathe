package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

final class TypeResolver {

  private static final Logger LOG = Logger.getLogger(TypeResolver.class.getName());

  private TypeResolver() {}

  // ── public API ────────────────────────────────────────────────────────────────

  static boolean isNonVoidMethod(
      final String className, final String methodName, final AttributedFileAnalysis snapshot) {
    if (className == null || methodName == null || snapshot.tree() == null) {
      return false;
    }

    return resolveMethodReturnType(className, methodName, snapshot) != null;
  }

  static ExpectedValue resolveExpectedValue(
      final CompletionSite site, final int cursorLine, final AttributedFileAnalysis snapshot) {
    if (snapshot.tree() == null) {
      return new ExpectedValue.Unknown();
    }

    if (site.sentinelContext() == SentinelContext.CASE_LABEL) {
      final ExpectedValue expected = resolveSwitchSelectorValue(site, snapshot);
      if (!(expected instanceof ExpectedValue.Unknown)) {
        return expected;
      }
    }

    if (site.argIndex() >= 0 && site.enclosingMethodName() != null) {
      final var argValue = resolveArgumentValue(site, cursorLine, snapshot);
      if (!(argValue instanceof ExpectedValue.Unknown)) {
        return argValue;
      }

      final var posValue = resolveArgumentValueByPosition(site, snapshot);
      if (!(posValue instanceof ExpectedValue.Unknown)) {
        return posValue;
      }
    }

    return resolveInitializerValue(site, snapshot);
  }

  static ExpectedValue resolveExpectedArgumentValue(
      final int cursorOffset, final AttributedFileAnalysis snapshot) {
    if (snapshot.tree() == null) {
      return new ExpectedValue.Unknown();
    }

    final var result = new AtomicReference<ExpectedValue>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitMethodInvocation(final MethodInvocationTree node, final Void unused) {
        super.visitMethodInvocation(node, unused);
        if (result.get() != null || cursorOutside(snapshot, node, cursorOffset)) {
          return null;
        }

        final int argIndex = argumentIndex(node, cursorOffset);
        if (argIndex < 0) {
          return null;
        }

        final String methodName = methodSelectName(node.getMethodSelect());
        final var receiverTypeEl =
            node.getMethodSelect() instanceof final MemberSelectTree ms
                ? receiverType(getCurrentPath(), ms, snapshot)
                : enclosingClass(getCurrentPath(), snapshot);
        if (receiverTypeEl == null) {
          return null;
        }

        result.set(expectedMethodArgumentValue(receiverTypeEl, methodName, argIndex, snapshot));

        return null;
      }

      private int argumentIndex(final MethodInvocationTree node, final int cursorOffset) {
        final var args = node.getArguments();
        for (int i = 0; i < args.size(); i++) {
          if (!cursorOutside(snapshot, args.get(i), cursorOffset)) {
            return i;
          }
        }

        return -1;
      }
    }.scan(snapshot.tree(), null);
    return result.get() != null ? result.get() : new ExpectedValue.Unknown();
  }

  private static TypeElement receiverType(
      final TreePath invocationPath,
      final MemberSelectTree methodSelect,
      final AttributedFileAnalysis snapshot) {
    final var receiverPath = new TreePath(invocationPath, methodSelect.getExpression());
    final TypeMirror receiverType = snapshot.trees().getTypeMirror(receiverPath);
    if (receiverType == null || receiverType.getKind() != TypeKind.DECLARED) {
      return null;
    }

    final var receiverEl = snapshot.types().asElement(receiverType);
    return receiverEl instanceof final TypeElement receiverTypeEl ? receiverTypeEl : null;
  }

  private static TypeElement enclosingClass(
      final TreePath path, final AttributedFileAnalysis snapshot) {
    for (TreePath current = path; current != null; current = current.getParentPath()) {
      if (current.getLeaf() instanceof ClassTree) {
        final var el = snapshot.trees().getElement(current);
        return el instanceof final TypeElement typeElement ? typeElement : null;
      }
    }

    return null;
  }

  static ResolvedReceiver resolveReceiver(
      final ParsedSentinel sentinel, final int cursorLine, final AttributedFileAnalysis snapshot) {

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

  static Scope resolveScope(final AttributedFileAnalysis snapshot, final int cursorOffset) {
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

  // ── expected value resolution ─────────────────────────────────────────────────

  private static ExpectedValue resolveArgumentValue(
      final CompletionSite site, final int cursorLine, final AttributedFileAnalysis snapshot) {
    final String receiver = site.enclosingReceiver();
    final TypeElement ownerType;
    if (receiver == null || "this".equals(receiver)) {
      ownerType = findClassElement(site.enclosingClass(), snapshot);

    } else if (receiver.indexOf('.') < 0
        && receiver.indexOf('(') < 0
        && receiver.indexOf(' ') < 0) {
      final var localType =
          scanForLocalDeclaration(
              receiver, site.enclosingClass(), site.enclosingMethod(), cursorLine, snapshot);
      final var receiverType =
          localType != null ? localType : findFieldType(receiver, site.enclosingClass(), snapshot);
      final var el = receiverType != null ? snapshot.types().asElement(receiverType) : null;
      ownerType = el instanceof final TypeElement te ? te : null;
    } else {
      return new ExpectedValue.Unknown();
    }

    if (ownerType == null) {
      return new ExpectedValue.Unknown();
    }

    final var expected =
        expectedMethodArgumentValue(
            ownerType, site.enclosingMethodName(), site.argIndex(), snapshot);
    return expected instanceof ExpectedValue.Unknown
        ? resolveConstructorArgumentValue(site, snapshot)
        : expected;
  }

  private static ExpectedValue resolveConstructorArgumentValue(
      final CompletionSite site, final AttributedFileAnalysis snapshot) {
    final var classEl = findClassElement(site.enclosingMethodName(), snapshot);
    if (classEl == null) {
      return new ExpectedValue.Unknown();
    }

    for (final var el : classEl.getEnclosedElements()) {
      if (el.getKind() != ElementKind.CONSTRUCTOR) {
        continue;
      }

      final var ctor = (ExecutableElement) el;
      final var params = ctor.getParameters();
      final int idx =
          ctor.isVarArgs() ? Math.min(site.argIndex(), params.size() - 1) : site.argIndex();
      if (idx >= 0 && idx < params.size()) {
        return new ExpectedValue.Type(params.get(idx).asType());
      }
    }

    return new ExpectedValue.Unknown();
  }

  private static ExpectedValue resolveArgumentValueByPosition(
      final CompletionSite site, final AttributedFileAnalysis snapshot) {
    final var methodPath =
        findScopeMethodPath(
            site.enclosingClass(), site.enclosingMethod(), site.cursorOffset(), snapshot);
    if (methodPath == null) {
      return new ExpectedValue.Unknown();
    }

    final var result = new AtomicReference<ExpectedValue>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitMethodInvocation(final MethodInvocationTree node, final Void unused) {
        if (result.get() != null) {
          return super.visitMethodInvocation(node, unused);
        }

        if (!site.enclosingMethodName().equals(methodSelectName(node.getMethodSelect()))) {
          return super.visitMethodInvocation(node, unused);
        }

        if (cursorOutside(snapshot, node, site.cursorOffset())) {
          return super.visitMethodInvocation(node, unused);
        }

        if (!(node.getMethodSelect() instanceof final MemberSelectTree ms)) {
          return super.visitMethodInvocation(node, unused);
        }

        final var receiverTypeEl = receiverType(getCurrentPath(), ms, snapshot);
        if (receiverTypeEl == null) {
          return super.visitMethodInvocation(node, unused);
        }

        final var expected =
            expectedMethodArgumentValue(
                receiverTypeEl, site.enclosingMethodName(), site.argIndex(), snapshot);
        if (!(expected instanceof ExpectedValue.Unknown)) {
          result.set(expected);
          return null;
        }

        return super.visitMethodInvocation(node, unused);
      }
    }.scan(methodPath, null);

    return result.get() != null ? result.get() : new ExpectedValue.Unknown();
  }

  private static ExpectedValue resolveInitializerValue(
      final CompletionSite site, final AttributedFileAnalysis snapshot) {
    if (site.enclosingMethod() == null) {
      return new ExpectedValue.Unknown();
    }

    final var methodPath =
        findScopeMethodPath(
            site.enclosingClass(), site.enclosingMethod(), site.cursorOffset(), snapshot);
    if (methodPath == null || ((MethodTree) methodPath.getLeaf()).getBody() == null) {
      return new ExpectedValue.Unknown();
    }

    final var result = new AtomicReference<TypeMirror>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitVariable(final VariableTree node, final Void unused) {
        if (result.get() != null) {
          return super.visitVariable(node, unused);
        }

        if (cursorOutside(snapshot, node, site.cursorOffset())) {
          return super.visitVariable(node, unused);
        }

        if (node.getType() != null
            && !cursorOutside(snapshot, node.getType(), site.cursorOffset())) {
          return super.visitVariable(node, unused);
        }

        final var el = snapshot.trees().getElement(getCurrentPath());
        if (el != null) {
          result.set(el.asType());
        }

        return super.visitVariable(node, unused);
      }

      @Override
      public Void visitReturn(final ReturnTree node, final Void unused) {
        if (result.get() != null) {
          return super.visitReturn(node, unused);
        }

        if (cursorOutside(snapshot, node, site.cursorOffset())) {
          return super.visitReturn(node, unused);
        }

        final TypeMirror ret =
            resolveMethodReturnType(site.enclosingClass(), site.enclosingMethod(), snapshot);
        if (ret != null && ret.getKind() != TypeKind.VOID) {
          result.set(ret);
        }

        return super.visitReturn(node, unused);
      }

      @Override
      public Void visitAssignment(final AssignmentTree node, final Void unused) {
        if (result.get() != null) {
          return super.visitAssignment(node, unused);
        }

        if (cursorOutside(snapshot, node, site.cursorOffset())) {
          return super.visitAssignment(node, unused);
        }

        if (node.getVariable() != null
            && !cursorOutside(snapshot, node.getVariable(), site.cursorOffset())) {
          return super.visitAssignment(node, unused);
        }

        final var lhsPath = new TreePath(getCurrentPath(), node.getVariable());
        final TypeMirror tm = snapshot.trees().getTypeMirror(lhsPath);
        if (tm != null && tm.getKind() != TypeKind.ERROR && tm.getKind() != TypeKind.NONE) {
          result.set(tm);
        }

        return super.visitAssignment(node, unused);
      }

      @Override
      public Void visitBinary(final BinaryTree node, final Void unused) {
        if (result.get() != null) {
          return super.visitBinary(node, unused);
        }

        final var kind = node.getKind();
        if (kind != Tree.Kind.EQUAL_TO && kind != Tree.Kind.NOT_EQUAL_TO) {
          return super.visitBinary(node, unused);
        }

        if (cursorOutside(snapshot, node, site.cursorOffset())) {
          return super.visitBinary(node, unused);
        }

        final var lhs = node.getLeftOperand();
        final long lhsEnd =
            snapshot.trees().getSourcePositions().getEndPosition(snapshot.tree(), lhs);
        final Tree other = site.cursorOffset() > lhsEnd ? lhs : node.getRightOperand();
        final var otherPath = new TreePath(getCurrentPath(), other);
        final TypeMirror otherType = snapshot.trees().getTypeMirror(otherPath);
        if (otherType != null
            && otherType.getKind() != TypeKind.ERROR
            && otherType.getKind() != TypeKind.NONE) {
          result.set(otherType);
        }

        return super.visitBinary(node, unused);
      }
    }.scan(methodPath, null);

    return result.get() != null
        ? new ExpectedValue.Type(result.get())
        : new ExpectedValue.Unknown();
  }

  // ── shared primitives ─────────────────────────────────────────────────────────

  private static TypeMirror findMethodParamType(
      final TypeElement owner,
      final String methodName,
      final int argIndex,
      final AttributedFileAnalysis snapshot) {
    for (final var el : snapshot.elements().getAllMembers(owner)) {
      if (el.getKind() != ElementKind.METHOD) {
        continue;
      }

      if (!methodName.equals(el.getSimpleName().toString())) {
        continue;
      }

      final var method = (ExecutableElement) el;
      final var params = method.getParameters();
      final int idx = method.isVarArgs() ? Math.min(argIndex, params.size() - 1) : argIndex;
      if (idx >= 0 && idx < params.size()) {
        return params.get(idx).asType();
      }
    }

    return null;
  }

  private static ExpectedValue expectedMethodArgumentValue(
      final TypeElement owner,
      final String methodName,
      final int argIndex,
      final AttributedFileAnalysis snapshot) {
    final var paramType = findMethodParamType(owner, methodName, argIndex, snapshot);
    if (paramType != null) {
      return new ExpectedValue.Type(paramType);
    }

    return hasMethodByName(owner, methodName, snapshot)
        ? new ExpectedValue.NoSlot()
        : new ExpectedValue.Unknown();
  }

  private static boolean hasMethodByName(
      final TypeElement owner, final String methodName, final AttributedFileAnalysis snapshot) {
    return snapshot.elements().getAllMembers(owner).stream()
        .anyMatch(
            el ->
                el.getKind() == ElementKind.METHOD
                    && methodName.equals(el.getSimpleName().toString()));
  }

  private static TypeMirror resolveMethodReturnType(
      final String className, final String methodName, final AttributedFileAnalysis snapshot) {
    final var classEl = findClassElement(className, snapshot);
    if (classEl == null) {
      return null;
    }

    return snapshot.elements().getAllMembers(classEl).stream()
        .filter(el -> el.getKind() == ElementKind.METHOD)
        .filter(el -> methodName.equals(el.getSimpleName().toString()))
        .map(el -> ((ExecutableElement) el).getReturnType())
        .filter(t -> t.getKind() != TypeKind.VOID)
        .findFirst()
        .orElse(null);
  }

  private static String methodSelectName(final Tree methodSelect) {
    if (methodSelect instanceof final IdentifierTree id) {
      return id.getName().toString();
    }

    if (methodSelect instanceof final MemberSelectTree ms) {
      return ms.getIdentifier().toString();
    }

    return null;
  }

  private static boolean cursorOutside(
      final AttributedFileAnalysis snapshot, final Tree node, final int cursorOffset) {
    final var positions = snapshot.trees().getSourcePositions();
    final long start = positions.getStartPosition(snapshot.tree(), node);
    final long end = positions.getEndPosition(snapshot.tree(), node);
    return start < 0 || start >= cursorOffset || end < cursorOffset;
  }

  private static TreePath findScopeMethodPath(
      final String className,
      final String methodName,
      final int cursorOffset,
      final AttributedFileAnalysis snapshot) {
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
        if (className != null
            && !(parentLeaf instanceof final ClassTree cls
                && className.equals(cls.getSimpleName().toString()))) {
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

  private static TypeElement findClassElement(
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

  private static TreePath findMethodPath(
      final String className, final String methodName, final AttributedFileAnalysis snapshot) {
    final var result = new AtomicReference<TreePath>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitClass(final ClassTree node, final Void unused) {
        return super.visitClass(node, unused);
      }

      @Override
      public Void visitMethod(final MethodTree node, final Void unused) {
        if (result.get() != null || !methodName.equals(node.getName().toString())) {
          return null;
        }

        final var parentLeaf = getCurrentPath().getParentPath().getLeaf();
        if (!(parentLeaf instanceof final ClassTree cls)
            || !className.equals(cls.getSimpleName().toString())) {
          return null;
        }

        result.set(getCurrentPath());
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
      final AttributedFileAnalysis snapshot) {
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

  private static ExpectedValue resolveSwitchSelectorValue(
      final CompletionSite site, final AttributedFileAnalysis snapshot) {
    final TreePath path =
        SourceLocator.pathAt(snapshot.trees(), snapshot.tree(), site.cursorOffset());
    if (path == null) {
      return new ExpectedValue.Unknown();
    }

    for (TreePath current = path; current != null; current = current.getParentPath()) {
      final Tree leaf = current.getLeaf();
      if (leaf instanceof ClassTree
          || leaf instanceof MethodTree
          || leaf instanceof LambdaExpressionTree) {
        break;
      }

      final Tree selector =
          switch (leaf) {
            case final SwitchTree s -> s.getExpression();
            case final SwitchExpressionTree s -> s.getExpression();
            default -> null;
          };
      if (selector != null) {
        final TreePath selectorPath = new TreePath(current, selector);
        final TypeMirror type = snapshot.trees().getTypeMirror(selectorPath);
        if (type != null && type.getKind() != TypeKind.ERROR && type.getKind() != TypeKind.NONE) {
          return new ExpectedValue.Type(type);
        }
      }
    }

    return new ExpectedValue.Unknown();
  }
}
