package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.BuildLock;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

// Holds the build lock (.lathe/lathe.lock) from the first task that writes .lathe/ until the build
// finishes, when Gradle closes its shared services. The tasks that write .lathe/ ask for it as they
// start: Gradle creates a service only when a task asks for it.
public abstract class LatheBuildLockService
    implements BuildService<LatheBuildLockService.Params>, AutoCloseable {

  public interface Params extends BuildServiceParameters {
    DirectoryProperty getLatheDir();
  }

  // Null when the build holds none: a first-ever build, before .lathe/ exists.
  private final BuildLock lock;

  public LatheBuildLockService() {
    lock = BuildLock.hold(getParameters().getLatheDir().get().getAsFile().toPath()).orElse(null);
  }

  @Override
  public void close() {
    if (lock != null) {
      lock.close();
    }
  }
}
