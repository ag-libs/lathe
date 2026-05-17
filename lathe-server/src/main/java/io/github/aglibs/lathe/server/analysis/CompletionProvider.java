package io.github.aglibs.lathe.server.analysis;

import static java.util.logging.Level.SEVERE;

import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.core.Stopwatch;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;

final class CompletionProvider {

  private static final Logger LOG = Logger.getLogger(CompletionProvider.class.getName());
  private static final String SENTINEL = "__LATHE_SENTINEL__";

  private final SourceCompiler compiler;

  CompletionProvider(final SourceCompiler compiler) {
    this.compiler = compiler;
  }

  List<CompletionItem> complete(
      final String uri, final String content, final Position pos, final FileAnalysis cached) {
    final int offset = cursorOffset(content, pos);
    LOG.fine(
        () -> "[completion] %d:%d offset=%d".formatted(pos.getLine(), pos.getCharacter(), offset));

    if (cached != null) {
      final var stale = tryFromStale(cached, content, offset);
      if (stale != null) {
        LOG.fine(() -> "[completion] stale hit items=%d".formatted(stale.size()));
        return stale;
      }
    }

    LOG.fine(() -> "[completion] stale miss, compiling");
    final var injected = content.substring(0, offset) + SENTINEL + content.substring(offset);
    final var t = Stopwatch.start();
    final FileAnalysis analysis;
    try {
      analysis = compiler.compile(uri, injected, CompileMode.FAST).fileAnalysis();
    } catch (final IOException e) {
      LOG.log(SEVERE, e, () -> "[completion] compile failed for " + uri);
      return List.of();
    }

    LOG.fine(() -> "[completion] compiled %dms".formatted(t.elapsedMs()));
    return membersFromSentinel(analysis);
  }

  private List<CompletionItem> tryFromStale(
      final FileAnalysis cached, final String content, final int cursorOffset) {
    final int staleOffset;
    if (content.equals(cached.content())) {
      if (cursorOffset < 1) {
        return null;
      }

      staleOffset = cursorOffset - 1;
    } else if (isDotOnlyInsertion(content, cached.content(), cursorOffset)) {
      if (cursorOffset < 2) {
        return null;
      }

      staleOffset = cursorOffset - 2;
    } else {
      return null;
    }

    if (cached.tree() == null) {
      return null;
    }

    final var receiverType =
        resolveReceiverType(cached, staleOffset, content.equals(cached.content()));
    if (receiverType == null || receiverType.getKind() == TypeKind.ERROR) {
      return null;
    }

    if (!(receiverType instanceof DeclaredType declaredType)) {
      return null;
    }

    return membersOf(declaredType, cached);
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

  private List<CompletionItem> membersFromSentinel(final FileAnalysis analysis) {
    if (analysis == null || analysis.tree() == null) {
      LOG.fine(() -> "[completion] no tree");
      return List.of();
    }

    final var memberSelectPath = findSentinel(analysis);
    if (memberSelectPath == null) {
      LOG.fine(() -> "[completion] sentinel not found in AST");
      return List.of();
    }

    final var memberSelect = (MemberSelectTree) memberSelectPath.getLeaf();
    final var receiverPath = new TreePath(memberSelectPath, memberSelect.getExpression());
    final TypeMirror receiverType = analysis.trees().getTypeMirror(receiverPath);

    if (receiverType == null || receiverType.getKind() == TypeKind.ERROR) {
      LOG.fine(() -> "[completion] receiver not attributed: %s".formatted(receiverType));
      return List.of();
    }

    if (!(receiverType instanceof DeclaredType declaredType)) {
      LOG.fine(
          () ->
              "[completion] receiver is not a declared type: %s %s"
                  .formatted(receiverType.getKind(), receiverType));
      return List.of();
    }

    final var typeElement = (TypeElement) declaredType.asElement();
    LOG.fine(() -> "[completion] receiver=%s".formatted(typeElement.getQualifiedName()));
    final var items = membersOf(declaredType, analysis);
    LOG.fine(() -> "[completion] %d members".formatted(items.size()));
    return items;
  }

  private List<CompletionItem> membersOf(
      final DeclaredType declaredType, final FileAnalysis analysis) {
    final var typeElement = (TypeElement) declaredType.asElement();
    return analysis.elements().getAllMembers(typeElement).stream()
        .filter(m -> m.getKind() != ElementKind.CONSTRUCTOR)
        .map(m -> toItem(m, declaredType, analysis))
        .toList();
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

  private static CompletionItem toItem(
      final Element member, final DeclaredType receiverType, final FileAnalysis analysis) {
    final var item = new CompletionItem(member.getSimpleName().toString());
    item.setKind(completionKind(member.getKind()));
    item.setDetail(memberDetail(member, receiverType, analysis));
    return item;
  }

  private static String memberDetail(
      final Element member, final DeclaredType receiverType, final FileAnalysis analysis) {
    try {
      final TypeMirror memberType = analysis.types().asMemberOf(receiverType, member);
      if (memberType instanceof ExecutableType execType) {
        return execType.getReturnType().toString();
      }

      return memberType.toString();
    } catch (final IllegalArgumentException e) {
      return member.asType().toString();
    }
  }

  private static CompletionItemKind completionKind(final ElementKind kind) {
    return switch (kind) {
      case METHOD -> CompletionItemKind.Method;
      case FIELD -> CompletionItemKind.Field;
      case CLASS, RECORD, INTERFACE -> CompletionItemKind.Class;
      case ENUM -> CompletionItemKind.Enum;
      case ENUM_CONSTANT -> CompletionItemKind.EnumMember;
      default -> CompletionItemKind.Text;
    };
  }

  private static int cursorOffset(final String content, final Position pos) {
    int offset = 0;
    int line = 0;
    for (int i = 0; i < content.length() && line < pos.getLine(); i++) {
      if (content.charAt(i) == '\n') {
        line++;
        offset = i + 1;
      }
    }

    return offset + pos.getCharacter();
  }
}
