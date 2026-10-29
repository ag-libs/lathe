package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.schema.AnalysisMode;
import io.github.aglibs.lathe.server.LatheUri;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.BatchWrite;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.CompilerResult;
import io.github.aglibs.lathe.server.analysis.JavaSourceCompiler;
import io.github.aglibs.lathe.server.analysis.JavacDiagnosticMapper;
import io.github.aglibs.lathe.server.analysis.TransientAnalysis;
import io.github.aglibs.lathe.server.analysis.TransientSource;
import java.io.File;
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
  // Full compiles' processor path (the only compiles that run processors): the build's, else the
  // classpath, where javac finds them by default. generate() closes the loader javac opens over it.
  private final List<Path> processorPath;
  private final LombokProcessors lombok;

  ModuleSourceCompiler(
      final ModuleSourceConfig config, final CompilationAdmission compilationAdmission) {
    this.config = config;
    try {
      this.tempDir = Files.createTempDirectory("lathe-");
      this.fm = JavaSourceCompiler.createFileManager();
      final List<Path> classpath = initLocations();
      this.processorPath =
          config.processorPath().isEmpty() ? classpath : config.remappedProcessorPath();
      LOG.fine(() -> "[cache] ANNOTATION_PROCESSOR_PATH (full)=%s".formatted(processorPath));
      this.lombok =
          LatheFlags.PROC_NONE.equals(config.proc())
              ? LombokProcessors.none()
              : LombokProcessors.find(processorPath, fm.getClass().getClassLoader());
      this.runner = new JavacRunner(fm, compilationAdmission, lombok);
      this.compilerArgs =
          processPatchModules(
              hoistFileManagerOptions(dropForkedLauncherArgs(config.compilerArgs()), fm), fm);
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
    final var options = buildOptions(mode);
    LOG.fine(() -> "[compile:%s] tempDir=%s opts=%s".formatted(mode.tag, tempDir, options));
    final JavaFileObject jfo = fm.getJavaFileObjects(tempFile).iterator().next();
    return runner.run(jfo, options, cancelChecker);
  }

  @Override
  public BatchWrite compileBatch(
      final List<TransientSource> sources, final CancelChecker cancelChecker) {
    final var options = buildOptions(CompileMode.FULL);
    final Map<Path, String> uriByTempFile = writeTempSources(sources, cancelChecker);
    JavaSourceCompiler.setProcessorPath(fm, processorPath);
    try {
      final CompilerResult result =
          runner.compileBatch(javaFilesOf(uriByTempFile), options, cancelChecker);
      final Map<String, String> contentByUri =
          sources.stream()
              .collect(Collectors.toMap(TransientSource::uri, TransientSource::content));
      final Map<String, List<Diagnostic<? extends JavaFileObject>>> bySource =
          result.diagnostics().stream()
              .filter(diagnostic -> uriByTempFile.containsKey(sourcePathOf(diagnostic)))
              .collect(
                  Collectors.groupingBy(diagnostic -> uriByTempFile.get(sourcePathOf(diagnostic))));
      // An error with no source (-Werror's "warnings found") fails the whole task: javac wrote no
      // class, so every source carries it, or the batch would look clean.
      final List<Diagnostic<? extends JavaFileObject>> taskErrors =
          result.diagnostics().stream().filter(ModuleSourceCompiler::isTaskError).toList();
      return new BatchWrite(
          result.writtenBinaryNames(),
          contentByUri.keySet().stream()
              .filter(uri -> bySource.containsKey(uri) || !taskErrors.isEmpty())
              .collect(
                  Collectors.toUnmodifiableMap(
                      uri -> uri,
                      uri ->
                          JavacDiagnosticMapper.filterAndMap(
                              Stream.concat(
                                      bySource.getOrDefault(uri, List.of()).stream(),
                                      taskErrors.stream())
                                  .toList(),
                              contentByUri.get(uri)))));
    } finally {
      JavaSourceCompiler.setProcessorPath(fm, List.of());
      IOUtil.unchecked(fm::flush);
    }
  }

  @Override
  public List<TransientAnalysis> analyzeBatch(
      final List<TransientSource> sources, final CancelChecker cancelChecker) {
    final var options = buildOptions(CompileMode.FAST);
    final Map<Path, String> uriByTempFile = writeTempSources(sources, cancelChecker);
    final List<AttributedFileAnalysis> analyses =
        runner.analyzeBatch(javaFilesOf(uriByTempFile), options, cancelChecker);
    return analyses.stream().map(analysis -> toTransientAnalysis(analysis, uriByTempFile)).toList();
  }

  // Analyze open documents in one task, one result per source as its own OPEN compile would give.
  @Override
  public List<CompilerResult> analyzeOpenBatch(
      final List<TransientSource> sources, final CancelChecker cancelChecker) {
    final var options = buildOptions(CompileMode.OPEN);
    final Map<Path, String> uriByTempFile = writeTempSources(sources, cancelChecker);
    final JavacRunner.BatchAnalysis batch =
        runner.analyzeBatchDiagnosed(javaFilesOf(uriByTempFile), options, true, cancelChecker);
    return sources.stream().map(source -> resultFor(batch, uriByTempFile, source.uri())).toList();
  }

  private static CompilerResult resultFor(
      final JavacRunner.BatchAnalysis batch,
      final Map<Path, String> uriByTempFile,
      final String uri) {
    final AttributedFileAnalysis analysis =
        batch.analyses().stream()
            .filter(candidate -> uri.equals(uriByTempFile.get(sourcePathOf(candidate))))
            .findFirst()
            .orElseGet(AttributedFileAnalysis::diagnosticsOnly);
    final List<Diagnostic<? extends JavaFileObject>> diagnostics =
        batch.diagnostics().stream()
            .filter(diagnostic -> uri.equals(uriByTempFile.get(sourcePathOf(diagnostic))))
            .toList();
    final boolean parsedCleanly =
        analysis.tree() != null
            && !batch.parseFailed().contains(analysis.tree().getSourceFile().toUri());
    return new CompilerResult(diagnostics, analysis, Set.of(), parsedCleanly);
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

  private static boolean isTaskError(final Diagnostic<? extends JavaFileObject> diagnostic) {
    return diagnostic.getSource() == null && diagnostic.getKind() == Diagnostic.Kind.ERROR;
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
    closeLogged(fm::close, "close file manager");
    closeLogged(lombok::close, "close Lombok's class loader");
    closeLogged(() -> FileUtil.deleteDir(tempDir), "delete temp dir %s".formatted(tempDir));
  }

  private static void closeLogged(final IOUtil.IORunnable action, final String what) {
    try {
      action.run();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[cache] failed to %s".formatted(what));
    }
  }

  // Returns the classpath it set, empty when it sets none.
  private List<Path> initLocations() throws IOException {
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
      return List.of();
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

    return classpath;
  }

  // Reused file manager: javac rejects re-applying --module-source-path, so set these once and drop
  // them from the per-compile options (keeping any the file manager doesn't accept).
  private static List<String> hoistFileManagerOptions(
      final List<String> args, final StandardJavaFileManager fm) {
    return consumeFileManagerFlags(
        args, Set.of(MODULE_SOURCE_PATH, SYSTEM), (flag, value) -> applyOption(fm, flag, value));
  }

  // Repoint each patched module at tempDir (edited buffers, first so they win) plus the module's
  // compiled output, so cross-file references and module-info exports/opens see the module's own
  // packages — which the overlay alone (just the open file) does not contain.
  private List<String> processPatchModules(
      final List<String> args, final StandardJavaFileManager fm) {
    final String patch = tempDir + File.pathSeparator + config.outputDir();
    return consumeFileManagerFlags(
        args, Set.of(PATCH_MODULE), (flag, spec) -> patchModule(fm, spec, patch));
  }

  private static boolean patchModule(
      final StandardJavaFileManager fm, final String spec, final String patch) {
    final int eq = spec.indexOf('=');
    if (eq > 0) {
      applyOption(fm, PATCH_MODULE, "%s=%s".formatted(spec.substring(0, eq), patch));
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

  private List<String> buildOptions(final CompileMode mode) {
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

    // A batch write puts .class files into .lathe/, overwriting the Maven-mirrored bytecode that
    // the debugger replays. javac's default without -g omits the LocalVariableTable, so those
    // classes would keep line numbers (breakpoints bind) but lose locals -- the Variables view and
    // local evaluation then fail even when the project's own build emits full debug info. Force -g
    // so the replayed bytecode always carries locals (DB-5). FAST/OPEN only analyze, never
    // generate.
    if (mode == CompileMode.FULL) {
      opts.add("-g");
    }

    opts.addAll(modeCompilerArgs(compilerArgs, mode));
    // A write runs processors only where the build does: with <proc>none</proc> a module's own
    // processor, visible through its class mirror, must not run over its sources. An analysis runs
    // none but Lombok, which JavacRunner hands its tasks.
    if (mode == CompileMode.FULL ? LatheFlags.PROC_NONE.equals(config.proc()) : !lombok.present()) {
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
  // compiler would ignore them. Sync carries their --add-exports/--add-opens over to the server JVM
  // via .lathe/jvm.args; anything else can be restored via LATHE_JVM_OPTS.
  static List<String> dropForkedLauncherArgs(final List<String> args) {
    final Map<Boolean, List<String>> partitioned =
        args.stream().collect(Collectors.partitioningBy(arg -> arg.startsWith("-J")));
    final List<String> dropped = partitioned.get(true);
    if (!dropped.isEmpty()) {
      LOG.info(
          () ->
              "[compile] dropped %d forked-javac -J option(s) %s; module access is applied via .lathe/jvm.args, others need LATHE_JVM_OPTS"
                  .formatted(dropped.size(), dropped));
    }

    return List.copyOf(partitioned.get(false));
  }
}
