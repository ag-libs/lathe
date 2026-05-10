package io.github.aglibs.lathe.server;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Stopwatch;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

final class ModuleCompiler implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(ModuleCompiler.class.getName());
  private static final int MAX_CACHED = 100;
  private static final String PATCH_MODULE = "--patch-module";
  private static final String PATCH_MODULE_EQ = PATCH_MODULE + "=";

  enum Mode {
    FAST("compile"),
    OPEN("open"),
    FULL("save");

    final String tag;

    Mode(final String tag) {
      this.tag = tag;
    }
  }

  private record CachedManager(StandardJavaFileManager fm, Path td, List<String> compilerArgs) {
    void close() {
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
  }

  private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

  private final LinkedHashMap<ModuleParams, CachedManager> cache =
      new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<ModuleParams, CachedManager> eldest) {
          if (size() > MAX_CACHED) {
            eldest.getValue().close();
            return true;
          }
          return false;
        }
      };

  CompilationResult compile(
      final String uri, final String content, final ModuleParams params, final Mode mode)
      throws IOException {
    final CachedManager cm = getOrCreate(params);
    final DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
    final Path filePath = Path.of(URI.create(uri));
    final Path sourceRoot =
        params.sourceRoots().stream()
            .filter(filePath::startsWith)
            .max(Comparator.comparingInt(Path::getNameCount))
            .orElseThrow(() -> new IllegalStateException("no source root for " + uri));

    final var tempFile = cm.td().resolve(sourceRoot.relativize(filePath));
    Files.createDirectories(tempFile.getParent());
    Files.writeString(tempFile, content);

    final var options = buildOptions(params, cm, mode);
    LOG.fine(() -> "[%s] td=%s root=%s opts=%s".formatted(mode.tag, cm.td(), sourceRoot, options));
    final JavaFileObject file = cm.fm().getJavaFileObjects(tempFile).iterator().next();
    try {
      final CompilationTaskContext context = runTask(cm.fm(), collector, options, file, mode);
      return new CompilationResult(collector.getDiagnostics(), context);
    } finally {
      if (mode == Mode.FULL) {
        cm.fm().flush();
      }
    }
  }

  void invalidate() {
    cache.values().forEach(CachedManager::close);
    cache.clear();
  }

  @Override
  public void close() {
    invalidate();
  }

  private CachedManager getOrCreate(final ModuleParams params) throws IOException {
    var cm = cache.get(params);
    if (cm == null) {
      cm = createCachedManager(params);
      cache.put(params, cm);
    }
    return cm;
  }

  private CachedManager createCachedManager(final ModuleParams params) throws IOException {
    final var td = Files.createTempDirectory("lathe-");
    final var fm = compiler.getStandardFileManager(null, null, null);

    final var classesDir = params.latheClassesDir();
    Files.createDirectories(classesDir);
    fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classesDir.toFile()));
    LOG.fine(() -> "[cache] CLASS_OUTPUT=%s".formatted(classesDir));

    final var genSourcesDir = params.generatedSourcesDir();
    Files.createDirectories(genSourcesDir);
    fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(genSourcesDir.toFile()));
    LOG.fine(() -> "[cache] SOURCE_OUTPUT=%s".formatted(genSourcesDir));

    final var classpath = params.remappedClasspath();
    if (!classpath.isEmpty()) {
      fm.setLocation(StandardLocation.CLASS_PATH, classpath.stream().map(Path::toFile).toList());
      LOG.fine(() -> "[cache] CLASS_PATH=%s".formatted(classpath));
    }
    final var modulepath = params.remappedModulepath();
    if (!modulepath.isEmpty()) {
      fm.setLocation(StandardLocation.MODULE_PATH, modulepath.stream().map(Path::toFile).toList());
      LOG.fine(() -> "[cache] MODULE_PATH=%s".formatted(modulepath));
    }
    if (!params.processorPath().isEmpty()) {
      fm.setLocation(
          StandardLocation.ANNOTATION_PROCESSOR_PATH,
          params.remappedProcessorPath().stream().map(Path::toFile).toList());
      LOG.fine(() -> "[cache] ANNOTATION_PROCESSOR_PATH=%s".formatted(params.processorPath()));
    }

    // Process --patch-module entries once at creation, pointing at the dedicated temp dir.
    // Filtered out of per-task options so handleOption is never called twice for the same module.
    final var compilerArgs = processPatchModules(params.compilerArgs(), fm, td);
    return new CachedManager(fm, td, compilerArgs);
  }

  private CompilationTaskContext runTask(
      final JavaFileManager fileManager,
      final DiagnosticCollector<JavaFileObject> collector,
      final List<String> options,
      final JavaFileObject sourceFile,
      final Mode mode)
      throws IOException {
    final var task =
        (JavacTask)
            compiler.getTask(null, fileManager, collector, options, null, List.of(sourceFile));
    final var it = task.parse().iterator();
    final CompilationUnitTree cu = it.hasNext() ? it.next() : null;
    task.analyze();
    if (mode == Mode.FULL) {
      task.generate();
    }
    final var trees = Trees.instance(task);
    if (cu != null) {
      final var t = Stopwatch.start();
      final List<SemanticToken> semanticTokens = SemanticTokensScanner.scan(trees, cu);
      LOG.fine(() -> "[tokens] %d tokens %dms".formatted(semanticTokens.size(), t.elapsedMs()));
      return new CompilationTaskContext(trees, cu, semanticTokens);
    }

    return new CompilationTaskContext(trees, cu, null);
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
      final ModuleParams params, final CachedManager cm, final Mode mode) {
    final var opts = new ArrayList<String>();
    if (params.release() != null && !params.release().isBlank()) {
      opts.add("--release");
      opts.add(params.release());
    }
    opts.add("-encoding");
    opts.add(params.encoding());
    if (params.parameters()) {
      opts.add("-parameters");
    }
    if (params.enablePreview()) {
      opts.add("--enable-preview");
    }
    opts.addAll(modeCompilerArgs(cm.compilerArgs(), mode));
    if (mode == Mode.FAST || mode == Mode.OPEN) {
      opts.add("-proc:none");
    }
    return opts;
  }

  static List<String> modeCompilerArgs(final List<String> args, final Mode mode) {
    return mode == Mode.FULL
        ? args
        : args.stream().filter(ModuleCompiler::isInteractiveCompilerArg).toList();
  }

  private static boolean isInteractiveCompilerArg(final String arg) {
    return !arg.startsWith("-Xplugin:") && !arg.startsWith("-Xep");
  }
}
