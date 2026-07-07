package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

public interface JavaSourceCompiler extends AutoCloseable {
  Logger LOG = Logger.getLogger(JavaSourceCompiler.class.getName());

  JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

  static StandardJavaFileManager createFileManager() {
    return COMPILER.getStandardFileManager(null, null, null);
  }

  CompilerResult compile(String uri, String content, CompileMode mode);

  default CompilerResult compile(
      final String uri,
      final String content,
      final CompileMode mode,
      final CancelChecker cancelChecker) {
    cancelChecker.checkCanceled();
    final CompilerResult result = compile(uri, content, mode);
    cancelChecker.checkCanceled();
    return result;
  }

  default AttributedFileAnalysis reattribute(final String uri, final String content) {
    return compile(uri, content, CompileMode.FAST).fileAnalysis();
  }

  /**
   * Attributes several isolated candidates for reference search. The default falls back to one FAST
   * compile per file; {@code ModuleSourceCompiler} overrides it with a single multi-file task that
   * amortizes javac's fixed per-invocation cost across the batch.
   */
  default List<TransientAnalysis> analyzeBatch(
      final List<TransientSource> sources, final CancelChecker cancelChecker) {
    final var analyses = new ArrayList<TransientAnalysis>(sources.size());
    for (final var source : sources) {
      cancelChecker.checkCanceled();
      final AttributedFileAnalysis analysis =
          compile(source.uri(), source.content(), CompileMode.FAST, cancelChecker).fileAnalysis();
      analyses.add(new TransientAnalysis(source.uri(), analysis));
    }

    return List.copyOf(analyses);
  }

  StandardJavaFileManager fileManager();

  @Override
  void close();

  static CompilerResult runAnalysis(
      final JavacTask task, final DiagnosticCollector<JavaFileObject> collector) {
    try {
      final CompilationUnitTree cu = safeCompile(task);
      final var trees = Trees.instance(task);
      if (cu != null) {
        final List<SemanticToken> tokens = TokenScanner.scan(trees, cu);
        return new CompilerResult(
            collector.getDiagnostics(),
            new AttributedFileAnalysis(trees, task.getElements(), task.getTypes(), cu, tokens),
            Set.of());
      }

      return new CompilerResult(
          collector.getDiagnostics(),
          new AttributedFileAnalysis(trees, task.getElements(), task.getTypes(), null, null),
          Set.of());
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static CompilationUnitTree safeCompile(final JavacTask task) throws IOException {
    final var it = task.parse().iterator();
    final CompilationUnitTree cu = it.hasNext() ? it.next() : null;
    analyzeSafely(task);
    return cu;
  }

  static void analyzeSafely(final JavacTask task) throws IOException {
    try {
      task.analyze();
    } catch (final RuntimeException e) {
      final Error fatal = fatalErrorCause(e);
      if (fatal != null) {
        throw fatal;
      }
      LOG.log(Level.SEVERE, e, () -> "javac bug: analyze() crashed on sentinel-injected source");
    }
  }

  static Error fatalErrorCause(final Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof Error error) {
        return error;
      }
      current = current.getCause();
    }
    return null;
  }
}
