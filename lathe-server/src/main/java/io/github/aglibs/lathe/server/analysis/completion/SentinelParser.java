package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.*;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.server.analysis.SourceParser;
import java.util.logging.Logger;
import java.util.stream.IntStream;

final class SentinelParser {

  private static final Logger LOG = Logger.getLogger(SentinelParser.class.getName());

  private final SourceParser sourceParser;

  SentinelParser(final SourceParser sourceParser) {
    this.sourceParser = sourceParser;
  }

  ParsedSentinel parse(
      final SentinelResult injected, final int expectedLspLine, final int version) {
    return sourceParser
        .parseContent(
            "sentinel://completion.java",
            injected.injectedContent(),
            (trees, cu) -> findSentinel(trees, cu, injected, expectedLspLine, version))
        .orElseGet(
            () -> ParsedSentinel.invalid(injected.prefix(), injected.receiverText(), version));
  }

  private static ParsedSentinel findSentinel(
      final Trees trees,
      final CompilationUnitTree cu,
      final SentinelResult injected,
      final int expectedLspLine,
      final int version) {

    final TreePath sentinelPath = new SentinelFinder().scan(cu, null);
    if (sentinelPath == null) {
      LOG.fine(() -> "[sentinel-parse] sentinel not found in parse tree");
      return ParsedSentinel.invalid(injected.prefix(), injected.receiverText(), version);
    }

    final SourcePositions sourcePositions = trees.getSourcePositions();
    final int receiverEndOffset;
    if (sentinelPath.getLeaf() instanceof final MemberSelectTree memberSelect) {
      final long pos = sourcePositions.getEndPosition(cu, memberSelect.getExpression());
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

    if (injected.prefix().isEmpty() && !injected.hasDot()) {
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
              version);
      logValid(parsed);
      return parsed;
    }

    final var parentPath = sentinelPath.getParentPath();
    final Classification cls =
        parentPath != null
            ? classifySentinel(sentinelPath.getLeaf(), parentPath)
            : Classification.of(SentinelContext.SIMPLE_NAME);

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
      final var parsed =
          ParsedSentinel.valid(
              injected, SentinelContext.MODULE_DIRECTIVE, receiverEndOffset, version);
      logValid(parsed);
      return parsed;
    }

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
            cls.annotationTypeText(),
            cls.typeReferenceRole(),
            enclosedByLoop,
            enclosedBySwitchStatement,
            enclosedBySwitchExpression,
            inEqualityComparison,
            cls.inExpression(),
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
      String annotationTypeText,
      TypeReferenceRole typeReferenceRole,
      boolean inExpression) {

    static Classification of(final SentinelContext ctx) {
      return new Classification(
          ctx, -1, null, null, -1, null, null, TypeReferenceRole.ORDINARY, false);
    }

    static Classification typeReference(final TypeReferenceRole role) {
      return new Classification(
          SentinelContext.TYPE_REFERENCE, -1, null, null, -1, null, null, role, false);
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
          TypeReferenceRole.ORDINARY,
          true);
    }
  }

  private static Classification classifySentinel(final Tree sentinel, final TreePath parentPath) {
    if (sentinel instanceof VariableTree v) {
      return classifyVariableDeclaration(v);
    }

    final Tree parent = parentPath.getLeaf();
    final boolean simpleName = !(sentinel instanceof MemberSelectTree);
    final AnnotationValueInfo annotationValue = annotationArgumentValue(sentinel, parentPath);
    if (annotationValue != null) {
      return Classification.annotationArgumentValue(
          annotationValue.annotationType(), annotationValue.elementName());
    }

    final String annotationArgumentType = annotationArgumentType(sentinel, parentPath);
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

    return switch (parent) {
      // --- statement-level expression positions ---
      case ReturnTree r when simpleName && r.getExpression() == sentinel ->
          Classification.expression();
      case ThrowTree t when simpleName && t.getExpression() == sentinel ->
          Classification.expression();
      case VariableTree v when simpleName && v.getInitializer() == sentinel ->
          Classification.expression();
      // --- expression-tree parents: sentinel is a sub-expression ---
      case ConditionalExpressionTree ignored -> Classification.expression();
      case BinaryTree ignored -> Classification.expression();
      case UnaryTree ignored -> Classification.expression();
      case ParenthesizedTree ignored -> Classification.expression();
      case InstanceOfTree i when i.getExpression() == sentinel -> Classification.expression();
      case TypeCastTree t when t.getExpression() == sentinel -> Classification.expression();
      case AssignmentTree a when a.getExpression() == sentinel -> Classification.expression();
      case CompoundAssignmentTree a when a.getExpression() == sentinel ->
          Classification.expression();
      case ArrayAccessTree a when a.getIndex() == sentinel -> Classification.expression();
      case NewArrayTree n when n.getDimensions().stream().anyMatch(d -> d == sentinel) ->
          Classification.expression();
      case MemberSelectTree m when m.getExpression() == sentinel && isClassLiteral(m) ->
          Classification.typeReference(inferredRole);
      case MemberSelectTree m when m.getExpression() == sentinel && !simpleName ->
          Classification.of(SentinelContext.MEMBER_ACCESS);
      case MemberSelectTree m when m.getExpression() == sentinel -> Classification.expression();
      // --- control-flow condition / selector positions ---
      case IfTree i when i.getCondition() == sentinel -> Classification.expression();
      case WhileLoopTree w when w.getCondition() == sentinel -> Classification.expression();
      case DoWhileLoopTree d when d.getCondition() == sentinel -> Classification.expression();
      case ForLoopTree f when f.getCondition() == sentinel -> Classification.expression();
      case EnhancedForLoopTree e when e.getExpression() == sentinel -> Classification.expression();
      case SynchronizedTree s when s.getExpression() == sentinel -> Classification.expression();
      case SwitchTree s when s.getExpression() == sentinel -> Classification.expression();
      case SwitchExpressionTree s when s.getExpression() == sentinel -> Classification.expression();
      // --- method-call / constructor / lambda / annotation: existing paths ---
      case MethodInvocationTree m
          when simpleName && m.getArguments().stream().anyMatch(a -> a == sentinel) ->
          classifyMethodInvocation(sentinel, m);
      case LambdaExpressionTree lambda -> classifyLambda(sentinel, lambda);
      case NewClassTree m when simpleName -> classifyConstructorCall(sentinel, m);
      case AnnotationTree ignored -> Classification.annotation();
      // --- type-reference positions ---
      case VariableTree v when v.getType() == sentinel ->
          Classification.typeReference(inferredRole);
      case MethodTree m when m.getReturnType() == sentinel ->
          Classification.typeReference(inferredRole);
      case TypeCastTree t when t.getType() == sentinel ->
          Classification.typeReference(inferredRole);
      case ParameterizedTypeTree ignored -> Classification.typeReference(inferredRole);
      case ClassTree ignored -> Classification.typeReference(inferredRole);
      case ArrayTypeTree ignored -> Classification.typeReference(inferredRole);
      case WildcardTree ignored -> Classification.typeReference(inferredRole);
      case TypeParameterTree ignored -> Classification.typeReference(inferredRole);
      default -> classifyDefault(sentinel);
    };
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
        realType ? type.toString() : null,
        null,
        TypeReferenceRole.ORDINARY,
        false);
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
          TypeReferenceRole.ORDINARY,
          false);
    }

    final var params = lambda.getParameters();
    final String receiver = sel.getExpression().toString();
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
