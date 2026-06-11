package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.server.LatheUri;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.CompilerResult;
import io.github.aglibs.lathe.server.analysis.JavaSourceCompiler;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

public final class ExternalCompiler implements JavaSourceCompiler {

  private static final Logger LOG = Logger.getLogger(ExternalCompiler.class.getName());

  private final WorkspaceManifest manifest;

  private final StandardJavaFileManager fm;
  private final JavacRunner runner;
  private final Path td;
  private final Set<String> patchedModules = new HashSet<>();

  public ExternalCompiler(final WorkspaceManifest manifest) {
    this.manifest = manifest;
    try {
      this.fm = JavaSourceCompiler.createFileManager();
      this.runner = new JavacRunner(fm);
      this.td = Files.createTempDirectory("lathe-ext-");
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
    final var filePath = LatheUri.toPath(uri);
    final var sourceRoot = manifest.externalSourceRootForFile(filePath);
    if (sourceRoot.isEmpty()) {
      LOG.fine(() -> "[external] no source root for %s — skipping".formatted(uri));
      return new CompilerResult(
          List.of(), new AttributedFileAnalysis(null, null, null, null, null));
    }

    try {
      final var tempFile = FileUtil.writeTempSourceFile(td, sourceRoot.get(), filePath, content);

      final var classpath = manifest.depClasspathForFile(filePath);
      fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);

      final JavaFileObject jfo = fm.getJavaFileObjects(tempFile).iterator().next();
      final List<String> options = buildOptions(filePath);
      final var externalMode = mode == CompileMode.FULL ? CompileMode.FAST : mode;
      return runner.run(jfo, options, externalMode);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
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
  }
}
