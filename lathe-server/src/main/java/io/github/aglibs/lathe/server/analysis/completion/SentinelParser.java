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

    final long startPos = trees.getSourcePositions().getStartPosition(cu, sentinelPath.getLeaf());
    final long javacLine = cu.getLineMap().getLineNumber(startPos);
    if (javacLine - 1 != expectedLspLine) {
      LOG.fine(
          () ->
              "[sentinel-parse] sentinel moved: expected lsp-line=%d javac-line=%d"
                  .formatted(expectedLspLine, javacLine));
      return ParsedSentinel.invalid(injected.prefix(), injected.receiverText(), version);
    }

    final var parentPath = sentinelPath.getParentPath();
    final SentinelContext ctx =
        parentPath != null
            ? classifySentinel(sentinelPath.getLeaf(), parentPath.getLeaf())
            : SentinelContext.SIMPLE_NAME;

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
      final var parsed =
          new ParsedSentinel(
              true,
              injected.prefix(),
              injected.receiverText(),
              SentinelContext.MODULE_DIRECTIVE,
              null,
              null,
              version);
      LOG.fine(() -> "[sentinel-parse] %s".formatted(parsed));
      return parsed;
    }

    final var parsed =
        new ParsedSentinel(
            true,
            injected.prefix(),
            injected.receiverText(),
            ctx,
            enclosingClass,
            enclosingMethod,
            version);

    LOG.fine(() -> "[sentinel-parse] %s".formatted(parsed));
    return parsed;
  }

  private static SentinelContext classifySentinel(final Tree sentinel, final Tree parent) {
    return switch (parent) {
      case MethodInvocationTree m when m.getArguments().stream().anyMatch(a -> a == sentinel) ->
          SentinelContext.ARGUMENT_POSITION;
      case NewClassTree ignored -> SentinelContext.CONSTRUCTOR_CALL;
      case AnnotationTree ignored -> SentinelContext.ANNOTATION_CONTEXT;
      case LambdaExpressionTree ignored -> SentinelContext.LAMBDA_BODY;
      case VariableTree v when v.getType() == sentinel -> SentinelContext.TYPE_REFERENCE;
      case MethodTree m when m.getReturnType() == sentinel -> SentinelContext.TYPE_REFERENCE;
      case ParameterizedTypeTree ignored -> SentinelContext.TYPE_REFERENCE;
      case ClassTree ignored -> SentinelContext.TYPE_REFERENCE;
      case ArrayTypeTree ignored -> SentinelContext.TYPE_REFERENCE;
      case WildcardTree ignored -> SentinelContext.TYPE_REFERENCE;
      case TypeCastTree t when t.getType() == sentinel -> SentinelContext.TYPE_REFERENCE;
      default ->
          sentinel instanceof MemberSelectTree
              ? SentinelContext.MEMBER_ACCESS
              : SentinelContext.SIMPLE_NAME;
    };
  }

  private static final class SentinelFinder extends TreePathScanner<TreePath, Void> {

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
