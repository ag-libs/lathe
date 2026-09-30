package io.github.aglibs.lathe.server.module;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.CompilerResult;
import io.github.aglibs.lathe.server.analysis.JavaSourceCompiler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

  List<AttributedFileAnalysis> analyzeBatch(
      final Iterable<? extends JavaFileObject> sourceFiles,
      final List<String> options,
      final CancelChecker cancelChecker) {
    return analyzeBatchDiagnosed(sourceFiles, options, cancelChecker).analyses();
  }

  // Analyze several files in one task, keeping the diagnostics, so a live compile can widen a
  // single-file failure into the file's siblings.
  BatchAnalysis analyzeBatchDiagnosed(
      final Iterable<? extends JavaFileObject> sourceFiles,
      final List<String> options,
      final CancelChecker cancelChecker) {
    cancelChecker.checkCanceled();
    return admission.run(
        cancelChecker,
        () -> {
          final BatchAnalysis batch = attributeBatch(sourceFiles, options);
          cancelChecker.checkCanceled();
          return batch;
        });
  }

  private BatchAnalysis attributeBatch(
      final Iterable<? extends JavaFileObject> sourceFiles, final List<String> options) {
    final var collector = new DiagnosticCollector<JavaFileObject>();
    final var task = createTask(sourceFiles, options, collector);
    try {
      final Iterable<? extends CompilationUnitTree> units = task.parse();
      JavaSourceCompiler.analyzeSafely(task);
      final var trees = Trees.instance(task);
      final Elements elements = task.getElements();
      final Types types = task.getTypes();
      final var analyses = new ArrayList<AttributedFileAnalysis>();
      for (final var cu : units) {
        analyses.add(new AttributedFileAnalysis(trees, elements, types, cu, null));
      }

      return new BatchAnalysis(analyses, collector.getDiagnostics());
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  record BatchAnalysis(
      List<AttributedFileAnalysis> analyses,
      List<Diagnostic<? extends JavaFileObject>> diagnostics) {
    BatchAnalysis {
      analyses = List.copyOf(analyses);
      diagnostics = List.copyOf(diagnostics);
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

  private CompilerResult compileFull(final JavaFileObject sourceFile, final List<String> options) {
    return generate(List.of(sourceFile), options);
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

  private Set<String> writtenBinaryNames(final Iterable<? extends JavaFileObject> generated) {
    final Path classesRoot = fm.getLocationAsPaths(StandardLocation.CLASS_OUTPUT).iterator().next();
    final Set<String> names = new HashSet<>();
    for (final var jfo : generated) {
      final var classFile = Path.of(jfo.toUri());
      final var relative = classesRoot.relativize(classFile).toString();
      names.add(
          relative
              .substring(0, relative.length() - ".class".length())
              .replace(classFile.getFileSystem().getSeparator(), "."));
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
