package io.github.aglibs.lathe.server.module;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.CompilerResult;
import io.github.aglibs.lathe.server.analysis.JavaSourceCompiler;
import io.github.aglibs.lathe.server.analysis.TokenScanner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

final class JavacRunner {
  private final CompilationAdmission admission;
  private final StandardJavaFileManager fm;

  JavacRunner(final StandardJavaFileManager fm, final CompilationAdmission admission) {
    this.fm = fm;
    this.admission = admission;
  }

  CompilerResult run(
      final JavaFileObject sourceFile,
      final List<String> options,
      final CancelChecker cancelChecker) {
    cancelChecker.checkCanceled();
    return admission.run(
        cancelChecker,
        () -> {
          final CompilerResult result = analyze(sourceFile, options);
          cancelChecker.checkCanceled();
          return result;
        });
  }

  List<AttributedFileAnalysis> analyzeBatch(
      final Iterable<? extends JavaFileObject> sourceFiles,
      final List<String> options,
      final CancelChecker cancelChecker) {
    return analyzeBatchDiagnosed(sourceFiles, options, false, cancelChecker).analyses();
  }

  // Analyze several files in one task, keeping the diagnostics: the dependents refresh, and a live
  // analysis joined by the unsaved buffers of its tree. withTokens also scans each file's semantic
  // tokens, as a single open-file analysis does, for analyses that will be cached and served.
  BatchAnalysis analyzeBatchDiagnosed(
      final Iterable<? extends JavaFileObject> sourceFiles,
      final List<String> options,
      final boolean withTokens,
      final CancelChecker cancelChecker) {
    cancelChecker.checkCanceled();
    return admission.run(
        cancelChecker,
        () -> {
          final BatchAnalysis batch = attributeBatch(sourceFiles, options, withTokens);
          cancelChecker.checkCanceled();
          return batch;
        });
  }

  private BatchAnalysis attributeBatch(
      final Iterable<? extends JavaFileObject> sourceFiles,
      final List<String> options,
      final boolean withTokens) {
    final var collector = new DiagnosticCollector<JavaFileObject>();
    final var task = createTask(sourceFiles, options, collector);
    try {
      final Iterable<? extends CompilationUnitTree> units = task.parse();
      final Set<URI> parseFailed =
          collector.getDiagnostics().stream()
              .filter(JavaSourceCompiler::isError)
              .filter(diagnostic -> diagnostic.getSource() != null)
              .map(diagnostic -> diagnostic.getSource().toUri())
              .collect(Collectors.toUnmodifiableSet());
      JavaSourceCompiler.analyzeSafely(task);
      final var trees = Trees.instance(task);
      final Elements elements = task.getElements();
      final Types types = task.getTypes();
      final var analyses = new ArrayList<AttributedFileAnalysis>();
      for (final var cu : units) {
        analyses.add(
            new AttributedFileAnalysis(
                trees, elements, types, cu, withTokens ? TokenScanner.scan(trees, cu) : null));
      }

      return new BatchAnalysis(analyses, collector.getDiagnostics(), parseFailed);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // parseFailed: the sources javac's parse phase reported an error in.
  record BatchAnalysis(
      List<AttributedFileAnalysis> analyses,
      List<Diagnostic<? extends JavaFileObject>> diagnostics,
      Set<URI> parseFailed) {
    BatchAnalysis {
      analyses = List.copyOf(analyses);
      diagnostics = List.copyOf(diagnostics);
      parseFailed = Set.copyOf(parseFailed);
    }
  }

  // Full-compile mutually dependent sources in one task so their cross-references resolve; no
  // SOURCE_PATH, so batching is the only way stale siblings see each other's bytecode.
  CompilerResult compileBatch(
      final Iterable<? extends JavaFileObject> sourceFiles,
      final List<String> options,
      final CancelChecker cancelChecker) {
    cancelChecker.checkCanceled();
    return admission.run(
        cancelChecker,
        () -> {
          final CompilerResult result = generate(sourceFiles, options);
          cancelChecker.checkCanceled();
          return result;
        });
  }

  private CompilerResult generate(
      final Iterable<? extends JavaFileObject> sourceFiles, final List<String> options) {
    final var collector = new DiagnosticCollector<JavaFileObject>();
    final var task = createTask(sourceFiles, options, collector);
    try {
      task.analyze();
      final Iterable<? extends JavaFileObject> generated = task.generate();
      return new CompilerResult(
          collector.getDiagnostics(),
          AttributedFileAnalysis.diagnosticsOnly(),
          writtenBinaryNames(generated));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // Not fm.inferBinaryName: javac's implementation calls a method Lombok's wrapper of the written
  // file lacks (AbstractMethodError). javac reports outputs at their resolved path, so the root is
  // resolved too, or a symlinked workspace (macOS /var -> /private/var) would not match.
  private Set<String> writtenBinaryNames(final Iterable<? extends JavaFileObject> generated)
      throws IOException {
    final Path root =
        fm.getLocationAsPaths(StandardLocation.CLASS_OUTPUT).iterator().next().toRealPath();
    final Set<String> names = new HashSet<>();
    for (final var jfo : generated) {
      names.add(FileUtil.classBinaryName(root, Path.of(jfo.toUri()).toRealPath()));
    }

    return Set.copyOf(names);
  }

  private JavacTask createTask(
      final Iterable<? extends JavaFileObject> sourceFiles,
      final List<String> options,
      final DiagnosticCollector<JavaFileObject> collector) {
    return (JavacTask)
        JavaSourceCompiler.COMPILER.getTask(null, fm, collector, options, null, sourceFiles);
  }

  private CompilerResult analyze(final JavaFileObject sourceFile, final List<String> options) {
    final var collector = new DiagnosticCollector<JavaFileObject>();
    return JavaSourceCompiler.runAnalysis(
        createTask(List.of(sourceFile), options, collector), collector);
  }
}
