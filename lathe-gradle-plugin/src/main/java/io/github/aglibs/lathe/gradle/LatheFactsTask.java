package io.github.aglibs.lathe.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

// Writes this project's ProjectFacts, as JSON, for the root's latheSync to merge.
@DisableCachingByDefault(because = "Writing one small file is cheaper than caching it.")
public abstract class LatheFactsTask extends DefaultTask {

  @Input
  public abstract Property<String> getFacts();

  @OutputFile
  public abstract RegularFileProperty getFactsFile();

  @TaskAction
  public void write() throws IOException {
    final Path file = getFactsFile().get().getAsFile().toPath();
    Files.createDirectories(file.getParent());
    Files.writeString(file, getFacts().get(), StandardCharsets.UTF_8);
  }
}
