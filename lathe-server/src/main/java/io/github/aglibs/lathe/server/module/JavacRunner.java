package io.github.aglibs.lathe.server.module;

import com.sun.source.util.JavacTask;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.CompilerResult;
import io.github.aglibs.lathe.server.analysis.JavaSourceCompiler;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

final class JavacRunner {
  private final CompilationAdmission admission;
  private final StandardJavaFileManager fm;

  JavacRunner(final StandardJavaFileManager fm, final CompilationAdmission admission) {
    this.fm = fm;
    this.admission = admission;
  }

  CompilerResult run(
      final JavaFileObject sourceFile, final List<String> options, final CompileMode mode) {
    return run(sourceFile, options, mode, () -> {});
  }

  CompilerResult run(
      final JavaFileObject sourceFile,
      final List<String> options,
      final CompileMode mode,
      final CancelChecker cancelChecker) {
    cancelChecker.checkCanceled();
    return admission.run(
        cancelChecker,
        () -> {
          final CompilerResult result =
              mode == CompileMode.FULL
                  ? compileFull(sourceFile, options)
                  : analyze(sourceFile, options);
          cancelChecker.checkCanceled();
          return result;
        });
  }

  private CompilerResult compileFull(final JavaFileObject sourceFile, final List<String> options) {
    final var collector = new DiagnosticCollector<JavaFileObject>();
    createTask(sourceFile, options, collector).call();
    return new CompilerResult(collector.getDiagnostics(), AttributedFileAnalysis.diagnosticsOnly());
  }

  private JavacTask createTask(
      final JavaFileObject sourceFile,
      final List<String> options,
      final DiagnosticCollector<JavaFileObject> collector) {
    return (JavacTask)
        JavaSourceCompiler.COMPILER.getTask(
            null, fm, collector, options, null, List.of(sourceFile));
  }

  private CompilerResult analyze(final JavaFileObject sourceFile, final List<String> options) {
    final var collector = new DiagnosticCollector<JavaFileObject>();
    return JavaSourceCompiler.runAnalysis(createTask(sourceFile, options, collector), collector);
  }
}
