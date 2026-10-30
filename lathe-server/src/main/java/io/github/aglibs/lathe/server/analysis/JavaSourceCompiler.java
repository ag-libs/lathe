package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.IOUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

public interface JavaSourceCompiler extends AutoCloseable {
  Logger LOG = Logger.getLogger(JavaSourceCompiler.class.getName());

  JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

  // With an empty processor path, so the plugin and processor lookup javac runs before every parse
  // searches nothing. Unset, javac searches the classpath through a class loader that only
  // call()/generate() close: analysis-only tasks would leave every classpath jar open until GC,
  // which on Windows blocks a build from replacing them. A compile that runs processors sets the
  // path it needs for its run (ModuleSourceCompiler's full compiles).
  static StandardJavaFileManager createFileManager() {
    final StandardJavaFileManager fm = COMPILER.getStandardFileManager(null, null, null);
    setProcessorPath(fm, List.of());
    return fm;
  }

  static void setProcessorPath(final StandardJavaFileManager fm, final List<Path> processorPath) {
    IOUtil.unchecked(
        () -> fm.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, processorPath));
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

  /**
   * Full-compiles mutually dependent sources in one task so their cross-references resolve. The
   * default compiles per file; {@code ModuleSourceCompiler} overrides with one multi-file task.
   */
  default BatchWrite compileBatch(
      final List<TransientSource> sources, final CancelChecker cancelChecker) {
    final var written = new HashSet<String>();
    final var diagnostics = new HashMap<String, List<org.eclipse.lsp4j.Diagnostic>>();
    for (final var source : sources) {
      cancelChecker.checkCanceled();
      final CompilerResult result =
          compile(source.uri(), source.content(), CompileMode.FULL, cancelChecker);
      written.addAll(result.writtenBinaryNames());
      diagnostics.put(
          source.uri(), JavacDiagnosticMapper.filterAndMap(result.diagnostics(), source.content()));
    }

    return new BatchWrite(written, diagnostics);
  }

  /**
   * Analyzes open documents together, one result per source as its own OPEN compile would give. The
   * default compiles each alone; {@code ModuleSourceCompiler} overrides it with one task.
   */
  default List<CompilerResult> analyzeOpenBatch(
      final List<TransientSource> sources, final CancelChecker cancelChecker) {
    return sources.stream()
        .map(source -> compile(source.uri(), source.content(), CompileMode.OPEN, cancelChecker))
        .toList();
  }

  StandardJavaFileManager fileManager();

  @Override
  void close();

  static CompilerResult runAnalysis(
      final JavacTask task, final DiagnosticCollector<JavaFileObject> collector) {
    try {
      final var it = task.parse().iterator();
      final CompilationUnitTree cu = it.hasNext() ? it.next() : null;
      final boolean parsedCleanly =
          collector.getDiagnostics().stream().noneMatch(JavaSourceCompiler::isError);
      analyzeSafely(task);
      final var trees = Trees.instance(task);
      final List<SemanticToken> tokens = cu != null ? TokenScanner.scan(trees, cu) : null;
      return new CompilerResult(
          collector.getDiagnostics(),
          new AttributedFileAnalysis(trees, task.getElements(), task.getTypes(), cu, tokens),
          Set.of(),
          cu != null && parsedCleanly);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static boolean isError(final Diagnostic<?> diagnostic) {
    return diagnostic.getKind() == Diagnostic.Kind.ERROR;
  }

  // False when javac crashed and the analysis is partial: nothing may be generated from it.
  static boolean analyzeSafely(final JavacTask task) throws IOException {
    try {
      task.analyze();
      return true;
    } catch (final RuntimeException e) {
      final Error fatal = fatalErrorCause(e);
      if (fatal != null && !isJavacAssertion(fatal)) {
        throw fatal;
      }

      // A javac bug during analyze() (often on Lathe's sentinel/recovery-modified source): log a
      // source-free, actionable line -- phase and the JDK identity that a crash report needs, plus
      // the stack trace -- then continue with partial analysis. The exact triggering source is NOT
      // logged (logging policy); reproduce to capture it.
      LOG.log(
          Level.SEVERE,
          e,
          () ->
              "[javacCrash] phase=analyze jdk=%s javac threw during analyze; returning partial analysis"
                  .formatted(javaVersion()));
      return false;
    }
  }

  /**
   * JDK identity for a javac-crash log line. javac bugs are version-specific, so a crash report is
   * only actionable with the exact runtime; unlike the inputs that triggered the crash, this is
   * source-free and safe to log.
   */
  private static String javaVersion() {
    final String version = System.getProperty("java.version", "unknown");
    final String vendor = System.getProperty("java.vendor.version", "");
    return vendor.isBlank() ? version : "%s (%s)".formatted(version, vendor);
  }

  // javac reports its own bugs as an AssertionError, and attributing broken code, which plain javac
  // never does, reaches some: deleting a `try {` line leaves a catch that javac's Attr rejects. It
  // fails one analysis, not the server. javac wraps every Error it throws, so the Error's own top
  // frame tells javac's from a processor's, a plugin's or Lathe's own, which stay fatal.
  private static boolean isJavacAssertion(final Error error) {
    final StackTraceElement[] stack = error.getStackTrace();
    return error instanceof AssertionError
        && stack.length > 0
        && stack[0].getClassName().startsWith("com.sun.tools.javac.");
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
