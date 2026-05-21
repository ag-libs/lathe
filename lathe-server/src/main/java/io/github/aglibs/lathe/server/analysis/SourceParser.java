package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;

public final class SourceParser implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(SourceParser.class.getName());

  private final StandardJavaFileManager fm;

  public SourceParser() {
    this.fm = SourceCompiler.createFileManager();
  }

  <T> Optional<T> parseFile(
      final Path sourceFile, final BiFunction<Trees, CompilationUnitTree, T> fn) {
    return parse(fm.getJavaFileObjects(sourceFile).iterator().next(), fn);
  }

  public <T> Optional<T> parseContent(
      final String uri, final String content, final BiFunction<Trees, CompilationUnitTree, T> fn) {
    final var jfo =
        new SimpleJavaFileObject(URI.create(uri), JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return content;
          }
        };
    return parse(jfo, fn);
  }

  private <T> Optional<T> parse(
      final JavaFileObject jfo, final BiFunction<Trees, CompilationUnitTree, T> fn) {
    final var collector = new DiagnosticCollector<JavaFileObject>();
    final var task =
        (JavacTask) SourceCompiler.COMPILER.getTask(null, fm, collector, null, null, List.of(jfo));
    try {
      final CompilationUnitTree cu = task.parse().iterator().next();
      final T result = fn.apply(Trees.instance(task), cu);
      final var diags = collector.getDiagnostics();
      if (!diags.isEmpty()) {
        LOG.fine(() -> "[parse] diags: " + diagSummary(diags));
      }
      return Optional.ofNullable(result);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String diagSummary(
      final List<? extends Diagnostic<? extends JavaFileObject>> diags) {
    return diags.stream()
        .collect(Collectors.groupingBy(Diagnostic::getKind, Collectors.counting()))
        .entrySet()
        .stream()
        .map(e -> e.getKey() + "=" + e.getValue())
        .collect(Collectors.joining(" "));
  }

  @Override
  public void close() {
    try {
      fm.close();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[parser] failed to close fm");
    }
  }
}
