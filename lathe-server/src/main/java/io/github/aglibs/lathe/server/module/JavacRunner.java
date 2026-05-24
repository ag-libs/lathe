package io.github.aglibs.lathe.server.module;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.server.analysis.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

final class JavacRunner {

  private final StandardJavaFileManager fm;

  JavacRunner(final StandardJavaFileManager fm) {
    this.fm = fm;
  }

  CompilationResult run(
      final JavaFileObject sourceFile, final List<String> options, final CompileMode mode) {
    return mode == CompileMode.FULL
        ? compileFull(sourceFile, options)
        : analyze(sourceFile, options);
  }

  private CompilationResult compileFull(
      final JavaFileObject sourceFile, final List<String> options) {
    final var collector = new DiagnosticCollector<JavaFileObject>();
    createTask(sourceFile, options, collector).call();
    return new CompilationResult(collector.getDiagnostics(), FileAnalysis.diagnosticsOnly());
  }

  private JavacTask createTask(
      final JavaFileObject sourceFile,
      final List<String> options,
      final DiagnosticCollector<JavaFileObject> collector) {
    return (JavacTask)
        SourceCompiler.COMPILER.getTask(null, fm, collector, options, null, List.of(sourceFile));
  }

  private CompilationResult analyze(final JavaFileObject sourceFile, final List<String> options) {
    final var collector = new DiagnosticCollector<JavaFileObject>();
    final var task = createTask(sourceFile, options, collector);

    try {
      final var it = task.parse().iterator();
      final CompilationUnitTree cu = it.hasNext() ? it.next() : null;
      task.analyze();
      final Trees trees = Trees.instance(task);
      final var elements = task.getElements();
      final var types = task.getTypes();
      final FileAnalysis fileAnalysis;
      if (cu != null) {
        final List<SemanticToken> semanticTokens = TokenScanner.scan(trees, cu);
        fileAnalysis = new FileAnalysis(trees, elements, types, cu, semanticTokens);
      } else {
        fileAnalysis = new FileAnalysis(trees, elements, types, null, null);
      }

      return new CompilationResult(collector.getDiagnostics(), fileAnalysis);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
