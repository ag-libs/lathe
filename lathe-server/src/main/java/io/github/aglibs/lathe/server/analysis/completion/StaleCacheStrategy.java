package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

final class StaleCacheStrategy implements CompletionStrategy {

  @Override
  public CompletionResult attempt(final CompletionRequest request) {
    if (request.cached() == null
        || request.cachedSource() == null
        || request.cached().tree() == null) {
      return new CompletionResult.Declined();
    }

    final int staleOffset = staleOffset(request);
    if (staleOffset < 0) {
      return new CompletionResult.Declined();
    }

    final boolean findMemberSelect = request.source().equals(request.cachedSource());
    final var receiverType = resolveReceiverType(request.cached(), staleOffset, findMemberSelect);
    if (receiverType == null || receiverType.getKind() == TypeKind.ERROR) {
      return new CompletionResult.Declined();
    }

    if (!(receiverType instanceof DeclaredType declaredType)) {
      return new CompletionResult.Declined();
    }

    return new CompletionResult.Found(MemberResolver.membersOf(declaredType, request.cached()));
  }

  private static int staleOffset(final CompletionRequest request) {
    final int offset = request.offset();
    if (request.source().equals(request.cachedSource())) {
      return offset >= 1 ? offset - 1 : -1;
    }

    if (isDotOnlyInsertion(request.source(), request.cachedSource(), offset)) {
      return offset >= 2 ? offset - 2 : -1;
    }

    return -1;
  }

  private static TypeMirror resolveReceiverType(
      final FileAnalysis analysis, final int offset, final boolean findMemberSelect) {
    var path = SourceLocator.pathAt(analysis.trees(), analysis.tree(), offset);
    if (path == null) {
      return null;
    }

    if (findMemberSelect) {
      while (path != null && !(path.getLeaf() instanceof MemberSelectTree)) {
        path = path.getParentPath();
      }

      if (path == null) {
        return null;
      }

      final var memberSelect = (MemberSelectTree) path.getLeaf();
      final var receiverPath = new TreePath(path, memberSelect.getExpression());
      return analysis.trees().getTypeMirror(receiverPath);
    }

    return analysis.trees().getTypeMirror(path);
  }

  private static boolean isDotOnlyInsertion(
      final String content, final String cachedContent, final int cursorOffset) {
    return cursorOffset >= 1
        && content.charAt(cursorOffset - 1) == '.'
        && content.length() == cachedContent.length() + 1
        && content.regionMatches(0, cachedContent, 0, cursorOffset - 1)
        && content.regionMatches(
            cursorOffset,
            cachedContent,
            cursorOffset - 1,
            cachedContent.length() - cursorOffset + 1);
  }
}
