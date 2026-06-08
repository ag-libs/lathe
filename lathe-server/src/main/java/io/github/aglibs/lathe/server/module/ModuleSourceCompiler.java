package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.CompilerResult;
import io.github.aglibs.lathe.server.analysis.JavaSourceCompiler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

public final class ModuleSourceCompiler implements JavaSourceCompiler, AutoCloseable {

  private static final Logger LOG = Logger.getLogger(ModuleSourceCompiler.class.getName());
  private static final String PATCH_MODULE = "--patch-module";
  private static final String PATCH_MODULE_EQ = PATCH_MODULE + "=";

  private final ModuleSourceConfig config;
  private final StandardJavaFileManager fm;
  private final JavacRunner runner;
  private final Path td;
  private final List<String> compilerArgs;

  ModuleSourceCompiler(final ModuleSourceConfig config) {
    this.config = config;
    try {
      this.td = Files.createTempDirectory("lathe-");
      this.fm = JavaSourceCompiler.createFileManager();
      this.runner = new JavacRunner(fm);
      initLocations();
      this.compilerArgs = processPatchModules(config.compilerArgs(), fm, td);
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
    final var filePath = Path.of(URI.create(uri));
    final Path sourceRoot =
        config.sourceRoots().stream()
            .filter(filePath::startsWith)
            .max(Comparator.comparingInt(Path::getNameCount))
            .orElseThrow(() -> new IllegalStateException("no source root for " + uri));

    try {
      final var tempFile = FileUtil.writeTempSourceFile(td, sourceRoot, filePath, content);

      final var options = buildOptions(config, compilerArgs, mode);
      LOG.fine(
          () -> "[compile:%s] td=%s root=%s opts=%s".formatted(mode.tag, td, sourceRoot, options));
      final JavaFileObject jfo = fm.getJavaFileObjects(tempFile).iterator().next();
      try {
        return runner.run(jfo, options, mode);
      } finally {
        if (mode == CompileMode.FULL) {
          fm.flush();
        }
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    try {
      fm.close();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[cache] failed to close file manager");
    }
    try {
      FileUtil.deleteDir(td);
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[cache] failed to delete temp dir %s".formatted(td));
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
      final List<String> args, final StandardJavaFileManager fm, final Path td) {
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
                    PATCH_MODULE, List.of(v.substring(0, v.indexOf('=')) + "=" + td).iterator()));
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
