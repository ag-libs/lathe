package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import io.github.aglibs.lathe.server.analysis.SourceCompiler;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import io.github.aglibs.lathe.server.analysis.SourceParser;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;

public final class CompletionEngine {

  private static final Logger LOG = Logger.getLogger(CompletionEngine.class.getName());

  private final SourceParser parser;

  public CompletionEngine(final SourceCompiler compiler, final SourceParser parser) {
    this.parser = parser;
  }

  public List<CompletionItem> complete(
      final String uri,
      final String content,
      final Position pos,
      final String cachedContent,
      final FileAnalysis cachedAnalysis) {

    final String sourceLine = lineAt(content, pos.getLine());
    final int cursorOffset = cursorOffset(content, pos);
    LOG.fine(
        () ->
            "[completion] %s line=%d col=%d offset=%d"
                .formatted(uri, pos.getLine(), pos.getCharacter(), cursorOffset));
    LOG.fine(() -> "[completion] source line |%s|".formatted(sourceLine));

    if (cachedContent == null) {
      LOG.fine(() -> "[completion] diff no cached source");
    } else if (content.equals(cachedContent)) {
      LOG.fine(() -> "[completion] diff none (identical)");
    } else {
      final int deltaLen = content.length() - cachedContent.length();
      final int firstDiff = firstDiffOffset(content, cachedContent);
      LOG.fine(
          () ->
              "[completion] diff Δlen=%+d first=%d cursor=%d"
                  .formatted(deltaLen, firstDiff, cursorOffset));

      if (cachedAnalysis != null && cachedAnalysis.tree() != null) {
        final var diffPath =
            SourceLocator.pathAt(cachedAnalysis.trees(), cachedAnalysis.tree(), firstDiff);
        final var encClass = enclosingClass(diffPath);
        final var encMethod = enclosingMethod(diffPath);
        LOG.fine(
            () ->
                "[completion] diff-ctx node=%s class=%s method=%s"
                    .formatted(
                        diffPath != null ? diffPath.getLeaf().getKind() : "null",
                        encClass != null ? encClass.getSimpleName() : "null",
                        encMethod != null ? encMethod.getName() : "null"));

        if (diffPath != null) {
          final var encElement = SourceLocator.elementAt(cachedAnalysis.trees(), diffPath);
          LOG.fine(
              () ->
                  "[completion] diff-ctx element=%s kind=%s"
                      .formatted(encElement, encElement != null ? encElement.getKind() : null));
        }
      }
    }

    final var diags = parser.tryParseContent(uri, content);
    LOG.fine(() -> "[completion] parse diags=%d".formatted(diags.size()));
    for (final var d : diags) {
      LOG.fine(
          () ->
              "[completion] diag [%s] pos=%d code=%s %s"
                  .formatted(
                      d.getKind(), d.getPosition(), d.getCode(), d.getMessage(Locale.ENGLISH)));
    }

    if (content.equals(cachedContent) && cachedAnalysis != null && cachedAnalysis.tree() != null) {
      final var cu = cachedAnalysis.tree();
      final long offset = SourceLocator.toOffset(cu, pos.getLine(), pos.getCharacter());
      final var path = SourceLocator.pathAt(cachedAnalysis.trees(), cu, offset);
      LOG.fine(
          () ->
              "[completion] no-diff offset=%d node=%s"
                  .formatted(offset, path != null ? path.getLeaf().getKind() : "null"));

      if (path != null) {
        final var type = cachedAnalysis.trees().getTypeMirror(path);
        final var element = SourceLocator.elementAt(cachedAnalysis.trees(), path);
        LOG.fine(
            () ->
                "[completion] type %s kind=%s"
                    .formatted(type, type != null ? type.getKind() : null));
        LOG.fine(
            () ->
                "[completion] element %s kind=%s"
                    .formatted(element, element != null ? element.getKind() : null));
      }
    }

    return List.of();
  }

  private static ClassTree enclosingClass(final TreePath path) {
    for (var p = path; p != null; p = p.getParentPath()) {
      if (p.getLeaf() instanceof ClassTree ct) {
        return ct;
      }
    }

    return null;
  }

  private static MethodTree enclosingMethod(final TreePath path) {
    for (var p = path; p != null; p = p.getParentPath()) {
      if (p.getLeaf() instanceof MethodTree mt) {
        return mt;
      }
    }

    return null;
  }

  private static int firstDiffOffset(final String a, final String b) {
    final int limit = Math.min(a.length(), b.length());
    for (int i = 0; i < limit; i++) {
      if (a.charAt(i) != b.charAt(i)) {
        return i;
      }
    }

    return limit;
  }

  private static String lineAt(final String content, final int line) {
    return content.lines().skip(line).findFirst().orElse("");
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
