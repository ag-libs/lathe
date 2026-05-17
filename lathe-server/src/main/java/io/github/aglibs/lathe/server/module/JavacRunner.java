package io.github.aglibs.lathe.server.module;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.server.analysis.CompilationResult;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import io.github.aglibs.lathe.server.analysis.SemanticToken;
import io.github.aglibs.lathe.server.analysis.TokenScanner;
import java.io.IOException;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

final class JavacRunner {

  private final JavaCompiler compiler;
  private final StandardJavaFileManager fm;

  JavacRunner(final JavaCompiler compiler, final StandardJavaFileManager fm) {
    this.compiler = compiler;
    this.fm = fm;
  }

  CompilationResult run(
      final JavaFileObject sourceFile, final List<String> options, final CompileMode mode)
      throws IOException {
    final var collector = new DiagnosticCollector<JavaFileObject>();
    final var task =
        (JavacTask) compiler.getTask(null, fm, collector, options, null, List.of(sourceFile));
    final var it = task.parse().iterator();
    final CompilationUnitTree cu = it.hasNext() ? it.next() : null;
    task.analyze();
    if (mode == CompileMode.FULL) {
      task.generate();
    }

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
  }
}
