package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.*;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.server.analysis.CompilerTrees;
import io.github.aglibs.lathe.server.analysis.SourceParser;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import javax.lang.model.element.Modifier;

final class SentinelParser {

  private static final Logger LOG = Logger.getLogger(SentinelParser.class.getName());

  private final SourceParser sourceParser;

  SentinelParser(final SourceParser sourceParser) {
    this.sourceParser = sourceParser;
  }

  ParsedSentinel parse(
      final SentinelInjectionResult injected,
      final int expectedLspLine,
      final int version,
      final String uri) {
    return sourceParser
        .parseContent(
            uri,
            injected.injectedContent(),
            (trees, cu) -> findSentinel(trees, cu, injected, expectedLspLine, version))
        .orElseGet(
            () -> ParsedSentinel.invalid(injected.prefix(), injected.receiverText(), version));
  }

  private static ParsedSentinel findSentinel(
      final Trees trees,
      final CompilationUnitTree cu,
      final SentinelInjectionResult injected,
      final int expectedLspLine,
      final int version) {

    final var sentinelPath = new SentinelFinder().scan(cu, null);
    if (sentinelPath == null) {
      LOG.fine(() -> "[sentinel-parse] sentinel not found in parse tree");
      if (cu.getModule() != null) {
        return ParsedSentinel.valid(
            injected, SentinelContext.MODULE_DIRECTIVE, -1, ModuleDirectiveKind.NONE, version);
      }

      return ParsedSentinel.invalid(injected.prefix(), injected.receiverText(), version);
    }

    final var sourcePositions = trees.getSourcePositions();
    final int receiverEndOffset;
    if (sentinelPath.getLeaf() instanceof final MemberSelectTree memberSelect) {
      final long pos = sourcePositions.getEndPosition(cu, memberSelect.getExpression());
      receiverEndOffset = pos >= 0 ? (int) pos : -1;
    } else if (sentinelPath.getLeaf() instanceof final MemberReferenceTree memberReference) {
      final long pos = sourcePositions.getEndPosition(cu, memberReference.getQualifierExpression());
      receiverEndOffset = pos >= 0 ? (int) pos : -1;
    } else {
      receiverEndOffset = -1;
    }

    final long startPos = sourcePositions.getEndPosition(cu, sentinelPath.getLeaf());
    final long javacLine = cu.getLineMap().getLineNumber(startPos);
    if (javacLine - 1 != expectedLspLine) {
      LOG.fine(
          () ->
              "[sentinel-parse] sentinel moved: expected lsp-line=%d javac-line=%d"
                  .formatted(expectedLspLine, javacLine));
      return ParsedSentinel.invalid(injected.prefix(), injected.receiverText(), version);
    }

    if (injected.prefix().isEmpty() && !injected.hasDot() && !injected.memberReference()) {
      final long sentinelStart = sourcePositions.getStartPosition(cu, sentinelPath.getLeaf());
      if (sentinelStart >= 0
          && expressionEndsBefore(sentinelStart, cu, sourcePositions, injected.injectedContent())) {
        return ParsedSentinel.invalid(injected.prefix(), injected.receiverText(), version);
      }
    }

    final var importTree = enclosingImport(sentinelPath);
    if (importTree != null) {
      final var parsed =
          ParsedSentinel.valid(
              injected,
              importTree.isStatic() ? SentinelContext.STATIC_IMPORT : SentinelContext.IMPORT,
              receiverEndOffset,
              null,
              version);
      logValid(parsed);
      return parsed;
    }

    final var parentPath = sentinelPath.getParentPath();
    final Classification classified =
        parentPath != null
            ? classifySentinel(sentinelPath.getLeaf(), parentPath)
            : Classification.of(SentinelContext.SIMPLE_NAME);
    final Classification cls = correctExplicitDotRecovery(injected, classified);

    String enclosingClass = null;
    String enclosingMethod = null;
    for (final Tree node : sentinelPath) {
      if (node instanceof MethodTree m && enclosingMethod == null) {
        enclosingMethod = m.getName().toString();
      } else if (node instanceof ClassTree c) {
        enclosingClass = c.getSimpleName().toString();
        break;
      }
    }

    boolean enclosedByLoop = false;
    boolean enclosedBySwitchStatement = false;
    boolean enclosedBySwitchExpression = false;
    boolean inEqualityComparison = false;
    for (final Tree node : sentinelPath) {
      if (node instanceof MethodTree
          || node instanceof LambdaExpressionTree
          || node instanceof ClassTree) {
        break;
      }
      if (node instanceof ForLoopTree
          || node instanceof EnhancedForLoopTree
          || node instanceof WhileLoopTree
          || node instanceof DoWhileLoopTree) {
        enclosedByLoop = true;
      } else if (node instanceof SwitchExpressionTree) {
        enclosedBySwitchExpression = true;
      } else if (node instanceof SwitchTree) {
        enclosedBySwitchStatement = true;
      } else if (node instanceof final BinaryTree binary) {
        final var kind = binary.getKind();
        if (kind == Tree.Kind.EQUAL_TO || kind == Tree.Kind.NOT_EQUAL_TO) {
          inEqualityComparison = true;
        }
      }
    }

    if (enclosingClass == null && cu.getModule() != null) {
      final var keyword = findDirectiveKeyword(cu.getModule());
      final var parsed =
          ParsedSentinel.valid(
              injected, SentinelContext.MODULE_DIRECTIVE, receiverEndOffset, keyword, version);
      logValid(parsed);
      return parsed;
    }

    // A catch parameter's name slot is a VariableTree whose parent is the CatchTree, distinguishing
    // it from a local declared inside the catch block.
    final boolean catchParameter =
        cls.context() == SentinelContext.VARIABLE_DECLARATION
            && parentPath != null
            && parentPath.getLeaf() instanceof CatchTree;

    // Only an explicit `static final` field is a constant; its name slot earns SCREAMING_SNAKE
    // suggestions. Implicit interface constants carry no keyword flags here, so they fall through.
    final boolean constantField =
        cls.context() == SentinelContext.VARIABLE_DECLARATION
            && sentinelPath.getLeaf() instanceof VariableTree v
            && v.getModifiers().getFlags().contains(Modifier.STATIC)
            && v.getModifiers().getFlags().contains(Modifier.FINAL);

    final var parsed =
        ParsedSentinel.valid(
            injected,
            cls.context(),
            receiverEndOffset,
            enclosingClass,
            enclosingMethod,
            cls.argIndex(),
            cls.enclosingReceiver(),
            cls.enclosingMethodName(),
            cls.lambdaParamIndex(),
            cls.declaredTypeText(),
            cls.declaredElementTypeName(),
            cls.annotationTypeText(),
            cls.typeReferenceRole(),
            enclosedByLoop,
            enclosedBySwitchStatement,
            enclosedBySwitchExpression,
            inEqualityComparison,
            cls.inExpression(),
            catchParameter,
            constantField,
            null,
            version);

    logValid(parsed);
    return parsed;
  }

  private static void logValid(final ParsedSentinel parsed) {
    LOG.fine(
        () ->
            "[sentinel-parse] valid ctx=%s prefix=|%s| receiver=|%s|"
                .formatted(parsed.sentinelContext(), parsed.prefix(), parsed.receiverText()));
  }

  private static Classification correctExplicitDotRecovery(
      final SentinelInjectionResult injected, final Classification classified) {
    if (injected.hasDot()
        && injected.receiverText() != null
        && classified.context() == SentinelContext.SIMPLE_NAME) {
      return Classification.of(SentinelContext.MEMBER_ACCESS);
    }

    return classified;
  }

  private static boolean expressionEndsBefore(
      final long sentinelStart,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final String source) {
    final var found = new java.util.concurrent.atomic.AtomicBoolean();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitMethodInvocation(final MethodInvocationTree node, final Void unused) {
        checkEnd(node);
        return super.visitMethodInvocation(node, unused);
      }

      @Override
      public Void visitArrayAccess(final ArrayAccessTree node, final Void unused) {
        checkEnd(node);
        return super.visitArrayAccess(node, unused);
      }

      private void checkEnd(final Tree node) {
        if (found.get()) {
          return;
        }

        final long end = positions.getEndPosition(cu, node);
        if (end <= 0 || end > sentinelStart) {
          return;
        }

        for (long i = end; i < sentinelStart; i++) {
          if (!Character.isWhitespace(source.charAt((int) i))) {
            return;
          }
        }

        found.set(true);
      }
    }.scan(cu, null);
    return found.get();
  }

  private static ImportTree enclosingImport(final TreePath path) {
    for (final Tree node : path) {
      if (node instanceof final ImportTree importTree) {
        return importTree;
      }
    }

    return null;
  }

  private record Classification(
      SentinelContext context,
      int argIndex,
      String enclosingReceiver,
      String enclosingMethodName,
      int lambdaParamIndex,
      String declaredTypeText,
      String declaredElementTypeName,
      String annotationTypeText,
      TypeReferenceRole typeReferenceRole,
      boolean inExpression) {

    static Classification of(final SentinelContext ctx) {
      return new Classification(
          ctx, -1, null, null, -1, null, null, null, TypeReferenceRole.ORDINARY, false);
    }

    static Classification typeReference(final TypeReferenceRole role) {
      return new Classification(
          SentinelContext.TYPE_REFERENCE, -1, null, null, -1, null, null, null, role, false);
    }

    static Classification annotation() {
      return new Classification(
          SentinelContext.ANNOTATION_CONTEXT,
          -1,
          null,
          null,
          -1,
          null,
          null,
          null,
          TypeReferenceRole.ANNOTATION,
          false);
    }

    static Classification annotationArgument(final String annotationTypeText) {
      return new Classification(
          SentinelContext.ANNOTATION_ARGUMENT,
          -1,
          null,
          null,
          -1,
          null,
          null,
          annotationTypeText,
          TypeReferenceRole.ORDINARY,
          false);
    }

    static Classification annotationArgumentValue(
        final String annotationTypeName, final String elementName) {
      return new Classification(
          SentinelContext.ANNOTATION_ARGUMENT_VALUE,
          -1,
          null,
          elementName, // enclosingMethodName repurposed: stores the element name
          -1,
          null,
          null,
          annotationTypeName,
          TypeReferenceRole.ORDINARY,
          false);
    }

    static Classification expression() {
      return new Classification(
          SentinelContext.SIMPLE_NAME,
          -1,
          null,
          null,
          -1,
          null,
          null,
          null,
          TypeReferenceRole.ORDINARY,
          true);
    }
  }

  private static Classification classifySentinel(final Tree sentinel, final TreePath parentPath) {
    if (sentinel instanceof VariableTree v) {
      return classifyVariableDeclaration(v);
    }

    if (sentinel instanceof MemberReferenceTree) {
      return Classification.of(SentinelContext.MEMBER_REFERENCE);
    }

    final Tree parent = parentPath.getLeaf();
    final boolean simpleName = !(sentinel instanceof MemberSelectTree);
    final String annotationArgumentType = annotationArgumentType(sentinel, parentPath);
    if (!simpleName && annotationArgumentType != null) {
      return Classification.of(SentinelContext.MEMBER_ACCESS);
    }

    final AnnotationValueInfo annotationValue = annotationArgumentValue(sentinel, parentPath);
    if (annotationValue != null) {
      return Classification.annotationArgumentValue(
          annotationValue.annotationType(), annotationValue.elementName());
    }

    if (annotationArgumentType != null) {
      return Classification.annotationArgument(annotationArgumentType);
    }

    final TypeReferenceRole inferredRole = inferTypeReferenceRole(sentinel, parentPath);

    if (inferredRole == TypeReferenceRole.ANNOTATION) {
      return Classification.annotation();
    }

    if (inferredRole != TypeReferenceRole.ORDINARY) {
      return Classification.typeReference(inferredRole);
    }

    // --- statement-level expression positions ---
    if (simpleName
        && (parent instanceof final ReturnTree returnTree && returnTree.getExpression() == sentinel
            || parent instanceof final ThrowTree throwTree && throwTree.getExpression() == sentinel
            || parent instanceof final VariableTree variable
                && variable.getInitializer() == sentinel)) {
      return Classification.expression();
    }

    // --- expression-tree parents: sentinel is a sub-expression ---
    if (parent instanceof ConditionalExpressionTree
        || parent instanceof BinaryTree
        || parent instanceof UnaryTree
        || parent instanceof ParenthesizedTree
        || parent instanceof final InstanceOfTree instanceOf
            && instanceOf.getExpression() == sentinel
        || parent instanceof final TypeCastTree cast && cast.getExpression() == sentinel
        || parent instanceof final AssignmentTree assignment
            && assignment.getExpression() == sentinel
        || parent instanceof final CompoundAssignmentTree compound
            && compound.getExpression() == sentinel
        || parent instanceof final ArrayAccessTree access && access.getIndex() == sentinel
        || parent instanceof final NewArrayTree newArray
            && newArray.getDimensions().stream().anyMatch(d -> d == sentinel)) {
      return Classification.expression();
    }

    if (parent instanceof final MemberSelectTree m && m.getExpression() == sentinel) {
      if (isClassLiteral(m)) {
        return Classification.typeReference(inferredRole);
      }

      return simpleName
          ? Classification.expression()
          : Classification.of(SentinelContext.MEMBER_ACCESS);
    }

    // --- control-flow condition / selector positions ---
    if (parent instanceof final IfTree ifTree && ifTree.getCondition() == sentinel
        || parent instanceof final WhileLoopTree whileLoop && whileLoop.getCondition() == sentinel
        || parent instanceof final DoWhileLoopTree doWhile && doWhile.getCondition() == sentinel
        || parent instanceof final ForLoopTree forLoop && forLoop.getCondition() == sentinel
        || parent instanceof final EnhancedForLoopTree forEach
            && forEach.getExpression() == sentinel
        || parent instanceof final SynchronizedTree sync && sync.getExpression() == sentinel
        || parent instanceof final SwitchTree switchTree && switchTree.getExpression() == sentinel
        || parent instanceof final SwitchExpressionTree switchExpression
            && switchExpression.getExpression() == sentinel) {
      return Classification.expression();
    }

    // --- method-call / constructor / lambda / annotation: existing paths ---
    if (parent instanceof final MethodInvocationTree m
        && simpleName
        && m.getArguments().stream().anyMatch(a -> a == sentinel)) {
      return classifyMethodInvocation(sentinel, m);
    }

    if (parent instanceof final LambdaExpressionTree lambda) {
      return classifyLambda(sentinel, lambda);
    }

    if (parent instanceof final NewClassTree m && simpleName) {
      return classifyConstructorCall(sentinel, m);
    }

    if (parent instanceof AnnotationTree) {
      return Classification.annotation();
    }

    // --- type-reference positions ---
    if (parent instanceof final VariableTree variable && variable.getType() == sentinel
        || parent instanceof final MethodTree method && method.getReturnType() == sentinel
        || parent instanceof final TypeCastTree cast && cast.getType() == sentinel
        || parent instanceof ParameterizedTypeTree
        || parent instanceof ClassTree
        || parent instanceof ArrayTypeTree
        || parent instanceof WildcardTree
        || parent instanceof TypeParameterTree) {
      return Classification.typeReference(inferredRole);
    }

    if (CompilerTrees.isCaseLabel(parent, sentinel)) {
      return Classification.of(SentinelContext.CASE_LABEL);
    }

    return classifyDefault(sentinel);
  }

  private static Classification classifyVariableDeclaration(final VariableTree v) {
    final var type = v.getType();
    // Treat an erroneous type as absent so the position is not flagged as a typed name slot.
    final boolean realType = type != null && type.getKind() != Tree.Kind.ERRONEOUS;
    return new Classification(
        SentinelContext.VARIABLE_DECLARATION,
        -1,
        null,
        null,
        -1,
        realType ? simpleTypeName(type) : null,
        realType ? elementTypeName(type) : null,
        null,
        TypeReferenceRole.ORDINARY,
        false);
  }

  // The declared type's simple name, taken from the AST so no type text is parsed: the base of a
  // generic type (List<String> -> List) and the last segment of a qualified one (java.util.List ->
  // List). Other shapes (primitives, arrays) keep their source form and are filtered downstream.
  private static String simpleTypeName(final Tree type) {
    if (type instanceof final IdentifierTree id) {
      return id.getName().toString();
    }

    if (type instanceof final ParameterizedTypeTree parameterized) {
      return simpleTypeName(parameterized.getType());
    }

    if (type instanceof final MemberSelectTree member) {
      return member.getIdentifier().toString();
    }

    return type.toString();
  }

  // The simple name of the last type argument of a generic type (Map<String, User> -> User), or
  // null
  // for a non-generic type. The last argument is the element/value in the common containers.
  private static String elementTypeName(final Tree type) {
    if (type instanceof ParameterizedTypeTree parameterized
        && !parameterized.getTypeArguments().isEmpty()) {
      final var args = parameterized.getTypeArguments();
      return simpleTypeName(args.get(args.size() - 1));
    }

    return null;
  }

  private static Classification classifyMethodInvocation(
      final Tree sentinel, final MethodInvocationTree m) {
    final var args = m.getArguments();
    final int argIndex =
        IntStream.range(0, args.size()).filter(j -> args.get(j) == sentinel).findFirst().orElse(0);
    final String enclosingReceiver;
    final String enclosingMethodName;
    if (m.getMethodSelect() instanceof final MemberSelectTree sel) {
      enclosingReceiver = sel.getExpression().toString();
      enclosingMethodName = sel.getIdentifier().toString();
    } else {
      enclosingReceiver = null;
      enclosingMethodName = m.getMethodSelect().toString();
    }

    return new Classification(
        SentinelContext.ARGUMENT_POSITION,
        argIndex,
        enclosingReceiver,
        enclosingMethodName,
        -1,
        null,
        null,
        null,
        TypeReferenceRole.ORDINARY,
        false);
  }

  private static Classification classifyLambda(
      final Tree sentinel, final LambdaExpressionTree lambda) {
    if (!(sentinel instanceof final MemberSelectTree sel)) {
      return new Classification(
          SentinelContext.LAMBDA_BODY,
          -1,
          null,
          null,
          -1,
          null,
          null,
          null,
          TypeReferenceRole.ORDINARY,
          false);
    }

    final var params = lambda.getParameters();
    final var receiver = sel.getExpression().toString();
    final int lambdaParamIndex =
        IntStream.range(0, params.size())
            .filter(j -> params.get(j).getName().toString().equals(receiver))
            .findFirst()
            .orElse(-1);
    return new Classification(
        SentinelContext.LAMBDA_BODY,
        -1,
        null,
        null,
        lambdaParamIndex,
        null,
        null,
        null,
        TypeReferenceRole.ORDINARY,
        false);
  }

  private static Classification classifyConstructorCall(
      final Tree sentinel, final NewClassTree newClass) {
    final var args = newClass.getArguments();
    final int argIndex =
        IntStream.range(0, args.size()).filter(j -> args.get(j) == sentinel).findFirst().orElse(-1);
    final String constructorClassName = argIndex >= 0 ? newClass.getIdentifier().toString() : null;
    return new Classification(
        SentinelContext.CONSTRUCTOR_CALL,
        argIndex,
        null,
        constructorClassName,
        -1,
        null,
        null,
        null,
        argIndex < 0 ? TypeReferenceRole.CONSTRUCTOR : TypeReferenceRole.ORDINARY,
        false);
  }

  private static String annotationArgumentType(final Tree sentinel, final TreePath parentPath) {
    Tree child = sentinel;
    for (TreePath path = parentPath; path != null; path = path.getParentPath()) {
      final Tree parent = path.getLeaf();
      final Tree currentChild = child;

      if (parent instanceof final AnnotationTree annotationTree) {
        return annotationTree.getAnnotationType() == currentChild
            ? null
            : annotationTree.getAnnotationType().toString();
      }

      child = parent;
    }

    return null;
  }

  private record AnnotationValueInfo(String annotationType, String elementName) {}

  private static AnnotationValueInfo annotationArgumentValue(
      final Tree sentinel, final TreePath parentPath) {
    Tree child = sentinel;
    for (TreePath path = parentPath; path != null; path = path.getParentPath()) {
      final Tree parent = path.getLeaf();
      final Tree currentChild = child;

      if (parent instanceof final AssignmentTree assignmentTree
          && assignmentTree.getExpression() == currentChild) {
        for (TreePath p = path.getParentPath(); p != null; p = p.getParentPath()) {
          if (p.getLeaf() instanceof final AnnotationTree annotationTree) {
            return new AnnotationValueInfo(
                annotationTree.getAnnotationType().toString(),
                assignmentTree.getVariable().toString());
          }
        }
      }

      if (parent instanceof final AnnotationTree annotationTree
          && !(currentChild instanceof AssignmentTree)
          && annotationTree.getArguments().stream().anyMatch(arg -> arg == currentChild)) {
        return new AnnotationValueInfo(annotationTree.getAnnotationType().toString(), "value");
      }

      child = parent;
    }

    return null;
  }

  private static TypeReferenceRole inferTypeReferenceRole(
      final Tree sentinel, final TreePath parentPath) {
    Tree child = sentinel;
    for (TreePath path = parentPath; path != null; path = path.getParentPath()) {
      final Tree parent = path.getLeaf();
      final Tree currentChild = child;

      if (parent instanceof final AnnotationTree annotationTree
          && annotationTree.getAnnotationType() == currentChild) {
        return TypeReferenceRole.ANNOTATION;
      }

      if (parent instanceof final MethodTree method
          && method.getThrows().stream().anyMatch(t -> t == currentChild)) {
        return TypeReferenceRole.THROWS;
      }

      if (parent instanceof final ThrowTree throwTree
          && throwTree.getExpression() == currentChild
          && currentChild != sentinel) {
        return TypeReferenceRole.THROWS;
      }

      if (parent instanceof final ClassTree cls) {
        final TypeReferenceRole role = inferClassHeaderRole(currentChild, cls);
        if (role != TypeReferenceRole.ORDINARY) {
          return role;
        }
      }

      child = parent;
    }

    return TypeReferenceRole.ORDINARY;
  }

  private static boolean isClassLiteral(final MemberSelectTree memberSelect) {
    return "class".contentEquals(memberSelect.getIdentifier());
  }

  private static TypeReferenceRole inferClassHeaderRole(final Tree child, final ClassTree cls) {
    if (cls.getExtendsClause() == child) {
      return cls.getKind() == Tree.Kind.INTERFACE
          ? TypeReferenceRole.INTERFACE_EXTENDS
          : TypeReferenceRole.CLASS_EXTENDS;
    }

    if (cls.getImplementsClause().stream().anyMatch(t -> t == child)) {
      return cls.getKind() == Tree.Kind.RECORD
          ? TypeReferenceRole.RECORD_IMPLEMENTS
          : TypeReferenceRole.CLASS_IMPLEMENTS;
    }

    return TypeReferenceRole.ORDINARY;
  }

  private static Classification classifyDefault(final Tree sentinel) {
    return Classification.of(
        sentinel instanceof MemberSelectTree
            ? SentinelContext.MEMBER_ACCESS
            : SentinelContext.SIMPLE_NAME);
  }

  static ModuleDirectiveKind findDirectiveKeyword(final ModuleTree moduleTree) {
    if (containsSentinel(moduleTree.getName())) {
      return ModuleDirectiveKind.MODULE;
    }

    for (final var directive : moduleTree.getDirectives()) {
      switch (directive.getKind()) {
        case EXPORTS -> {
          if (containsSentinel(((ExportsTree) directive).getPackageName())) {
            return ModuleDirectiveKind.EXPORTS;
          }
        }
        case OPENS -> {
          if (containsSentinel(((OpensTree) directive).getPackageName())) {
            return ModuleDirectiveKind.OPENS;
          }
        }
        case REQUIRES -> {
          final var req = (RequiresTree) directive;
          if (containsSentinel(req.getModuleName())) {
            return req.isTransitive()
                ? ModuleDirectiveKind.REQUIRES_TRANSITIVE
                : req.isStatic()
                    ? ModuleDirectiveKind.REQUIRES_STATIC
                    : ModuleDirectiveKind.REQUIRES;
          }
        }
        case USES -> {
          if (containsSentinel(((UsesTree) directive).getServiceName())) {
            return ModuleDirectiveKind.USES;
          }
        }
        case PROVIDES -> {
          final var prov = (ProvidesTree) directive;
          if (containsSentinel(prov.getServiceName())) {
            return ModuleDirectiveKind.PROVIDES;
          }

          for (final ExpressionTree impl : prov.getImplementationNames()) {
            if (containsSentinel(impl)) {
              return ModuleDirectiveKind.WITH;
            }
          }
        }
        default -> {}
      }
    }

    return ModuleDirectiveKind.NONE;
  }

  private static boolean containsSentinel(final Tree tree) {
    return tree != null && tree.toString().contains(SentinelInjector.SENTINEL);
  }

  private static final class SentinelFinder extends TreePathScanner<TreePath, Void> {

    @Override
    public TreePath visitVariable(final VariableTree node, final Void unused) {
      if (SentinelInjector.SENTINEL.equals(node.getName().toString())) {
        return getCurrentPath();
      }

      return super.visitVariable(node, unused);
    }

    @Override
    public TreePath visitMemberSelect(final MemberSelectTree node, final Void unused) {
      if (SentinelInjector.SENTINEL.equals(node.getIdentifier().toString())) {
        return getCurrentPath();
      }

      return super.visitMemberSelect(node, unused);
    }

    @Override
    public TreePath visitMemberReference(final MemberReferenceTree node, final Void unused) {
      if (SentinelInjector.SENTINEL.equals(node.getName().toString())) {
        return getCurrentPath();
      }

      return super.visitMemberReference(node, unused);
    }

    @Override
    public TreePath visitIdentifier(final IdentifierTree node, final Void unused) {
      if (SentinelInjector.SENTINEL.equals(node.getName().toString())) {
        return getCurrentPath();
      }

      return super.visitIdentifier(node, unused);
    }

    @Override
    public TreePath visitErroneous(final ErroneousTree node, final Void unused) {
      // javac wraps non-statement expressions (e.g. bare member access) in ErroneousTree;
      // scan inside so the sentinel is still reachable.
      return scan(node.getErrorTrees(), unused);
    }

    @Override
    public TreePath reduce(final TreePath r1, final TreePath r2) {
      return r1 != null ? r1 : r2;
    }
  }
}
