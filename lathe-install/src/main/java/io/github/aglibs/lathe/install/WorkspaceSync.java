package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.ResourceRootData;
import io.github.aglibs.lathe.install.dependency.DependencySource;
import io.github.aglibs.lathe.install.dependency.DependencySourceSync;
import io.github.aglibs.lathe.install.jdk.JdkSource;
import io.github.aglibs.lathe.install.jdk.JdkSourceSync;
import io.github.aglibs.lathe.install.typeindex.JdkTypeIndexSync;
import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Path;
import java.util.List;

// The steps every build front-end's sync shares, in one order: the dependencies' sources and type
// indexes, the JDK's sources and type index, the workspace manifest, and the sync script. A
// front-end gathers the inputs from its build and adds what is its own around this.
public final class WorkspaceSync {

  private WorkspaceSync() {}

  // What a front-end gathers from its build for the shared steps.
  public record Inputs(
      Path workspaceRoot,
      List<DependencySource> dependencySources,
      List<ResolvedJar> externalJars,
      JdkSource jdk,
      String serverVersion,
      List<String> runnerClasspath,
      List<String> buildFiles,
      List<ResourceRootData> resourceRoots,
      List<OutputData> outputs,
      SyncScript syncScript) {

    public Inputs {
      ValidCheck.check()
          .notNull(workspaceRoot, "workspaceRoot")
          .notNull(jdk, "jdk")
          .notNull(serverVersion, "serverVersion")
          .notNull(syncScript, "syncScript")
          .validate();
      dependencySources = List.copyOf(dependencySources);
      externalJars = List.copyOf(externalJars);
      runnerClasspath = List.copyOf(runnerClasspath);
      buildFiles = List.copyOf(buildFiles);
      resourceRoots = List.copyOf(resourceRoots);
      outputs = List.copyOf(outputs);
    }
  }

  public static void write(final Inputs inputs) {
    final List<DependencySource> dependencySources =
        DependencySourceSync.prepare(inputs.dependencySources(), inputs.externalJars());
    JdkSourceSync.extract(inputs.jdk());
    final JdkSource indexedJdk = JdkTypeIndexSync.index(inputs.jdk());
    WorkspaceManifestWriter.write(
        inputs.workspaceRoot(),
        dependencySources,
        indexedJdk,
        inputs.serverVersion(),
        inputs.runnerClasspath(),
        inputs.buildFiles(),
        inputs.resourceRoots(),
        inputs.outputs(),
        inputs.syncScript().tool());
    inputs.syncScript().write(inputs.workspaceRoot());
  }
}
