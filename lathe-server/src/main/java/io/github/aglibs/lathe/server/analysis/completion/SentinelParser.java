package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.server.analysis.SourceParser;
import java.util.logging.Logger;

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

    final long startPos = trees.getSourcePositions().getEndPosition(cu, sentinelPath.getLeaf());
    final long javacLine = cu.getLineMap().getLineNumber(startPos);
    if (javacLine - 1 != expectedLspLine) {
      LOG.fine(
          () ->
              "[sentinel-parse] sentinel moved: expected lsp-line=%d javac-line=%d"
                  .formatted(expectedLspLine, javacLine));
      return ParsedSentinel.invalid(injected.prefix(), injected.receiverText(), version);
    }

    final var importTree = enclosingImport(sentinelPath);
    if (importTree != null) {
      final var parsed =
          ParsedSentinel.valid(
              injected,
              importTree.isStatic() ? SentinelContext.STATIC_IMPORT : SentinelContext.IMPORT,
              version);
      LOG.fine(() -> "[sentinel-parse] %s".formatted(parsed));
      return parsed;
    }

    final var parentPath = sentinelPath.getParentPath();
    final Classification cls =
        parentPath != null
            ? classifySentinel(sentinelPath.getLeaf(), parentPath.getLeaf())
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

    if (enclosingClass == null && cu.getModule() != null) {
      final var parsed = ParsedSentinel.valid(injected, SentinelContext.MODULE_DIRECTIVE, version);
      LOG.fine(() -> "[sentinel-parse] %s".formatted(parsed));
      return parsed;
    }

    final var parsed =
        ParsedSentinel.valid(
            injected,
            cls.context(),
            enclosingClass,
            enclosingMethod,
            cls.argIndex(),
            cls.enclosingReceiver(),
            cls.enclosingMethodName(),
            cls.lambdaParamIndex(),
            cls.declaredTypeText(),
            version);

    LOG.fine(() -> "[sentinel-parse] %s".formatted(parsed));
    return parsed;
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
      String declaredTypeText) {

    static Classification of(final SentinelContext ctx) {
      return new Classification(ctx, -1, null, null, -1, null);
    }
  }

  private static Classification classifySentinel(final Tree sentinel, final Tree parent) {
    if (sentinel instanceof VariableTree v) {
      return classifyVariableDeclaration(v);
    }
    return switch (parent) {
      case MethodInvocationTree m
          when !(sentinel instanceof MemberSelectTree)
              && m.getArguments().stream().anyMatch(a -> a == sentinel) ->
          classifyMethodInvocation(sentinel, m);
      case LambdaExpressionTree lambda -> classifyLambda(sentinel, lambda);
      case NewClassTree ignored when !(sentinel instanceof MemberSelectTree) ->
          Classification.of(SentinelContext.CONSTRUCTOR_CALL);
      case AnnotationTree ignored -> Classification.of(SentinelContext.ANNOTATION_CONTEXT);
      case VariableTree v when v.getType() == sentinel ->
          Classification.of(SentinelContext.TYPE_REFERENCE);
      case MethodTree m when m.getReturnType() == sentinel ->
          Classification.of(SentinelContext.TYPE_REFERENCE);
      case ParameterizedTypeTree ignored -> Classification.of(SentinelContext.TYPE_REFERENCE);
      case ClassTree ignored -> Classification.of(SentinelContext.TYPE_REFERENCE);
      case ArrayTypeTree ignored -> Classification.of(SentinelContext.TYPE_REFERENCE);
      case WildcardTree ignored -> Classification.of(SentinelContext.TYPE_REFERENCE);
      case TypeParameterTree ignored -> Classification.of(SentinelContext.TYPE_REFERENCE);
      case TypeCastTree t when t.getType() == sentinel ->
          Classification.of(SentinelContext.TYPE_REFERENCE);
      default -> classifyDefault(sentinel);
    };
  }

  private static Classification classifyVariableDeclaration(final VariableTree v) {
    final var type = v.getType();
    return new Classification(
        SentinelContext.VARIABLE_DECLARATION,
        -1,
        null,
        null,
        -1,
        type != null ? type.toString() : null);
  }

  private static Classification classifyMethodInvocation(
      final Tree sentinel, final MethodInvocationTree m) {
    final var args = m.getArguments();
    int argIndex = 0;
    for (int j = 0; j < args.size(); j++) {
      if (args.get(j) == sentinel) {
        argIndex = j;
        break;
      }
    }
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
        null);
  }

  private static Classification classifyLambda(
      final Tree sentinel, final LambdaExpressionTree lambda) {
    int lambdaParamIndex = -1;
    if (sentinel instanceof MemberSelectTree sel) {
      final String receiver = sel.getExpression().toString();
      final var params = lambda.getParameters();
      for (int j = 0; j < params.size(); j++) {
        if (params.get(j).getName().toString().equals(receiver)) {
          lambdaParamIndex = j;
          break;
        }
      }
    }
    return new Classification(SentinelContext.LAMBDA_BODY, -1, null, null, lambdaParamIndex, null);
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
