package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.schema.AnalysisMode;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.install.CompileCapture;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

// Captures one successful compile into .lathe/<module>/: its lsp-params, the bytecode and
// annotation-processor output mirrors, and the compile stamps, as LatheCompiler does on Maven. It
// finalizes the compile, so a failed compile skips it and the last good capture stays; its outputs
// are the mirror, so a wiped .lathe/ is refilled even when the compile is up to date.
@DisableCachingByDefault(because = "The mirror holds this machine's absolute paths.")
public abstract class LatheCaptureTask extends DefaultTask {

  private static final String PROC = "-proc:";
  private static final String PROC_NONE = "none";
  private static final String CLASS_SUFFIX = ".class";

  @Input
  public abstract Property<String> getSourceTree();

  // Whether the source set is test code, by Gradle's own convention (LatheProjectPlugin).
  @Input
  public abstract Property<Boolean> getTestCode();

  @Internal
  public abstract DirectoryProperty getModuleDir();

  // The source files make the task skip a source set with none, as the compile does; the roots
  // are what lsp-params records.
  @InputFiles
  @SkipWhenEmpty
  @IgnoreEmptyDirectories
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ConfigurableFileCollection getSources();

  @Internal
  public abstract ConfigurableFileCollection getSourceRoots();

  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getClassesDir();

  // Read, not tracked: a build may point its test compile at this directory too (Apache Fineract
  // points every compile at main's), which Gradle would see as a second writer of an input; the
  // plugin orders that compile after the main capture instead. The classes, an input, change
  // whenever the compile writes new sources here.
  @Internal
  public abstract DirectoryProperty getGeneratedSourcesDir();

  // The source set's processed resources, mirrored with the classes as Maven's output holds them.
  // A task that writes into it after the classes runs after the capture, which the classes task
  // depends on.
  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getResourcesDir();

  @Classpath
  public abstract ConfigurableFileCollection getClasspath();

  @Classpath
  public abstract ConfigurableFileCollection getProcessorPath();

  @Input
  public abstract ListProperty<String> getCompilerArgs();

  @Input
  @Optional
  public abstract Property<String> getRelease();

  @Input
  @Optional
  public abstract Property<String> getEncoding();

  @Input
  public abstract Property<Boolean> getInferModulePath();

  @OutputDirectory
  public Provider<Directory> getClassesMirror() {
    return getModuleDir().dir(getSourceTree());
  }

  @OutputDirectory
  public Provider<Directory> getGeneratedSourcesMirror() {
    return getModuleDir().dir(getSourceTree().map(LatheLayout::generatedSourcesDir));
  }

  @OutputFile
  public Provider<RegularFile> getParamsFile() {
    return getModuleDir().file(getSourceTree().map(LatheLayout::paramsFileName));
  }

  @OutputFile
  public Provider<RegularFile> getStampsFile() {
    return getModuleDir().file(getSourceTree().map(LatheLayout::compiledStampsFileName));
  }

  // Asked for as the task starts, so the build holds .lathe/lathe.lock from here until it ends.
  @Internal
  public abstract Property<LatheBuildLockService> getBuildLock();

  @TaskAction
  public void capture() throws IOException {
    getBuildLock().get();
    final Path moduleDir = getModuleDir().get().getAsFile().toPath();
    final ModuleConfigData config = moduleConfig();
    final Path lock = LatheLayout.moduleLockFile(moduleDir);
    LatheLock.acquire(lock);
    try {
      CompileCapture.writeParams(moduleDir, config);
      if (!compiled(config)) {
        getLogger()
            .lifecycle(
                "[lathe] {} compile failed; kept the last good capture", getSourceTree().get());
        return;
      }

      CompileCapture.mirror(
          moduleDir, config, List.of(getResourcesDir().get().getAsFile().toPath()));
    } finally {
      LatheLock.release(lock);
    }
  }

  // Whether the compile wrote a class for every source it compiled. A compile with errors writes
  // none for what it compiled, and with failOnError off (a personal setup's init script) Gradle
  // runs
  // this task as after a good one, so the mirror would record sources as built that have no
  // classes, and the server would never compile them: keep the last good capture, as Maven's shim
  // does. The compile's own sources, excludes applied: a file the build leaves out has no class. A
  // source declaring only a type of another name, or in a folder that is not its package, reads as
  // failed too, which costs the server a compile of its own, never a wrong mirror. module-info and
  // package-info may compile to no class.
  private boolean compiled(final ModuleConfigData config) {
    final Path classes = Path.of(config.outputDir());
    final List<Path> roots = config.sourceRoots().stream().map(Path::of).toList();
    for (final File file : getSources().getFiles()) {
      final Path source = file.toPath();
      final java.util.Optional<Path> root = sourceRoot(roots, source);
      if (!compilesToItsClass(source) || root.isEmpty()) {
        continue;
      }

      final Path classFile =
          classes
              .resolve(root.get().relativize(source.getParent()))
              .resolve("%s%s".formatted(FileUtil.javaTypeName(source), CLASS_SUFFIX));
      if (!Files.isRegularFile(classFile)) {
        return false;
      }
    }

    return true;
  }

  // The deepest source root holding source, as source roots may nest.
  private static java.util.Optional<Path> sourceRoot(final List<Path> roots, final Path source) {
    return roots.stream()
        .filter(source::startsWith)
        .max(Comparator.comparingInt(Path::getNameCount));
  }

  private static boolean compilesToItsClass(final Path path) {
    final String name = path.getFileName().toString();
    return FileUtil.isJavaFile(path)
        && !name.equals(LatheLayout.MODULE_INFO_JAVA)
        && !name.equals(LatheLayout.PACKAGE_INFO_JAVA);
  }

  private ModuleConfigData moduleConfig() {
    final List<String> sourceRoots = GradleFiles.paths(getSourceRoots());
    final List<String> classpath = GradleFiles.paths(getClasspath());
    final Map<Boolean, List<String>> split =
        isModular(sourceRoots)
            ? ModulePathSplit.split(classpath)
            : Map.of(true, List.of(), false, classpath);
    final List<String> processorPath = GradleFiles.paths(getProcessorPath());
    final List<String> compilerArgs = getCompilerArgs().get();
    // Gradle runs no processor when the processor path is empty, and passes -proc:none to say so;
    // without it the server's javac would look for processors on the classpath.
    final boolean noProcessors =
        processorPath.isEmpty() && compilerArgs.stream().noneMatch(arg -> arg.startsWith(PROC));
    return new ModuleConfigData(
        getSourceTree().get(),
        getClassesDir().get().getAsFile().getPath(),
        getGeneratedSourcesDir().map(dir -> dir.getAsFile().getPath()).getOrNull(),
        sourceRoots,
        split.get(false),
        split.get(true),
        processorPath,
        getRelease().getOrNull(),
        getEncoding().getOrNull(),
        false,
        false,
        noProcessors ? PROC_NONE : null,
        noProcessors
            ? Stream.concat(compilerArgs.stream(), Stream.of(PROC + PROC_NONE)).toList()
            : compilerArgs,
        AnalysisMode.CLASSPATH,
        false,
        getTestCode().get());
  }

  private boolean isModular(final List<String> sourceRoots) {
    return getInferModulePath().get()
        && sourceRoots.stream()
            .anyMatch(root -> Files.isRegularFile(Path.of(root, LatheLayout.MODULE_INFO_JAVA)));
  }
}
