package io.github.aglibs.lathe.analysis.completion;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.CursorElements;
import io.github.aglibs.lathe.analysis.FunctionalInterfaces;
import io.github.aglibs.lathe.analysis.TreePaths;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

final class ExpectedValueResolver {

  private static final Logger LOG = Logger.getLogger(ExpectedValueResolver.class.getName());

  private ExpectedValueResolver() {}

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

    if (site.sentinelContext() == SentinelContext.LAMBDA_BODY) {
      final ExpectedValue lambdaValue = resolveLambdaBodyExpectedValue(site, snapshot);
      if (!(lambdaValue instanceof ExpectedValue.Unknown)) {
        return lambdaValue;
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

  static CompletionLibraryRules.StaticMemberResultContext resolveStaticMemberResultContext(
      final CompletionSite site,
      final ParsedSentinel parsed,
      final AttributedFileAnalysis snapshot) {
    if (parsed.sentinelContext() != SentinelContext.MEMBER_ACCESS
        || site.enclosingMethod() == null) {
      return null;
    }

    final var rule =
        CompletionLibraryRules.staticMemberResultRule(parsed.receiverText()).orElse(null);
    if (rule == null) {
      return null;
    }

    final var methodPath =
        NameLookup.findMethodPath(
            site.enclosingClass(), site.enclosingMethod(), site.cursorOffset(), snapshot);
    if (methodPath == null || ((MethodTree) methodPath.getLeaf()).getBody() == null) {
      return null;
    }

    final var result = new AtomicReference<CompletionLibraryRules.StaticMemberResultContext>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitMethodInvocation(final MethodInvocationTree node, final Void unused) {
        if (result.get() != null || !rule.matchesEnclosingInvocation(getCurrentPath(), snapshot)) {
          return super.visitMethodInvocation(node, unused);
        }

        if (node.getArguments().size() <= rule.enclosingArgumentIndex()
            || cursorOutside(
                snapshot,
                node.getArguments().get(rule.enclosingArgumentIndex()),
                site.cursorOffset())) {
          return super.visitMethodInvocation(node, unused);
        }

        final TypeMirror expected = expectedTypeForExpression(getCurrentPath(), snapshot);
        if (expected != null
            && expected.getKind() != TypeKind.ERROR
            && expected.getKind() != TypeKind.NONE
            && expected.getKind() != TypeKind.VOID) {
          result.set(new CompletionLibraryRules.StaticMemberResultContext(rule, expected));
        }

        return super.visitMethodInvocation(node, unused);
      }
    }.scan(methodPath, null);

    return result.get();
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

  static boolean isInsideVoidLambdaBody(
      final int cursorOffset, final AttributedFileAnalysis snapshot) {
    final TreePath lambdaPath = findLambdaPathAtOffset(cursorOffset, snapshot);
    if (lambdaPath == null) {
      return false;
    }

    final TypeMirror samReturnType = resolveLambdaSamReturnType(lambdaPath, snapshot);
    return samReturnType != null && samReturnType.getKind() == TypeKind.VOID;
  }

  static boolean isInsideLambdaBody(final int cursorOffset, final AttributedFileAnalysis snapshot) {
    return findLambdaPathAtOffset(cursorOffset, snapshot) != null;
  }

  // The parameter count of the functional-interface (SAM) target at a `::` site, or -1 when the
  // expected type is unknown or not a functional interface. Drives method-reference arity ranking.
  static int methodReferenceSamArity(
      final int cursorOffset, final AttributedFileAnalysis snapshot) {
    if (!(resolveExpectedArgumentValue(cursorOffset, snapshot)
            instanceof final ExpectedValue.Type expectedType)
        || !(expectedType.type() instanceof final DeclaredType declared)) {
      return -1;
    }

    final ExecutableElement sam = findFunctionalInterfaceMethod(declared, snapshot);
    return sam == null ? -1 : sam.getParameters().size();
  }

  static boolean isVoidFunctionalInterface(
      final TypeMirror type, final AttributedFileAnalysis snapshot) {
    if (!(type instanceof final DeclaredType declared)) {
      return false;
    }

    final ExecutableElement sam = findFunctionalInterfaceMethod(declared, snapshot);
    if (sam == null) {
      return false;
    }

    final ExecutableType memberType = (ExecutableType) snapshot.types().asMemberOf(declared, sam);
    return memberType.getReturnType().getKind() == TypeKind.VOID;
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
    final TreePath classPath = TreePaths.enclosingClass(path);
    return classPath != null
            && snapshot.trees().getElement(classPath) instanceof final TypeElement type
        ? type
        : null;
  }

  // ── expected value resolution ─────────────────────────────────────────────────

  private static ExpectedValue resolveArgumentValue(
      final CompletionSite site, final int cursorLine, final AttributedFileAnalysis snapshot) {
    final String receiver = site.enclosingReceiver();
    final TypeElement ownerType;
    if (receiver == null || "this".equals(receiver)) {
      ownerType = NameLookup.findClassElement(site.enclosingClass(), snapshot);

    } else if (receiver.indexOf('.') < 0
        && receiver.indexOf('(') < 0
        && receiver.indexOf(' ') < 0) {
      final var localType =
          NameLookup.scanForLocalDeclaration(
              receiver, site.enclosingClass(), site.enclosingMethod(), cursorLine, snapshot);
      final var receiverType =
          localType != null
              ? localType
              : NameLookup.findFieldType(receiver, site.enclosingClass(), snapshot);
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
    final var classEl = NameLookup.findClassElement(site.enclosingMethodName(), snapshot);
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
    if (site.enclosingMethod() == null) {
      return new ExpectedValue.Unknown();
    }

    final var methodPath =
        NameLookup.findMethodPath(
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

      @Override
      public Void visitNewClass(final NewClassTree node, final Void unused) {
        if (result.get() != null || cursorOutside(snapshot, node, site.cursorOffset())) {
          return super.visitNewClass(node, unused);
        }

        if (!site.enclosingMethodName().equals(methodSelectName(node.getIdentifier()))) {
          return super.visitNewClass(node, unused);
        }

        if (!(snapshot.trees().getElement(getCurrentPath())
            instanceof final ExecutableElement ctor)) {
          return super.visitNewClass(node, unused);
        }

        final var params = ctor.getParameters();
        final int idx =
            ctor.isVarArgs() ? Math.min(site.argIndex(), params.size() - 1) : site.argIndex();
        if (idx >= 0 && idx < params.size()) {
          result.set(new ExpectedValue.Type(params.get(idx).asType()));
        }

        return null;
      }
    }.scan(methodPath, null);

    return result.get() != null ? result.get() : new ExpectedValue.Unknown();
  }

  private static ExpectedValue resolveInitializerValue(
      final CompletionSite site, final AttributedFileAnalysis snapshot) {
    if (site.enclosingMethod() == null) {
      return new ExpectedValue.Unknown();
    }

    final var cursorValue = resolveCursorExpressionValue(site, snapshot);
    if (cursorValue.isPresent()) {
      return cursorValue.orElseThrow();
    }

    final var methodPath =
        NameLookup.findMethodPath(
            site.enclosingClass(), site.enclosingMethod(), site.cursorOffset(), snapshot);
    if (methodPath == null || ((MethodTree) methodPath.getLeaf()).getBody() == null) {
      return new ExpectedValue.Unknown();
    }

    final var result = new AtomicReference<TypeMirror>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitVariable(final VariableTree node, final Void unused) {
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
        if (cursorOutside(snapshot, node, site.cursorOffset())) {
          return super.visitReturn(node, unused);
        }

        final var ret = findReturnTargetType(getCurrentPath(), snapshot);
        if (ret != null && ret.getKind() != TypeKind.VOID) {
          result.set(ret);
        }

        return super.visitReturn(node, unused);
      }

      @Override
      public Void visitThrow(final ThrowTree node, final Void unused) {
        if (cursorOutside(snapshot, node, site.cursorOffset())) {
          return super.visitThrow(node, unused);
        }

        final var throwable = snapshot.elements().getTypeElement("java.lang.Throwable");
        if (throwable != null) {
          result.set(throwable.asType());
        }

        return super.visitThrow(node, unused);
      }

      @Override
      public Void visitAssignment(final AssignmentTree node, final Void unused) {
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
        if (node.getKind() != Tree.Kind.EQUAL_TO && node.getKind() != Tree.Kind.NOT_EQUAL_TO) {
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

  private static Optional<ExpectedValue> resolveCursorExpressionValue(
      final CompletionSite site, final AttributedFileAnalysis snapshot) {
    final var cursorPath =
        CursorElements.pathAt(snapshot.trees(), snapshot.tree(), site.cursorOffset());
    return cursorPath != null
        ? resolveCursorExpressionValue(cursorPath, cursorPath.getParentPath(), snapshot)
        : Optional.empty();
  }

  private static Optional<ExpectedValue> resolveCursorExpressionValue(
      final TreePath child, final TreePath parent, final AttributedFileAnalysis snapshot) {
    if (parent == null) {
      return Optional.empty();
    }

    final Tree leaf = parent.getLeaf();
    if (leaf instanceof ReturnTree || leaf instanceof LambdaExpressionTree) {
      return Optional.empty();
    }

    if (leaf instanceof final VariableTree variable
        && variable.getInitializer() == child.getLeaf()) {
      final TypeMirror type = variableDeclaredType(parent, variable, snapshot);
      return Optional.of(type != null ? new ExpectedValue.Type(type) : new ExpectedValue.Unknown());
    }

    if (leaf instanceof final AssignmentTree assignment
        && assignment.getExpression() == child.getLeaf()) {
      final TypeMirror type = assignmentTargetType(parent, assignment, snapshot);
      return Optional.of(type != null ? new ExpectedValue.Type(type) : new ExpectedValue.Unknown());
    }

    return resolveCursorExpressionValue(parent, parent.getParentPath(), snapshot);
  }

  // ── shared primitives ─────────────────────────────────────────────────────────

  private static TypeMirror expectedTypeForExpression(
      final TreePath expressionPath, final AttributedFileAnalysis snapshot) {
    var child = expressionPath;
    var parent = child.getParentPath();
    while (parent != null) {
      final Tree leaf = parent.getLeaf();
      if (leaf instanceof final ReturnTree returnTree
          && returnTree.getExpression() == child.getLeaf()) {
        return findReturnTargetType(parent, snapshot);
      }

      if (leaf instanceof final AssignmentTree assignment
          && assignment.getExpression() == child.getLeaf()) {
        return assignmentTargetType(parent, assignment, snapshot);
      }

      if (leaf instanceof final VariableTree variable
          && variable.getInitializer() == child.getLeaf()) {
        return variableDeclaredType(parent, variable, snapshot);
      }

      child = parent;
      parent = parent.getParentPath();
    }

    return null;
  }

  private static TypeMirror assignmentTargetType(
      final TreePath assignmentPath,
      final AssignmentTree assignment,
      final AttributedFileAnalysis snapshot) {
    final var lhsPath = new TreePath(assignmentPath, assignment.getVariable());
    final TypeMirror tm = snapshot.trees().getTypeMirror(lhsPath);
    return tm != null && tm.getKind() != TypeKind.ERROR && tm.getKind() != TypeKind.NONE
        ? tm
        : null;
  }

  private static TypeMirror variableDeclaredType(
      final TreePath variablePath,
      final VariableTree variable,
      final AttributedFileAnalysis snapshot) {
    if (variable.getType() == null || "var".equals(variable.getType().toString())) {
      return null;
    }

    final Element element = snapshot.trees().getElement(variablePath);
    final TypeMirror tm = element != null ? element.asType() : null;
    return tm != null && tm.getKind() != TypeKind.ERROR && tm.getKind() != TypeKind.NONE
        ? tm
        : null;
  }

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
    final var classEl = NameLookup.findClassElement(className, snapshot);
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

  private static ExpectedValue resolveSwitchSelectorValue(
      final CompletionSite site, final AttributedFileAnalysis snapshot) {
    final TreePath path =
        CursorElements.pathAt(snapshot.trees(), snapshot.tree(), site.cursorOffset());
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
          leaf instanceof final SwitchTree statement
              ? statement.getExpression()
              : leaf instanceof final SwitchExpressionTree expression
                  ? expression.getExpression()
                  : null;
      if (selector != null) {
        final var selectorPath = new TreePath(current, selector);
        final TypeMirror type = snapshot.trees().getTypeMirror(selectorPath);
        if (type != null && type.getKind() != TypeKind.ERROR && type.getKind() != TypeKind.NONE) {
          return new ExpectedValue.Type(type);
        }
      }
    }

    return new ExpectedValue.Unknown();
  }

  private static ExpectedValue resolveLambdaBodyExpectedValue(
      final CompletionSite site, final AttributedFileAnalysis snapshot) {
    final TreePath lambdaPath = findLambdaPathAtOffset(site.cursorOffset(), snapshot);
    if (lambdaPath == null) {
      return new ExpectedValue.Unknown();
    }

    final Optional<TypeMirror> projected =
        LambdaExpectedReturnTypeResolver.resolve(lambdaPath, snapshot);
    if (projected.isPresent()) {
      return new ExpectedValue.Type(projected.get());
    }

    final TypeMirror samReturnType = resolveLambdaSamReturnType(lambdaPath, snapshot);
    return completeType(samReturnType)
        ? new ExpectedValue.Type(samReturnType)
        : new ExpectedValue.Unknown();
  }

  private static TreePath findLambdaPathAtOffset(
      final int cursorOffset, final AttributedFileAnalysis snapshot) {
    final var positions = snapshot.trees().getSourcePositions();
    final var cu = snapshot.tree();
    final var result = new AtomicReference<TreePath>();
    final var size = new AtomicLong(Long.MAX_VALUE);
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitLambdaExpression(final LambdaExpressionTree node, final Void unused) {
        final long start = positions.getStartPosition(cu, node);
        final long end = positions.getEndPosition(cu, node);
        final long span = end - start;
        if (start <= cursorOffset && cursorOffset <= end && span <= size.get()) {
          size.set(span);
          result.set(getCurrentPath());
        }
        return super.visitLambdaExpression(node, unused);
      }

      @Override
      public Void visitErroneous(final ErroneousTree node, final Void unused) {
        for (final var e : node.getErrorTrees()) {
          scan(e, unused);
        }
        return null;
      }
    }.scan(cu, null);
    return result.get();
  }

  private static TypeMirror resolveLambdaSamReturnType(
      final TreePath lambdaPath, final AttributedFileAnalysis snapshot) {
    final TypeMirror lambdaType = snapshot.trees().getTypeMirror(lambdaPath);
    if (lambdaType != null && lambdaType.getKind() == TypeKind.DECLARED) {
      final DeclaredType declared = (DeclaredType) lambdaType;
      final ExecutableElement sam = findFunctionalInterfaceMethod(declared, snapshot);
      if (sam != null) {
        final ExecutableType memberType =
            (ExecutableType) snapshot.types().asMemberOf(declared, sam);
        return memberType.getReturnType();
      }
    }
    return null;
  }

  private static boolean completeType(final TypeMirror type) {
    return type != null
        && type.getKind() != TypeKind.VOID
        && type.getKind() != TypeKind.NONE
        && type.getKind() != TypeKind.ERROR;
  }

  private static ExecutableElement findFunctionalInterfaceMethod(
      final DeclaredType declared, final AttributedFileAnalysis snapshot) {
    return declared.asElement() instanceof final TypeElement element
        ? FunctionalInterfaces.singleAbstractMethod(element, snapshot.elements())
        : null;
  }

  private static TypeMirror findReturnTargetType(
      final TreePath returnPath, final AttributedFileAnalysis snapshot) {
    for (var current = returnPath.getParentPath();
        current != null;
        current = current.getParentPath()) {
      if (current.getLeaf() instanceof LambdaExpressionTree) {
        return LambdaExpectedReturnTypeResolver.resolve(current, snapshot).orElse(null);
      }

      if (current.getLeaf() instanceof MethodTree) {
        final var el = snapshot.trees().getElement(current);
        return el instanceof final ExecutableElement exec ? exec.getReturnType() : null;
      }
    }

    return null;
  }
}
