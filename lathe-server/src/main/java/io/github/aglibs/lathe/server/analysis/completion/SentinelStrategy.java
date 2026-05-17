package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import io.github.aglibs.lathe.server.analysis.SourceCompiler;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;

final class SentinelStrategy implements CompletionStrategy {

  static final String SENTINEL = "__LATHE_SENTINEL__";
  private static final Logger LOG = Logger.getLogger(SentinelStrategy.class.getName());

  private final SourceCompiler compiler;

  SentinelStrategy(final SourceCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public CompletionResult attempt(final CompletionRequest request) {
    return resolveWithSentinel(compiler, request.uri(), request.source(), request.offset());
  }

  static CompletionResult resolveWithSentinel(
      final SourceCompiler compiler, final String uri, final String source, final int offset) {
    final var injected = source.substring(0, offset) + SENTINEL + "()" + source.substring(offset);
    final var analysis = compiler.compile(uri, injected, CompileMode.FAST).fileAnalysis();
    return resolveFromAnalysis(analysis);
  }

  static CompletionResult resolveFromAnalysis(final FileAnalysis analysis) {
    if (analysis == null || analysis.tree() == null) {
      return new CompletionResult.Declined();
    }

    final var memberSelectPath = findSentinel(analysis);
    if (memberSelectPath == null) {
      LOG.fine(() -> "[completion] sentinel not found in AST");
      return new CompletionResult.Declined();
    }

    final var memberSelect = (MemberSelectTree) memberSelectPath.getLeaf();
    final var receiverPath = new TreePath(memberSelectPath, memberSelect.getExpression());
    final var receiverType = analysis.trees().getTypeMirror(receiverPath);

    if (receiverType == null || receiverType.getKind() == TypeKind.ERROR) {
      LOG.fine(() -> "[completion] receiver not attributed: %s".formatted(receiverType));
      return new CompletionResult.Declined();
    }

    if (!(receiverType instanceof DeclaredType declaredType)) {
      LOG.fine(
          () ->
              "[completion] receiver is not declared: %s %s"
                  .formatted(receiverType.getKind(), receiverType));
      return new CompletionResult.Declined();
    }

    return new CompletionResult.Found(MemberResolver.membersOf(declaredType, analysis));
  }

  private static TreePath findSentinel(final FileAnalysis analysis) {
    final var result = new AtomicReference<TreePath>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitMemberSelect(final MemberSelectTree node, final Void unused) {
        if (node.getIdentifier().toString().startsWith(SENTINEL)) {
          result.set(getCurrentPath());
        }

        return super.visitMemberSelect(node, unused);
      }
    }.scan(analysis.tree(), null);
    return result.get();
  }
}
