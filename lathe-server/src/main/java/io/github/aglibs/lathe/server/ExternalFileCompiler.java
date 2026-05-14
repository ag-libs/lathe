package io.github.aglibs.lathe.server;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Stopwatch;
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
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

final class ExternalFileCompiler implements SourceCompiler, AutoCloseable {

  private static final Logger LOG = Logger.getLogger(ExternalFileCompiler.class.getName());

  private volatile WorkspaceManifest manifest;
  private final JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
  private final StandardJavaFileManager fm;
  private final Path td;
  private final ModuleAnalysis analysis = new ModuleAnalysis(this);

  ExternalFileCompiler(final WorkspaceManifest manifest) {
    this.manifest = manifest;
    try {
      this.fm = javac.getStandardFileManager(null, null, null);
      this.td = Files.createTempDirectory("lathe-ext-");
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  void setManifest(final WorkspaceManifest manifest) {
    this.manifest = manifest;
    analysis.clearCache();
  }

  ModuleAnalysis analysis() {
    return analysis;
  }

  ModuleAnalysis ensureCompiled(final String uri) throws IOException {
    if (!analysis.isCached(uri)) {
      final var content = Files.readString(Path.of(URI.create(uri)));
      analysis.compile(uri, content, CompileMode.OPEN);
    }
    return analysis;
  }

  @Override
  public StandardJavaFileManager fileManager() {
    return fm;
  }

  @Override
  public CompilationResult compile(final String uri, final String content, final CompileMode mode)
      throws IOException {
    final Path filePath = Path.of(URI.create(uri));
    final var sourceRoot = manifest.externalSourceRootForFile(filePath);
    if (sourceRoot.isEmpty()) {
      LOG.fine(() -> "[external] no source root for %s — skipping".formatted(uri));
      return new CompilationResult(List.of(), new CompilationTaskContext(null, null, null));
    }

    final Path rel = sourceRoot.get().relativize(filePath);
    final Path tempFile = td.resolve(rel);
    Files.createDirectories(tempFile.getParent());
    Files.writeString(tempFile, content);

    final var classpath = manifest.depClasspathForFile(filePath);
    fm.setLocation(StandardLocation.CLASS_PATH, classpath.stream().map(Path::toFile).toList());

    final var collector = new DiagnosticCollector<JavaFileObject>();
    final JavaFileObject jfo = fm.getJavaFileObjects(tempFile).iterator().next();
    final var task =
        (JavacTask) javac.getTask(null, fm, collector, List.of("-proc:none"), null, List.of(jfo));
    final var it = task.parse().iterator();
    final CompilationUnitTree cu = it.hasNext() ? it.next() : null;
    task.analyze();
    final Trees trees = Trees.instance(task);
    if (cu != null) {
      final var t = Stopwatch.start();
      final List<SemanticToken> tokens = SemanticTokensScanner.scan(trees, cu);
      LOG.fine(
          () ->
              "[external] %s compiled %d tokens %dms".formatted(uri, tokens.size(), t.elapsedMs()));
      return new CompilationResult(
          collector.getDiagnostics(), new CompilationTaskContext(trees, cu, tokens));
    }

    return new CompilationResult(
        collector.getDiagnostics(), new CompilationTaskContext(trees, null, null));
  }

  @Override
  public void close() {
    try {
      fm.close();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[external] failed to close file manager");
    }
    try {
      FileUtil.deleteDir(td);
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[external] failed to delete temp dir %s".formatted(td));
    }
    analysis.clearCache();
  }
}
