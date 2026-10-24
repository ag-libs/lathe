package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
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
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
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

  @OutputFile
  public abstract RegularFileProperty getFactsFile();

  @TaskAction
  public void write() throws IOException {
    final List<String> trees =
        Stream.of(
                Map.entry(LatheLayout.CLASSES_DIR, getMainSources()),
                Map.entry(LatheLayout.TEST_CLASSES_DIR, getTestSources()))
            .filter(tree -> !tree.getValue().isEmpty())
            .map(Map.Entry::getKey)
            .toList();
    final Path file = getFactsFile().get().getAsFile().toPath();
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        Json.toJson(Json.fromJson(getFacts().get(), ProjectFacts.class).withTrees(trees)),
        StandardCharsets.UTF_8);
  }
}
