package io.github.aglibs.lathe.server.analysis;

import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.DefinitionLocator;
import io.github.aglibs.lathe.server.HoverFormatter;
import io.github.aglibs.lathe.server.JavadocLocator;
import io.github.aglibs.lathe.server.SourceLocator;
import io.github.aglibs.lathe.server.SourceParser;
import io.github.aglibs.lathe.server.module.CompileMode;
import io.github.aglibs.lathe.server.module.SourceCompiler;
import io.github.aglibs.lathe.server.tokens.SemanticToken;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaFileObject;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public final class AnalysisEngine {

  private static final Logger LOG = Logger.getLogger(AnalysisEngine.class.getName());

  private final SourceCompiler compiler;
  private final CompletionProvider completionProvider;
  private final DefinitionLocator definitionLocator;
  private final JavadocLocator javadocLocator;
  private final Map<String, FileAnalysis> cache = new HashMap<>();

  public AnalysisEngine(final SourceCompiler compiler) {
    this.compiler = compiler;
    this.completionProvider = new CompletionProvider(compiler);
    final var parsingFm = compiler.compiler().getStandardFileManager(null, null, null);
    final var parser = new SourceParser(compiler.compiler(), parsingFm);
    this.definitionLocator = new DefinitionLocator(parser);
    this.javadocLocator = new JavadocLocator(parser);
  }

  public void clearCache() {
    cache.clear();
  }

  public List<Diagnostic> compile(final String uri, final String content, final CompileMode mode)
      throws IOException {
    final var t = Stopwatch.start();
    final var run = compiler.compile(uri, content, mode);
    cache.put(uri, run.fileAnalysis());
    final var diags = filterAndMap(run.diagnostics(), content);
    LOG.fine(() -> "[%s] %s %dms diags=%d".formatted(mode.tag, uri, t.elapsedMs(), diags.size()));
    return diags;
  }

  public List<CompletionItem> complete(final String uri, final String content, final Position pos) {
    final var t = Stopwatch.start();
    final var items = completionProvider.complete(uri, content, pos);
    LOG.fine(() -> "[completion] %s %dms items=%d".formatted(uri, t.elapsedMs(), items.size()));
    return items;
  }

  public void dropFromCache(final String uri) {
    cache.remove(uri);
  }

  public boolean isCached(final String uri) {
    return cache.containsKey(uri);
  }

  public List<SemanticToken> semanticTokens(final String uri) {
    final var ctx = cache.get(uri);
    return ctx != null ? ctx.semanticTokens() : null;
  }

  public Hover hover(
      final String uri,
      final Position pos,
      final List<Path> sourceRoots,
      final WorkspaceManifest manifest) {
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
    final var allRoots =
        Stream.concat(sourceRoots.stream(), manifest.externalSourceDirs().stream()).toList();
    final var javadoc = javadocLocator.locate(element, cur.ctx().trees(), allRoots).orElse(null);
    final var origin = manifest.originLabel(element, compiler.fileManager()).orElse(null);
    LOG.fine(
        () ->
            "[hover] %dms element=%s type=%s doc=%s origin=%s"
                .formatted(t.elapsedMs(), element, type, javadoc != null, origin));
    return HoverFormatter.format(element, type, javadoc, origin)
        .map(md -> new Hover(new MarkupContent("markdown", md)))
        .orElse(null);
  }

  public Optional<Location> definition(
      final String uri,
      final Position pos,
      final List<Path> sourceRoots,
      final WorkspaceManifest manifest) {
    final var t = Stopwatch.start();
    final var cur = resolve(uri, pos);
    if (cur == null) {
      return Optional.empty();
    }

    final var element = SourceLocator.elementAt(cur.ctx().trees(), cur.path());
    var result = definitionLocator.locate(element, cur.ctx().trees(), sourceRoots, uri);
    if (result.isEmpty()) {
      result =
          manifest
              .externalSourceRoot(element, compiler.fileManager())
              .flatMap(root -> DefinitionLocator.findSourceFile(element, List.of(root)))
              .map(
                  file -> {
                    final var lspPos = definitionLocator.parsePosition(file, element);
                    return new Location(file.toUri().toString(), new Range(lspPos, lspPos));
                  });
    }

    final var finalResult = result;
    LOG.fine(
        () ->
            "[definition] %dms element=%s → %s"
                .formatted(
                    t.elapsedMs(),
                    element,
                    finalResult
                        .map(l -> "%s:%d".formatted(l.getUri(), l.getRange().getStart().getLine()))
                        .orElse("not found")));
    return result;
  }

  private record CursorContext(FileAnalysis ctx, TreePath path) {}

  private CursorContext resolve(final String uri, final Position pos) {
    final var ctx = cache.get(uri);
    if (ctx == null || ctx.tree() == null) {
      return null;
    }

    final long offset = SourceLocator.toOffset(ctx.tree(), pos.getLine(), pos.getCharacter());
    return new CursorContext(ctx, SourceLocator.pathAt(ctx.trees(), ctx.tree(), offset));
  }

  public static List<Diagnostic> filterAndMap(
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

  public static Diagnostic toLsp(
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
