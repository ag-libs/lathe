package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;

// Applied to every project by LatheSettingsPlugin: a Java project gets a capture task per main and
// test source set, finalizing its compile. The compile itself is left untouched, so its build-cache
// key is the same with or without Lathe.
public class LatheProjectPlugin implements Plugin<Project> {

  @Override
  public void apply(final Project project) {
    project.getPlugins().withType(JavaPlugin.class, java -> registerCaptures(project));
  }

  private static void registerCaptures(final Project project) {
    final var rootDir = project.getRootDir().toPath();
    final Path moduleDir =
        rootDir
            .resolve(LatheLayout.LATHE_DIR)
            .resolve(rootDir.relativize(project.getProjectDir().toPath()));
    final SourceSetContainer sourceSets =
        project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
    registerCapture(
        project,
        sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME),
        "latheCaptureMain",
        LatheLayout.CLASSES_DIR,
        moduleDir);
    registerCapture(
        project,
        sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME),
        "latheCaptureTest",
        LatheLayout.TEST_CLASSES_DIR,
        moduleDir);
  }

  private static void registerCapture(
      final Project project,
      final SourceSet sourceSet,
      final String name,
      final String sourceTree,
      final Path moduleDir) {
    final TaskProvider<JavaCompile> compile =
        project.getTasks().named(sourceSet.getCompileJavaTaskName(), JavaCompile.class);
    final TaskProvider<LatheCaptureTask> capture =
        project
            .getTasks()
            .register(
                name,
                LatheCaptureTask.class,
                task -> {
                  final JavaCompile javac = compile.get();
                  task.dependsOn(compile);
                  task.getSourceTree().set(sourceTree);
                  task.getModuleDir().set(moduleDir.toFile());
                  task.getSources().from(sourceSet.getJava());
                  task.getSourceRoots().from(sourceSet.getJava().getSourceDirectories());
                  task.getClassesDir().set(javac.getDestinationDirectory());
                  task.getGeneratedSourcesDir()
                      .set(javac.getOptions().getGeneratedSourceOutputDirectory());
                  task.getClasspath().from(javac.getClasspath());
                  // A Callable may return null, Gradle's "no processor path".
                  task.getProcessorPath()
                      .from(
                          (Callable<FileCollection>)
                              () -> javac.getOptions().getAnnotationProcessorPath());
                  task.getCompilerArgs()
                      .set(project.provider(() -> javac.getOptions().getAllCompilerArgs()));
                  task.getRelease()
                      .set(
                          javac
                              .getOptions()
                              .getRelease()
                              .map(String::valueOf)
                              .orElse(
                                  project.provider(
                                      () ->
                                          JavaVersion.toVersion(javac.getSourceCompatibility())
                                              .getMajorVersion())));
                  task.getEncoding().set(project.provider(() -> javac.getOptions().getEncoding()));
                  task.getInferModulePath().set(javac.getModularity().getInferModulePath());
                });
    compile.configure(javac -> javac.finalizedBy(capture));
  }
}
