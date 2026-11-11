package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.ResourceRootData;
import io.github.aglibs.lathe.install.dependency.DependencySource;
import io.github.aglibs.lathe.install.dependency.DependencySourceSync;
import io.github.aglibs.lathe.install.jdk.JdkSource;
import io.github.aglibs.lathe.install.jdk.JdkSourceSync;
import io.github.aglibs.lathe.install.typeindex.JdkTypeIndexSync;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// The steps every build front-end's sync shares, in one order: the dependencies' sources and type
// indexes, the JDK's sources and type index, then the workspace manifest and the sync script. A
// front-end gathers the inputs from its build and adds what is its own around this.
public final class WorkspaceSync {

  static final String GITIGNORE_FILE = ".gitignore";

  private WorkspaceSync() {}

  // The cached sources and type indexes the manifest names, each source enriched with them.
  public record Caches(List<DependencySource> dependencySources, JdkSource jdk) {

    public Caches {
      ValidCheck.check().notNull(jdk, "jdk").validate();
      dependencySources = List.copyOf(dependencySources);
    }
  }

  // What a front-end gathers from its build for the manifest.
  public record Inputs(
      Path workspaceRoot,
      Caches caches,
      String serverVersion,
      List<String> runnerClasspath,
      List<String> buildFiles,
      List<ResourceRootData> resourceRoots,
      List<OutputData> outputs,
      SyncScript syncScript) {

    public Inputs {
      ValidCheck.check()
          .notNull(workspaceRoot, "workspaceRoot")
          .notNull(caches, "caches")
          .notNull(serverVersion, "serverVersion")
          .notNull(syncScript, "syncScript")
          .validate();
      runnerClasspath = List.copyOf(runnerClasspath);
      buildFiles = List.copyOf(buildFiles);
      resourceRoots = List.copyOf(resourceRoots);
      outputs = List.copyOf(outputs);
    }
  }

  // Runs on every sync, also one that writes no manifest (Maven's -pl), so the caches are filled.
  public static Caches prepareCaches(
      final List<DependencySource> dependencySources,
      final List<ResolvedJar> externalJars,
      final JdkSource jdk) {
    final List<DependencySource> prepared =
        DependencySourceSync.prepare(dependencySources, externalJars);
    JdkSourceSync.extract(jdk);
    return new Caches(prepared, JdkTypeIndexSync.index(jdk));
  }

  public static void write(final Inputs inputs) {
    WorkspaceManifestWriter.write(
        inputs.workspaceRoot(),
        inputs.caches().dependencySources(),
        inputs.caches().jdk(),
        inputs.serverVersion(),
        inputs.runnerClasspath(),
        inputs.buildFiles(),
        inputs.resourceRoots(),
        inputs.outputs(),
        inputs.syncScript().tool());
    inputs.syncScript().write(inputs.workspaceRoot());
    writeGitignore(inputs.workspaceRoot());
  }

  // .lathe/ keeps itself out of git, so a project set up without changing it stays unchanged. Only
  // when missing: a user may have edited it.
  static void writeGitignore(final Path workspaceRoot) {
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    final var file = latheDir.resolve(GITIGNORE_FILE);
    if (Files.exists(file)) {
      return;
    }

    try {
      FileUtil.writeIfChanged(latheDir, file, "*\n", false);
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to write %s".formatted(file), e);
    }
  }
}
