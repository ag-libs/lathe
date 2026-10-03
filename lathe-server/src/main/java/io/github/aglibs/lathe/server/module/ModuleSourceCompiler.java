package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.schema.AnalysisMode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

public final class ModuleSourceCompiler implements JavaSourceCompiler, AutoCloseable {

  private static final Logger LOG = Logger.getLogger(ModuleSourceCompiler.class.getName());
  private static final String PATCH_MODULE = "--patch-module";
  private static final String MODULE_SOURCE_PATH = "--module-source-path";
  private static final String SYSTEM = "--system";

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
      this.compilerArgs =
          processPatchModules(
              hoistFileManagerOptions(dropForkedLauncherArgs(config.compilerArgs()), fm),
              fm,
              tempDir);
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
  public CompilerResult compileBatch(
      final List<TransientSource> sources, final CancelChecker cancelChecker) {
    final var options = buildOptions(config, compilerArgs, CompileMode.FULL);
    final Map<Path, String> uriByTempFile = writeTempSources(sources, cancelChecker);
    try {
      return runner.compileBatch(javaFilesOf(uriByTempFile), options, cancelChecker);
    } finally {
      IOUtil.unchecked(fm::flush);
    }
  }

  @Override
  public List<TransientAnalysis> analyzeBatch(
      final List<TransientSource> sources, final CancelChecker cancelChecker) {
    final var options = buildOptions(config, compilerArgs, CompileMode.FAST);
    final Map<Path, String> uriByTempFile = writeTempSources(sources, cancelChecker);
    final List<AttributedFileAnalysis> analyses =
        runner.analyzeBatch(javaFilesOf(uriByTempFile), options, cancelChecker);
    return analyses.stream().map(analysis -> toTransientAnalysis(analysis, uriByTempFile)).toList();
  }

  // Analyze the target with its siblings, returning only the target's diagnostics and tree.
  @Override
  public CompilerResult diagnoseInBatch(
      final List<TransientSource> sources,
      final String targetUri,
      final CancelChecker cancelChecker) {
    final var options = buildOptions(config, compilerArgs, CompileMode.FAST);
    final Map<Path, String> uriByTempFile = writeTempSources(sources, cancelChecker);
    final JavacRunner.BatchAnalysis batch =
        runner.analyzeBatchDiagnosed(javaFilesOf(uriByTempFile), options, cancelChecker);
    final AttributedFileAnalysis targetAnalysis =
        batch.analyses().stream()
            .filter(analysis -> targetUri.equals(uriByTempFile.get(sourcePathOf(analysis))))
            .findFirst()
            .orElseGet(AttributedFileAnalysis::diagnosticsOnly);
    final List<Diagnostic<? extends JavaFileObject>> targetDiagnostics =
        batch.diagnostics().stream()
            .filter(diagnostic -> targetUri.equals(uriByTempFile.get(sourcePathOf(diagnostic))))
            .toList();
    return new CompilerResult(targetDiagnostics, targetAnalysis, Set.of());
  }

  private static TransientAnalysis toTransientAnalysis(
      final AttributedFileAnalysis analysis, final Map<Path, String> uriByTempFile) {
    return new TransientAnalysis(uriByTempFile.get(sourcePathOf(analysis)), analysis);
  }

  private static Path sourcePathOf(final AttributedFileAnalysis analysis) {
    return analysis.tree() == null
        ? null
        : Path.of(analysis.tree().getSourceFile().toUri()).normalize();
  }

  private static Path sourcePathOf(final Diagnostic<? extends JavaFileObject> diagnostic) {
    return diagnostic.getSource() == null
        ? null
        : Path.of(diagnostic.getSource().toUri()).normalize();
  }

  // Write each source to a temp file, keyed by normalized temp path -> original uri, so callers can
  // map compiler outputs back to their source.
  private Map<Path, String> writeTempSources(
      final List<TransientSource> sources, final CancelChecker cancelChecker) {
    final Map<Path, String> uriByTempFile = new LinkedHashMap<>();
    for (final var source : sources) {
      cancelChecker.checkCanceled();
      uriByTempFile.put(writeTempFile(source.uri(), source.content()).normalize(), source.uri());
    }

    return uriByTempFile;
  }

  private Iterable<? extends JavaFileObject> javaFilesOf(final Map<Path, String> uriByTempFile) {
    return fm.getJavaFileObjects(uriByTempFile.keySet().toArray(Path[]::new));
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
    final Path genRoot = config.generatedSourcesDir();
    if (filePath.startsWith(genRoot)) {
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
    // MODULE_SYSTEM has no bytecode mirror: write throwaway output under the temp dir, not .lathe/.
    final var classOutput =
        config.analysisMode() == AnalysisMode.MODULE_SYSTEM
            ? tempDir.resolve("classes")
            : config.latheClassesDir();
    Files.createDirectories(classOutput);
    fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classOutput));
    LOG.fine(() -> "[cache] CLASS_OUTPUT=%s".formatted(classOutput));

    final var genSourcesDir = config.generatedSourcesDir();
    Files.createDirectories(genSourcesDir);
    fm.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(genSourcesDir));
    LOG.fine(() -> "[cache] SOURCE_OUTPUT=%s".formatted(genSourcesDir));

    // MODULE_SYSTEM resolves dependencies from the host JDK's module system plus the --patch-module
    // overlay, so it sets no class/module path (no mirror) — unlike the Maven classpath model.
    if (config.analysisMode() == AnalysisMode.MODULE_SYSTEM) {
      return;
    }

    // External output (OpenJDK build tools) resolves siblings from the build's own compiled dir,
    // added raw — it is not mirrored into .lathe/, so it must not be remapped like Maven outputs.
    final var externalOutput =
        config.externalOutput() ? Stream.of(config.outputDir()) : Stream.<Path>empty();
    final var classpath =
        Stream.of(Stream.of(classOutput), externalOutput, config.remappedClasspath().stream())
            .flatMap(stream -> stream)
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

  // Reused file manager: javac rejects re-applying --module-source-path, so set these once and drop
  // them from the per-compile options (keeping any the file manager doesn't accept).
  private static List<String> hoistFileManagerOptions(
      final List<String> args, final StandardJavaFileManager fm) {
    return consumeFileManagerFlags(
        args, Set.of(MODULE_SOURCE_PATH, SYSTEM), (flag, value) -> applyOption(fm, flag, value));
  }

  // Repoint each patched module at tempDir so the edited buffer overlays it.
  private static List<String> processPatchModules(
      final List<String> args, final StandardJavaFileManager fm, final Path tempDir) {
    return consumeFileManagerFlags(
        args, Set.of(PATCH_MODULE), (flag, spec) -> patchToTempDir(fm, spec, tempDir));
  }

  private static boolean patchToTempDir(
      final StandardJavaFileManager fm, final String spec, final Path tempDir) {
    final int eq = spec.indexOf('=');
    if (eq > 0) {
      applyOption(fm, PATCH_MODULE, "%s=%s".formatted(spec.substring(0, eq), tempDir));
    }
    return true;
  }

  private static boolean applyOption(
      final StandardJavaFileManager fm, final String option, final String value) {
    return fm.handleOption(option, List.of(value).iterator());
  }

  // Feeds each matched `<flag> <value>`/`<flag>=<value>` pair to handler; a false result keeps the
  // pair in the returned args.
  private static List<String> consumeFileManagerFlags(
      final List<String> args, final Set<String> flags, final BiPredicate<String, String> handler) {
    final var remaining = new ArrayList<String>(args.size());
    final var it = args.iterator();
    while (it.hasNext()) {
      final var arg = it.next();
      final String inlineFlag =
          flags.stream().filter(flag -> arg.startsWith(flag + "=")).findFirst().orElse(null);
      final boolean spaced = inlineFlag == null && flags.contains(arg) && it.hasNext();
      if (inlineFlag == null && !spaced) {
        remaining.add(arg);
        continue;
      }

      final String flag = inlineFlag != null ? inlineFlag : arg;
      final String value = inlineFlag != null ? arg.substring(flag.length() + 1) : it.next();
      if (!handler.test(flag, value)) {
        remaining.add(arg);
        if (inlineFlag == null) {
          remaining.add(value);
        }
      }
    }
    return List.copyOf(remaining);
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

    // A FULL compile writes .class files into .lathe/, overwriting the Maven-mirrored bytecode that
    // the debugger replays. javac's default without -g omits the LocalVariableTable, so those
    // classes would keep line numbers (breakpoints bind) but lose locals -- the Variables view and
    // local evaluation then fail even when the project's own build emits full debug info. Force -g
    // so the replayed bytecode always carries locals (DB-5). FAST/OPEN only analyze, never
    // generate.
    if (mode == CompileMode.FULL) {
      opts.add("-g");
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

  // -J flags forward JVM options to a forked javac executable (fork=true); the in-process javac API
  // has no launcher to receive them and rejects them as invalid flags, where Maven's own non-forked
  // compiler would ignore them. Lost JVM access can be restored via LATHE_JVM_OPTS.
  static List<String> dropForkedLauncherArgs(final List<String> args) {
    final Map<Boolean, List<String>> partitioned =
        args.stream().collect(Collectors.partitioningBy(arg -> arg.startsWith("-J")));
    final List<String> dropped = partitioned.get(true);
    if (!dropped.isEmpty()) {
      LOG.info(
          () ->
              "[compile] dropped %d forked-javac -J option(s) %s; set LATHE_JVM_OPTS to extend in-process access"
                  .formatted(dropped.size(), dropped));
    }

    return List.copyOf(partitioned.get(false));
  }
}
