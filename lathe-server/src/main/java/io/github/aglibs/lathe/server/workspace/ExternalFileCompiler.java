package io.github.aglibs.lathe.server.workspace;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.analysis.AnalysisEngine;
import io.github.aglibs.lathe.server.analysis.CompilationResult;
import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import io.github.aglibs.lathe.server.module.CompileMode;
import io.github.aglibs.lathe.server.module.SourceCompiler;
import io.github.aglibs.lathe.server.tokens.TokenScanner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

public final class ExternalFileCompiler implements SourceCompiler, AutoCloseable {

  private static final Logger LOG = Logger.getLogger(ExternalFileCompiler.class.getName());

  private WorkspaceManifest manifest;
  private final JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
  private final StandardJavaFileManager fm;
  private final Path td;
  private final Set<String> patchedModules = new HashSet<>();
  private final AnalysisEngine analysis;

  public ExternalFileCompiler(final WorkspaceManifest manifest) {
    this.manifest = manifest;
    try {
      this.fm = javac.getStandardFileManager(null, null, null);
      this.td = Files.createTempDirectory("lathe-ext-");
      this.analysis = new AnalysisEngine(this);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void setManifest(final WorkspaceManifest manifest) {
    this.manifest = manifest;
    analysis.clearCache();
  }

  public AnalysisEngine analysis() {
    return analysis;
  }

  public AnalysisEngine ensureCompiled(final String uri) throws IOException {
    if (!analysis.isCached(uri)) {
      final var content = Files.readString(Path.of(URI.create(uri)));
      analysis.compile(uri, content, CompileMode.OPEN);
    }
    return analysis;
  }

  @Override
  public JavaCompiler compiler() {
    return javac;
  }

  @Override
  public StandardJavaFileManager fileManager() {
    return fm;
  }

  @Override
  public CompilationResult compile(final String uri, final String content, final CompileMode mode)
      throws IOException {
    final var filePath = Path.of(URI.create(uri));
    final var sourceRoot = manifest.externalSourceRootForFile(filePath);
    if (sourceRoot.isEmpty()) {
      LOG.fine(() -> "[external] no source root for %s — skipping".formatted(uri));
      return new CompilationResult(List.of(), new FileAnalysis(null, null, null));
    }

    final var rel = sourceRoot.get().relativize(filePath);
    final var tempFile = td.resolve(rel);
    Files.createDirectories(tempFile.getParent());
    Files.writeString(tempFile, content);

    final var classpath = manifest.depClasspathForFile(filePath);
    fm.setLocation(StandardLocation.CLASS_PATH, classpath.stream().map(Path::toFile).toList());

    final var collector = new DiagnosticCollector<JavaFileObject>();
    final JavaFileObject jfo = fm.getJavaFileObjects(tempFile).iterator().next();
    final List<String> options = buildOptions(filePath);
    final var task = (JavacTask) javac.getTask(null, fm, collector, options, null, List.of(jfo));
    final var it = task.parse().iterator();
    final CompilationUnitTree cu = it.hasNext() ? it.next() : null;
    task.analyze();
    final Trees trees = Trees.instance(task);
    if (cu != null) {
      final var t = Stopwatch.start();
      final List<io.github.aglibs.lathe.server.tokens.SemanticToken> tokens =
          TokenScanner.scan(trees, cu);
      LOG.fine(
          () ->
              "[external] %s compiled %d tokens %dms".formatted(uri, tokens.size(), t.elapsedMs()));
      return new CompilationResult(collector.getDiagnostics(), new FileAnalysis(trees, cu, tokens));
    }

    return new CompilationResult(collector.getDiagnostics(), new FileAnalysis(trees, null, null));
  }

  private List<String> buildOptions(final Path filePath) {
    final var options = new ArrayList<String>();
    options.add("-proc:none");
    manifest
        .jdkModuleForFile(filePath)
        .filter(patchedModules::add)
        .ifPresent(
            moduleName -> {
              options.add("--patch-module");
              options.add(moduleName + "=" + td.resolve(moduleName));
            });
    return List.copyOf(options);
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
