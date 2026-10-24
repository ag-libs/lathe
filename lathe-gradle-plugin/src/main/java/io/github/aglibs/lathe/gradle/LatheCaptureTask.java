package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.schema.AnalysisMode;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.install.CompileCapture;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
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

  @Input
  public abstract Property<String> getSourceTree();

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

  @InputFiles
  @Optional
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getGeneratedSourcesDir();

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
      CompileCapture.mirror(moduleDir, config);
    } finally {
      LatheLock.release(lock);
    }
  }

  private ModuleConfigData moduleConfig() {
    final List<String> sourceRoots = paths(getSourceRoots());
    final List<String> classpath = paths(getClasspath());
    final Map<Boolean, List<String>> split =
        isModular(sourceRoots)
            ? ModulePathSplit.split(classpath)
            : Map.of(true, List.of(), false, classpath);
    final List<String> processorPath = paths(getProcessorPath());
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
        false);
  }

  private boolean isModular(final List<String> sourceRoots) {
    return getInferModulePath().get()
        && sourceRoots.stream()
            .anyMatch(root -> Files.isRegularFile(Path.of(root, "module-info.java")));
  }

  private static List<String> paths(final FileCollection files) {
    return files.getFiles().stream().map(File::getPath).toList();
  }
}
