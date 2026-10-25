package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.install.MainLaunchWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

// The module's main-run launch, .lathe/<module>/main-launch.json, derived here since no main run
// happens during a build: the main runtime classpath, split for a modular main by Gradle's own rule
// so a run matches Gradle's JavaExec, on the project's Java launcher. The classpath is no input:
// as one it would make every build that compiles also build each sibling's jar, which only the
// paths are needed of. So the task runs every time, cheaply, and a modular split made before a
// sibling's jar existed is corrected by the next build that has it.
@UntrackedTask(because = "Its classpath is read for its paths, not built.")
public abstract class LatheMainLaunchTask extends DefaultTask {

  @Internal
  public abstract ConfigurableFileCollection getRuntimeClasspath();

  @Internal
  public abstract DirectoryProperty getClassesDir();

  @Input
  public abstract Property<Boolean> getInferModulePath();

  @Input
  public abstract Property<String> getJavaHome();

  // The module directory, relative to the root ("." for the root project): the run's working
  // directory, and where its launch file goes.
  @Input
  public abstract Property<String> getModule();

  @Internal
  public abstract DirectoryProperty getWorkspaceRoot();

  @OutputFile
  public Provider<RegularFile> getMainLaunchFile() {
    return getWorkspaceRoot()
        .file(
            getModule()
                .map(
                    module ->
                        LatheLayout.moduleDir(module)
                            .resolve(LatheLayout.MAIN_LAUNCH_FILE)
                            .toString()));
  }

  // Asked for as the task starts, so the build holds .lathe/lathe.lock from here until it ends.
  @Internal
  public abstract Property<LatheBuildLockService> getBuildLock();

  @TaskAction
  public void write() throws IOException {
    getBuildLock().get();
    final Path root = getWorkspaceRoot().get().getAsFile().toPath();
    final String module = getModule().get();
    final String javaHome = getJavaHome().get();
    final List<String> classpath = GradleFiles.paths(getRuntimeClasspath());
    final Path classesDir = getClassesDir().get().getAsFile().toPath();
    final Path moduleInfo = classesDir.resolve(LatheLayout.MODULE_INFO_CLASS);
    if (!getInferModulePath().get() || !Files.isRegularFile(moduleInfo)) {
      MainLaunchWriter.writeClasspath(root, module, javaHome, classpath);
      return;
    }

    // The module's own classes lead the module path, so `-m module/Main` finds its main class.
    final Map<Boolean, List<String>> split =
        ModulePathSplit.split(
            classpath.stream().filter(entry -> !Path.of(entry).equals(classesDir)).toList());
    MainLaunchWriter.writeModule(
        root,
        module,
        javaHome,
        moduleName(moduleInfo),
        Stream.concat(Stream.of(classesDir.toString()), split.get(true).stream()).toList(),
        split.get(false));
  }

  // From the compiled descriptor, not the source: the name javac recorded.
  private static String moduleName(final Path moduleInfo) throws IOException {
    try (final InputStream in = Files.newInputStream(moduleInfo)) {
      return ModuleDescriptor.read(in).name();
    }
  }
}
