package io.github.aglibs.lathe.server.analysis;

import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.analysis.completion.CompletionEngine;
import io.github.aglibs.lathe.server.analysis.completion.CompletionRequest;
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
import org.eclipse.lsp4j.*;

public final class CompilationContext implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(CompilationContext.class.getName());

  private final SourceCompiler compiler;
  private final CompletionEngine completionEngine;
  private final DefinitionLocator definitionLocator;
  private final JavadocLocator javadocLocator;
  private final Map<String, CachedAnalysis> cache = new HashMap<>();
  private final SourceParser parser;

  public CompilationContext(final SourceCompiler compiler) {
    this.compiler = compiler;
    this.parser = new SourceParser();
    this.completionEngine = new CompletionEngine(parser, compiler);
    this.definitionLocator = new DefinitionLocator(parser);
    this.javadocLocator = new JavadocLocator(parser);
  }

  public List<Diagnostic> compile(
      final String uri, final String content, final int version, final CompileMode mode) {
    final var t = Stopwatch.start();
    final var run = compiler.compile(uri, content, mode);
    if (mode != CompileMode.FULL) {
      cache.put(uri, new CachedAnalysis(content, version, run.fileAnalysis()));
    }

    final var diags = filterAndMap(run.diagnostics(), content);
    LOG.info(
        () ->
            "[compile:%s] %s %dms diags=%d".formatted(mode.tag, uri, t.elapsedMs(), diags.size()));
    return diags;
  }

  public List<CompletionItem> complete(
      final String uri, final String content, final Position pos, final CompletionContext context) {
    final var t = Stopwatch.start();
    final var request = new CompletionRequest(uri, content, pos, context, cache.get(uri));
    final var outcome = completionEngine.complete(request);
    if (outcome.freshAnalysis() != null) {
      cache.put(
          uri, new CachedAnalysis(content, cache.get(uri).version(), outcome.freshAnalysis()));
    }

    LOG.fine(
        () ->
            "[completion] %s %dms items=%d reattributed=%s"
                .formatted(
                    uri, t.elapsedMs(), outcome.items().size(), outcome.freshAnalysis() != null));
    return outcome.items();
  }

  public void dropFromCache(final String uri) {
    cache.remove(uri);
  }

  public List<SemanticToken> semanticTokens(final String uri, final int expectedVersion) {
    final var ctx = cache.get(uri);
    if (ctx == null || ctx.version() != expectedVersion) {
      return null;
    }

    return ctx.analysis().semanticTokens();
  }

  public Hover hover(final FeatureRequest request) {
    final var t = Stopwatch.start();
    final CursorContext cur = resolve(request);
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
        Stream.concat(
                request.sourceRoots().stream(), request.manifest().externalSourceDirs().stream())
            .toList();
    final var javadoc = javadocLocator.locate(element, cur.ctx().trees(), allRoots).orElse(null);
    final var origin = request.manifest().originLabel(element, compiler.fileManager()).orElse(null);
    LOG.fine(
        () ->
            "[hover] %dms element=%s type=%s doc=%s origin=%s"
                .formatted(t.elapsedMs(), element, type, javadoc != null, origin));
    return HoverFormatter.format(element, type, javadoc, origin)
        .map(md -> new Hover(new MarkupContent("markdown", md)))
        .orElse(null);
  }

  public Optional<Location> definition(final FeatureRequest request) {
    final var t = Stopwatch.start();
    final var cur = resolve(request);
    if (cur == null) {
      return Optional.empty();
    }

    final var element = SourceLocator.elementAt(cur.ctx().trees(), cur.path());
    var result =
        definitionLocator.locate(element, cur.ctx().trees(), request.sourceRoots(), request.uri());
    if (result.isEmpty()) {
      result =
          request
              .manifest()
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

  public void close() {
    cache.clear();
    parser.close();
    compiler.close();
  }

  private record CursorContext(FileAnalysis ctx, TreePath path) {}

  private CachedAnalysis currentCache(final String uri, final String content) {
    final var cached = cache.get(uri);
    return cached != null && cached.content().equals(content) ? cached : null;
  }

  private CursorContext resolve(final FeatureRequest request) {
    final var cached = currentCache(request.uri(), request.content());
    final var analysis = cached != null ? cached.analysis() : null;
    if (analysis == null || analysis.tree() == null) {
      return null;
    }

    final long offset =
        SourceLocator.toOffset(
            analysis.tree(), request.pos().getLine(), request.pos().getCharacter());
    return new CursorContext(
        analysis, SourceLocator.pathAt(analysis.trees(), analysis.tree(), offset));
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
