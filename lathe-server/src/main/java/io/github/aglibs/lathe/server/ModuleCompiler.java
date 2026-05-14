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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

final class ModuleCompiler implements SourceCompiler, AutoCloseable {

  private static final Logger LOG = Logger.getLogger(ModuleCompiler.class.getName());
  private static final String PATCH_MODULE = "--patch-module";
  private static final String PATCH_MODULE_EQ = PATCH_MODULE + "=";

  private final ModuleConfig config;
  private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
  private final StandardJavaFileManager fm;
  private final Path td;
  private final List<String> compilerArgs;
  private final AnalysisEngine analysis = new AnalysisEngine(this);

  ModuleCompiler(final ModuleConfig config) {
    this.config = config;
    try {
      this.td = Files.createTempDirectory("lathe-");
      this.fm = compiler.getStandardFileManager(null, null, null);
      initLocations();
      this.compilerArgs = processPatchModules(config.compilerArgs(), fm, td);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  AnalysisEngine analysis() {
    return analysis;
  }

  @Override
  public StandardJavaFileManager fileManager() {
    return fm;
  }

  @Override
  public CompilationResult compile(final String uri, final String content, final CompileMode mode)
      throws IOException {
    final DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
    final Path filePath = Path.of(URI.create(uri));
    final Path sourceRoot =
        config.sourceRoots().stream()
            .filter(filePath::startsWith)
            .max(Comparator.comparingInt(Path::getNameCount))
            .orElseThrow(() -> new IllegalStateException("no source root for " + uri));

    final var tempFile = td.resolve(sourceRoot.relativize(filePath));
    Files.createDirectories(tempFile.getParent());
    Files.writeString(tempFile, content);

    final var options = buildOptions(config, compilerArgs, mode);
    LOG.fine(() -> "[%s] td=%s root=%s opts=%s".formatted(mode.tag, td, sourceRoot, options));
    final JavaFileObject file = fm.getJavaFileObjects(tempFile).iterator().next();
    try {
      final FileAnalysis fileAnalysis = runTask(collector, options, file, mode);
      return new CompilationResult(collector.getDiagnostics(), fileAnalysis);
    } finally {
      if (mode == CompileMode.FULL) {
        fm.flush();
      }
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
    analysis.clearCache();
  }

  private void initLocations() throws IOException {
    final var classesDir = config.latheClassesDir();
    Files.createDirectories(classesDir);
    fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classesDir.toFile()));
    LOG.fine(() -> "[cache] CLASS_OUTPUT=%s".formatted(classesDir));

    final var genSourcesDir = config.generatedSourcesDir();
    Files.createDirectories(genSourcesDir);
    fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(genSourcesDir.toFile()));
    LOG.fine(() -> "[cache] SOURCE_OUTPUT=%s".formatted(genSourcesDir));

    final var classpath = config.remappedClasspath();
    if (!classpath.isEmpty()) {
      fm.setLocation(StandardLocation.CLASS_PATH, classpath.stream().map(Path::toFile).toList());
      LOG.fine(() -> "[cache] CLASS_PATH=%s".formatted(classpath));
    }

    final var modulepath = config.remappedModulepath();
    if (!modulepath.isEmpty()) {
      fm.setLocation(StandardLocation.MODULE_PATH, modulepath.stream().map(Path::toFile).toList());
      LOG.fine(() -> "[cache] MODULE_PATH=%s".formatted(modulepath));
    }

    if (!config.processorPath().isEmpty()) {
      fm.setLocation(
          StandardLocation.ANNOTATION_PROCESSOR_PATH,
          config.remappedProcessorPath().stream().map(Path::toFile).toList());
      LOG.fine(() -> "[cache] ANNOTATION_PROCESSOR_PATH=%s".formatted(config.processorPath()));
    }
  }

  private FileAnalysis runTask(
      final DiagnosticCollector<JavaFileObject> collector,
      final List<String> options,
      final JavaFileObject sourceFile,
      final CompileMode mode)
      throws IOException {
    final var task =
        (JavacTask) compiler.getTask(null, this.fm, collector, options, null, List.of(sourceFile));
    final var it = task.parse().iterator();
    final CompilationUnitTree cu = it.hasNext() ? it.next() : null;
    task.analyze();
    if (mode == CompileMode.FULL) {
      task.generate();
    }
    final var trees = Trees.instance(task);
    if (cu != null) {
      final var t = Stopwatch.start();
      final List<SemanticToken> semanticTokens = TokenScanner.scan(trees, cu);
      LOG.fine(() -> "[tokens] %d tokens %dms".formatted(semanticTokens.size(), t.elapsedMs()));
      return new FileAnalysis(trees, cu, semanticTokens);
    }

    return new FileAnalysis(trees, cu, null);
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
      final ModuleConfig config, final List<String> compilerArgs, final CompileMode mode) {
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

  static List<String> modeCompilerArgs(final List<String> args, final CompileMode mode) {
    return mode == CompileMode.FULL
        ? args
        : args.stream().filter(ModuleCompiler::isInteractiveCompilerArg).toList();
  }

  private static boolean isInteractiveCompilerArg(final String arg) {
    return !arg.startsWith("-Xplugin:") && !arg.startsWith("-Xep");
  }
}
