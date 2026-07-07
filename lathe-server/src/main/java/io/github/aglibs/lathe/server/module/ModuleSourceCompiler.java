package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.server.LatheUri;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.CompilerResult;
import io.github.aglibs.lathe.server.analysis.JavaSourceCompiler;
import io.github.aglibs.lathe.server.analysis.TransientAnalysis;
import io.github.aglibs.lathe.server.analysis.TransientSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

public final class ModuleSourceCompiler implements JavaSourceCompiler, AutoCloseable {

  private static final Logger LOG = Logger.getLogger(ModuleSourceCompiler.class.getName());
  private static final String PATCH_MODULE = "--patch-module";
  private static final String PATCH_MODULE_EQ = PATCH_MODULE + "=";

  private final ModuleSourceConfig config;
  private final StandardJavaFileManager fm;
  private final JavacRunner runner;
  private final Path tempDir;
  private final List<String> compilerArgs;

  ModuleSourceCompiler(
      final ModuleSourceConfig config, final CompilationAdmission compilationAdmission) {
    this.config = config;
    try {
      this.tempDir = Files.createTempDirectory("lathe-");
      this.fm = JavaSourceCompiler.createFileManager();
      this.runner = new JavacRunner(fm, compilationAdmission);
      initLocations();
      this.compilerArgs = processPatchModules(config.compilerArgs(), fm, tempDir);
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
    return compile(uri, content, mode, () -> {});
  }

  @Override
  public CompilerResult compile(
      final String uri,
      final String content,
      final CompileMode mode,
      final CancelChecker cancelChecker) {
    final var tempFile = writeTempFile(uri, content);
    final var options = buildOptions(config, compilerArgs, mode);
    LOG.fine(() -> "[compile:%s] tempDir=%s opts=%s".formatted(mode.tag, tempDir, options));
    final JavaFileObject jfo = fm.getJavaFileObjects(tempFile).iterator().next();
    try {
      return runner.run(jfo, options, mode, cancelChecker);
    } finally {
      if (mode == CompileMode.FULL) {
        IOUtil.unchecked(fm::flush);
      }
    }
  }

  @Override
  public List<TransientAnalysis> analyzeBatch(
      final List<TransientSource> sources, final CancelChecker cancelChecker) {
    final var options = buildOptions(config, compilerArgs, CompileMode.FAST);
    final Map<Path, String> uriByTempFile = new HashMap<>();
    final var tempFiles = new ArrayList<Path>(sources.size());
    for (final var source : sources) {
      cancelChecker.checkCanceled();
      final var tempFile = writeTempFile(source.uri(), source.content());
      tempFiles.add(tempFile);
      uriByTempFile.put(tempFile.normalize(), source.uri());
    }

    final List<AttributedFileAnalysis> analyses =
        runner.analyzeBatch(
            fm.getJavaFileObjects(tempFiles.toArray(Path[]::new)), options, cancelChecker);
    return analyses.stream().map(analysis -> toTransientAnalysis(analysis, uriByTempFile)).toList();
  }

  private static TransientAnalysis toTransientAnalysis(
      final AttributedFileAnalysis analysis, final Map<Path, String> uriByTempFile) {
    final Path sourcePath = Path.of(analysis.tree().getSourceFile().toUri()).normalize();
    return new TransientAnalysis(uriByTempFile.get(sourcePath), analysis);
  }

  private Path writeTempFile(final String uri, final String content) {
    final var filePath = LatheUri.toPath(uri);
    final Path sourceRoot =
        config.sourceRoots().stream()
            .filter(filePath::startsWith)
            .max(Comparator.comparingInt(Path::getNameCount))
            .orElseGet(() -> generatedSourceRoot(filePath, uri));
    try {
      return FileUtil.writeTempSourceFile(tempDir, sourceRoot, filePath, content);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Path generatedSourceRoot(final Path filePath, final String uri) {
    final Path genRoot = config.originalGenSourcesDir();
    if (genRoot != null && filePath.startsWith(genRoot)) {
      return genRoot;
    }

    throw new IllegalStateException("no source root for %s".formatted(uri));
  }

  @Override
  public void close() {
    try {
      fm.close();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[cache] failed to close file manager");
    }
    try {
      FileUtil.deleteDir(tempDir);
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[cache] failed to delete temp dir %s".formatted(tempDir));
    }
  }

  private void initLocations() throws IOException {
    final var classesDir = config.latheClassesDir();
    Files.createDirectories(classesDir);
    fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classesDir));
    LOG.fine(() -> "[cache] CLASS_OUTPUT=%s".formatted(classesDir));

    final var genSourcesDir = config.generatedSourcesDir();
    Files.createDirectories(genSourcesDir);
    fm.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(genSourcesDir));
    LOG.fine(() -> "[cache] SOURCE_OUTPUT=%s".formatted(genSourcesDir));

    final var classpath =
        Stream.concat(Stream.of(classesDir), config.remappedClasspath().stream())
            .distinct()
            .toList();
    if (!classpath.isEmpty()) {
      fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
      LOG.fine(() -> "[cache] CLASS_PATH=%s".formatted(classpath));
    }

    final var modulepath = config.remappedModulepath();
    if (!modulepath.isEmpty()) {
      fm.setLocationFromPaths(StandardLocation.MODULE_PATH, modulepath);
      LOG.fine(() -> "[cache] MODULE_PATH=%s".formatted(modulepath));
    }

    if (!config.processorPath().isEmpty()) {
      final var processorPath = config.remappedProcessorPath();
      fm.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, processorPath);
      LOG.fine(() -> "[cache] ANNOTATION_PROCESSOR_PATH=%s".formatted(processorPath));
    }
  }

  private static List<String> processPatchModules(
      final List<String> args, final StandardJavaFileManager fm, final Path tempDir) {
    final var normalized = new ArrayList<String>(args.size());
    final var it = args.iterator();
    while (it.hasNext()) {
      final var arg = it.next();
      normalized.add(arg.equals(PATCH_MODULE) && it.hasNext() ? PATCH_MODULE_EQ + it.next() : arg);
    }
    normalized.stream()
        .filter(a -> a.startsWith(PATCH_MODULE_EQ))
        .map(a -> a.substring(PATCH_MODULE_EQ.length()))
        .filter(v -> v.indexOf('=') > 0)
        .forEach(
            v ->
                fm.handleOption(
                    PATCH_MODULE,
                    List.of("%s=%s".formatted(v.substring(0, v.indexOf('=')), tempDir))
                        .iterator()));
    return normalized.stream().filter(a -> !a.startsWith(PATCH_MODULE_EQ)).toList();
  }

  private static List<String> buildOptions(
      final ModuleSourceConfig config, final List<String> compilerArgs, final CompileMode mode) {
    final var opts = new ArrayList<String>();
    if (config.release() != null && !config.release().isBlank()) {
      opts.add("--release");
      opts.add(config.release());
    }
    opts.add("-encoding");
    opts.add(config.encoding());
    if (config.parameters()) {
      opts.add("-parameters");
    }
    if (config.enablePreview()) {
      opts.add("--enable-preview");
    }
    opts.addAll(modeCompilerArgs(compilerArgs, mode));
    if (mode == CompileMode.FAST || mode == CompileMode.OPEN) {
      opts.add("-proc:none");
    }
    return opts;
  }

  public static List<String> modeCompilerArgs(final List<String> args, final CompileMode mode) {
    return mode == CompileMode.FULL
        ? args
        : args.stream().filter(ModuleSourceCompiler::isInteractiveCompilerArg).toList();
  }

  private static boolean isInteractiveCompilerArg(final String arg) {
    return !arg.startsWith("-Xplugin:") && !arg.startsWith("-Xep");
  }
}
