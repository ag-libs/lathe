package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.tools.JavaCompiler;
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

  StandardJavaFileManager fileManager();

  @Override
  void close();

  static CompilationUnitTree safeCompile(final JavacTask task) throws IOException {
    final var it = task.parse().iterator();
    final CompilationUnitTree cu = it.hasNext() ? it.next() : null;
    try {
      task.analyze();
    } catch (final RuntimeException e) {
      final Error fatal = fatalErrorCause(e);
      if (fatal != null) {
        throw fatal;
      }
      LOG.log(Level.SEVERE, e, () -> "javac bug: analyze() crashed on sentinel-injected source");
    }

    return cu;
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
