package io.github.aglibs.lathe.server;

import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.core.Stopwatch;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaFileObject;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

final class ModuleAnalysis {

  private static final Logger LOG = Logger.getLogger(ModuleAnalysis.class.getName());

  private final ModuleCompiler compiler;
  private final Map<String, CompilationTaskContext> cache = new ConcurrentHashMap<>();

  ModuleAnalysis(final ModuleCompiler compiler) {
    this.compiler = compiler;
  }

  void clearCache() {
    cache.clear();
  }

  List<Diagnostic> compile(final String uri, final String content, final CompileMode mode)
      throws IOException {
    final var t = Stopwatch.start();
    final var run = compiler.compile(uri, content, mode);
    cache.put(uri, run.context());
    final var diags = filterAndMap(run.diagnostics(), content);
    LOG.fine(() -> "[%s] %s %dms diags=%d".formatted(mode.tag, uri, t.elapsedMs(), diags.size()));
    return diags;
  }

  void dropFromCache(final String uri) {
    cache.remove(uri);
  }

  List<SemanticToken> semanticTokens(final String uri) {
    final var ctx = cache.get(uri);
    return ctx != null ? ctx.semanticTokens() : null;
  }

  Hover hover(final String uri, final Position pos, final List<Path> sourceRoots) {
    final var t = Stopwatch.start();
    final CursorContext cur = resolve(uri, pos);
    if (cur == null) {
      return null;
    }

    final VariableElement param = SourceLocator.parameterElementAt(cur.ctx().trees(), cur.path());
    if (param != null) {
      LOG.fine(() -> "[hover] param=%s %dms".formatted(param, t.elapsedMs()));
      return new Hover(new MarkupContent("markdown", HoverFormatter.formatParameter(param)));
    }

    final Element element = SourceLocator.elementAt(cur.ctx().trees(), cur.path());
    final TypeMirror type = cur.path() != null ? cur.ctx().trees().getTypeMirror(cur.path()) : null;
    final var javadoc = JavadocLocator.locate(element, cur.ctx().trees(), sourceRoots).orElse(null);
    LOG.fine(
        () ->
            "[hover] %dms element=%s type=%s doc=%s"
                .formatted(t.elapsedMs(), element, type, javadoc != null));
    return HoverFormatter.format(element, type, javadoc)
        .map(md -> new Hover(new MarkupContent("markdown", md)))
        .orElse(null);
  }

  Optional<Location> definition(
      final String uri, final Position pos, final List<Path> sourceRoots) {
    final var t = Stopwatch.start();
    final var cur = resolve(uri, pos);
    if (cur == null) {
      return Optional.empty();
    }

    final var element = SourceLocator.elementAt(cur.ctx().trees(), cur.path());
    final var result = DefinitionLocator.locate(element, cur.ctx().trees(), sourceRoots, uri);
    LOG.fine(
        () ->
            "[definition] %dms element=%s → %s"
                .formatted(
                    t.elapsedMs(),
                    element,
                    result
                        .map(l -> "%s:%d".formatted(l.getUri(), l.getRange().getStart().getLine()))
                        .orElse("not found")));
    return result;
  }

  private record CursorContext(CompilationTaskContext ctx, TreePath path) {}

  private CursorContext resolve(final String uri, final Position pos) {
    final var ctx = cache.get(uri);
    if (ctx == null || ctx.tree() == null) {
      return null;
    }

    final long offset = SourceLocator.toOffset(ctx.tree(), pos.getLine(), pos.getCharacter());
    return new CursorContext(ctx, SourceLocator.pathAt(ctx.trees(), ctx.tree(), offset));
  }

  static List<Diagnostic> filterAndMap(
      final List<? extends javax.tools.Diagnostic<? extends JavaFileObject>> raw,
      final String content) {
    return raw.stream()
        .filter(
            d ->
                d.getKind() != javax.tools.Diagnostic.Kind.NOTE
                    || d.getPosition() != javax.tools.Diagnostic.NOPOS)
        .map(d -> toLsp(d, content))
        .toList();
  }

  static Diagnostic toLsp(
      final javax.tools.Diagnostic<? extends JavaFileObject> d, final String content) {
    final var severity =
        switch (d.getKind()) {
          case ERROR -> DiagnosticSeverity.Error;
          case WARNING, MANDATORY_WARNING -> DiagnosticSeverity.Warning;
          case NOTE -> DiagnosticSeverity.Hint;
          default -> DiagnosticSeverity.Information;
        };

    final long startOffset = d.getStartPosition();
    final long endOffset = d.getEndPosition();

    final Position start;
    final Position end;
    if (startOffset != javax.tools.Diagnostic.NOPOS) {
      start = SourceLocator.offsetToPosition(content, startOffset);
      end =
          (endOffset != javax.tools.Diagnostic.NOPOS && endOffset > startOffset)
              ? SourceLocator.offsetToPosition(content, endOffset)
              : new Position(start.getLine(), start.getCharacter() + 1);
    } else {
      final long rawLine = d.getLineNumber();
      final long rawCol = d.getColumnNumber();
      final int line = rawLine == javax.tools.Diagnostic.NOPOS ? 0 : (int) rawLine - 1;
      final int col = rawCol == javax.tools.Diagnostic.NOPOS ? 0 : (int) rawCol - 1;
      start = new Position(line, col);
      end = new Position(line, col + 1);
    }

    return new Diagnostic(new Range(start, end), d.getMessage(Locale.ENGLISH), severity, "lathe");
  }
}
