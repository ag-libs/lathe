package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.FileUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public final class TempSourceCompiler implements JavaSourceCompiler {

  private static final Logger LOG = Logger.getLogger(TempSourceCompiler.class.getName());

  private final Path td;
  private final JavaCompiler compiler;
  private final StandardJavaFileManager fm;

  public TempSourceCompiler() {
    try {
      this.compiler = ToolProvider.getSystemJavaCompiler();
      this.fm = compiler.getStandardFileManager(null, null, null);
      this.td = Files.createTempDirectory("lathe-test-");
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public StandardJavaFileManager fileManager() {
    return fm;
  }

  @Override
  public CompilerResult compile(final String uri, final String content, final CompileMode mode) {
    try {
      final var filename = Path.of(URI.create(uri)).getFileName();
      final var tempFile = td.resolve(filename);
      Files.writeString(tempFile, content);

      final var collector = new DiagnosticCollector<JavaFileObject>();
      final var task =
          (JavacTask)
              compiler.getTask(
                  null,
                  fm,
                  collector,
                  List.of("-proc:none"),
                  null,
                  fm.getJavaFileObjects(tempFile));

      final var it = task.parse().iterator();
      final CompilationUnitTree cu = it.hasNext() ? it.next() : null;
      try {
        task.analyze();
      } catch (final IllegalStateException e) {
        // javac bug: certain sentinel-injected patterns (e.g. sentinel inside @SuppressWarnings)
        // trigger a NullPointerException inside Lint.suppressionsFrom — return the partial result.
        LOG.log(Level.SEVERE, e, () -> "javac bug: analyze() crashed on sentinel-injected source");
      }

      final Trees trees = Trees.instance(task);
      final AttributedFileAnalysis fileAnalysis;
      if (cu != null) {
        final List<SemanticToken> tokens = TokenScanner.scan(trees, cu);
        fileAnalysis =
            new AttributedFileAnalysis(trees, task.getElements(), task.getTypes(), cu, tokens);
      } else {
        fileAnalysis =
            new AttributedFileAnalysis(trees, task.getElements(), task.getTypes(), null, null);
      }

      return new CompilerResult(collector.getDiagnostics(), fileAnalysis);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    try {
      fm.close();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    try {
      FileUtil.deleteDir(td);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
