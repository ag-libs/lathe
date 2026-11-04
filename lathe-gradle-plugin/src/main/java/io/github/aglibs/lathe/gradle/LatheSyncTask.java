package io.github.aglibs.lathe.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

// The editor's sync, for a fresh clone or a wiped .lathe/: it builds every project's facts, or the
// --module ones', and latheWorkspace, which finalizes it, writes the workspace files. A normal
// build refreshes them as well; this command does not depend on one having run.
@DisableCachingByDefault(because = "It only triggers other tasks.")
public abstract class LatheSyncTask extends DefaultTask {

  static final String MODULE_OPTION = "module";

  @Internal
  @Option(
      option = MODULE_OPTION,
      description = "Syncs only this module (its directory); repeatable.")
  public abstract ListProperty<String> getModules();
}
