package io.github.aglibs.lathe.gradle;

import java.io.File;
import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

// Records a source set's mtimes just before its compile task, which depends on it: an action of
// the compile task itself would change its build cache key, so a compile Lathe's absence cached
// would never be reused with Lathe on.
@UntrackedTask(because = "It records the sources' mtimes as they are on every run.")
public abstract class LatheSourceMtimesTask extends DefaultTask {

  @Internal
  public abstract ConfigurableFileCollection getSourceRoots();

  @Internal
  public abstract RegularFileProperty getMtimesFile();

  @TaskAction
  public void record() throws IOException {
    SourceMtimes.write(
        getSourceRoots().getFiles().stream().map(File::toPath).toList(),
        getMtimesFile().get().getAsFile().toPath());
  }
}
