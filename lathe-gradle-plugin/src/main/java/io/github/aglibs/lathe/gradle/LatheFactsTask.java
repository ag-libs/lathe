package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

// Writes this project's ProjectFacts, as JSON, for the root's latheSync to merge. The source trees
// are decided here, as the task runs: a configuration-cached build computes the other facts before
// any task generates sources into a source set.
@DisableCachingByDefault(because = "Writing one small file is cheaper than caching it.")
public abstract class LatheFactsTask extends DefaultTask {

  @Input
  public abstract Property<String> getFacts();

  @InputFiles
  @IgnoreEmptyDirectories
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ConfigurableFileCollection getMainSources();

  @InputFiles
  @IgnoreEmptyDirectories
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ConfigurableFileCollection getTestSources();

  // The external libraries the project compiles against, main and test, and their -sources jars:
  // group:name:version to jar, resolved as the task runs. Plain values, which the configuration
  // cache stores on every Gradle version; resolved artifacts it stores only from Gradle 9.
  @Internal
  public abstract MapProperty<String, String> getLibraries();

  @Internal
  public abstract MapProperty<String, String> getLibrarySources();

  @InputFiles
  @PathSensitive(PathSensitivity.ABSOLUTE)
  public abstract ConfigurableFileCollection getLibraryFiles();

  // The main compile's external jars: the classpath a library's sources compile with in the editor.
  @InputFiles
  @PathSensitive(PathSensitivity.ABSOLUTE)
  public abstract ConfigurableFileCollection getLibraryClasspath();

  // What replays the tests beside the test classpath: the test runner and the JUnit launcher.
  @Classpath
  public abstract ConfigurableFileCollection getRunnerClasspath();

  @OutputFile
  public abstract RegularFileProperty getFactsFile();

  // Asked for as the task starts, so the build holds .lathe/lathe.lock from here until it ends.
  @Internal
  public abstract Property<LatheBuildLockService> getBuildLock();

  @TaskAction
  public void write() throws IOException {
    getBuildLock().get();
    final List<String> trees =
        Stream.of(
                Map.entry(LatheLayout.CLASSES_DIR, getMainSources()),
                Map.entry(LatheLayout.TEST_CLASSES_DIR, getTestSources()))
            .filter(tree -> !tree.getValue().isEmpty())
            .map(Map.Entry::getKey)
            .toList();
    final Map<String, String> sources = getLibrarySources().get();
    final List<DependencyFact> libraries =
        getLibraries().get().entrySet().stream()
            .map(
                library ->
                    DependencyFact.of(
                        library.getKey(), library.getValue(), sources.get(library.getKey())))
            .toList();
    final List<String> classpath = paths(getLibraryClasspath());
    final Path file = getFactsFile().get().getAsFile().toPath();
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        Json.toJson(
            Json.fromJson(getFacts().get(), ProjectFacts.class)
                .withResolved(trees, libraries, classpath, paths(getRunnerClasspath()))),
        StandardCharsets.UTF_8);
  }

  private static List<String> paths(final ConfigurableFileCollection files) {
    return files.getFiles().stream().map(File::getPath).toList();
  }
}
