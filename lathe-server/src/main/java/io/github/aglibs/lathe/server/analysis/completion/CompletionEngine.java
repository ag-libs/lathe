package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.server.analysis.SourceCompiler;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import io.github.aglibs.lathe.server.analysis.SourceParser;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionTriggerKind;

public final class CompletionEngine {

  private static final Logger LOG = Logger.getLogger(CompletionEngine.class.getName());

  private final SourceParser parser;

  public CompletionEngine(final SourceCompiler compiler, final SourceParser parser) {
    this.parser = parser;
  }

  public List<CompletionItem> complete(final CompletionRequest req) {
    final var cached = req.cached();

    LOG.fine(
        () ->
            "[completion] %s line=%d col=%d offset=%d"
                .formatted(
                    req.uri(), req.pos().getLine(), req.pos().getCharacter(), req.cursorOffset()));
    LOG.fine(() -> "[completion] source line |%s|".formatted(req.sourceLine()));
    LOG.fine(
        () ->
            "[completion] prefix=|%s| charBefore='%s'"
                .formatted(req.prefix(), escapedChar(req.charBeforePrefix())));
    LOG.fine(() -> "[completion] context: %s kind=%s".formatted(req.context(), contextKind(req)));

    if (cached == null) {
      LOG.fine(() -> "[completion] diff no cached source");
    } else if (req.noDiff()) {
      LOG.fine(() -> "[completion] diff none (identical)");
    } else {
      final int deltaLen = req.content().length() - cached.content().length();
      final int firstDiff = req.firstDiff();
      LOG.fine(
          () ->
              "[completion] diff Δlen=%+d first=%d cursor=%d"
                  .formatted(deltaLen, firstDiff, req.cursorOffset()));

      if (cached.analysis() != null && cached.analysis().tree() != null) {
        final var diffPath =
            SourceLocator.pathAt(cached.analysis().trees(), cached.analysis().tree(), firstDiff);
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
          final var encElement = SourceLocator.elementAt(cached.analysis().trees(), diffPath);
          LOG.fine(
              () ->
                  "[completion] diff-ctx element=%s kind=%s"
                      .formatted(encElement, encElement != null ? encElement.getKind() : null));
        }
      }
    }

    final var diags = parser.tryParseContent(req.uri(), req.content());
    LOG.fine(() -> "[completion] parse diags=%d".formatted(diags.size()));
    final int co = req.cursorOffset();
    for (final var d : diags) {
      final long dp = d.getPosition();
      final String tag = dp == co ? "AT" : dp == co - 1 ? "BEFORE" : "pos=" + dp;
      LOG.fine(
          () ->
              "[completion] diag [%s] %s code=%s %s"
                  .formatted(d.getKind(), tag, d.getCode(), d.getMessage(Locale.ENGLISH)));
    }

    if (req.noDiff()
        && cached != null
        && cached.analysis() != null
        && cached.analysis().tree() != null) {
      final var cu = cached.analysis().tree();
      final long offset = SourceLocator.toOffset(cu, req.pos().getLine(), req.pos().getCharacter());
      final var path = SourceLocator.pathAt(cached.analysis().trees(), cu, offset);
      LOG.fine(
          () ->
              "[completion] no-diff offset=%d node=%s"
                  .formatted(offset, path != null ? path.getLeaf().getKind() : "null"));

      if (path != null) {
        final var type = cached.analysis().trees().getTypeMirror(path);
        final var element = SourceLocator.elementAt(cached.analysis().trees(), path);
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

  private static String contextKind(final CompletionRequest req) {
    final var ctx = req.context();
    if (ctx != null && ctx.getTriggerKind() == CompletionTriggerKind.TriggerCharacter) {
      final var tc = ctx.getTriggerCharacter();
      if (".".equals(tc)) {
        return "MEMBER_DOT";
      }
      if ("@".equals(tc)) {
        return "ANNOTATION";
      }
      if (" ".equals(tc)) {
        return "KEYWORD_SPACE";
      }
      if ("#".equals(tc)) {
        return "JAVADOC_MEMBER";
      }
      return "TRIGGER(" + tc + ")";
    }

    final char cbc = req.charBeforePrefix();
    if (cbc == '.') {
      return "MEMBER_DOT";
    }
    if (cbc == '@') {
      return "ANNOTATION";
    }

    final String prefix = req.prefix();
    final String line = req.sourceLine().stripLeading();
    if (line.startsWith("import ") || line.startsWith("import\t")) {
      return "IMPORT_PATH";
    }
    if (line.startsWith("requires ") || line.startsWith("exports ") || line.startsWith("opens ")) {
      return "MODULE_DIRECTIVE";
    }
    if (!prefix.isEmpty()) {
      return "STANDALONE_IDENT";
    }
    return "UNKNOWN";
  }

  private static String escapedChar(final char c) {
    return switch (c) {
      case '\0' -> "\\0";
      case '\n' -> "\\n";
      case '\t' -> "\\t";
      case ' ' -> "SPC";
      default -> String.valueOf(c);
    };
  }
}
