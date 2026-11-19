package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record WorkspaceManifestData(
    String schemaVersion,
    String workspaceRoot,
    String serverVersion,
    List<String> runnerClasspath,
    JdkSourceData jdk,
    List<DependencyData> dependencySources,
    List<BuildFileData> buildFiles,
    List<ResourceRootData> resourceRoots,
    List<OutputData> outputs,
    // The tool that ran the last build (the sync script runs it too), for the formatter's %MVN%.
    String buildTool,
    // A runner for each JUnit platform version the workspace's tests use, by that version: a
    // module replays on the one its own test classpath's platform names, and on runnerClasspath
    // when none does.
    Map<String, List<String>> runnerClasspaths) {

  public WorkspaceManifestData {
    ValidCheck.check()
        .notBlank(schemaVersion, "schemaVersion")
        .notBlank(workspaceRoot, "workspaceRoot")
        .validate();
    runnerClasspath = Objects.requireNonNullElse(runnerClasspath, List.of());
    dependencySources = Objects.requireNonNullElse(dependencySources, List.of());
    buildFiles = Objects.requireNonNullElse(buildFiles, List.of());
    resourceRoots = Objects.requireNonNullElse(resourceRoots, List.of());
    outputs = Objects.requireNonNullElse(outputs, List.of());
    runnerClasspaths = Map.copyOf(Objects.requireNonNullElse(runnerClasspaths, Map.of()));
  }

  // A workspace with one runner for every module, as a build front-end that resolves no other
  // writes it.
  public WorkspaceManifestData(
      final String schemaVersion,
      final String workspaceRoot,
      final String serverVersion,
      final List<String> runnerClasspath,
      final JdkSourceData jdk,
      final List<DependencyData> dependencySources,
      final List<BuildFileData> buildFiles,
      final List<ResourceRootData> resourceRoots,
      final List<OutputData> outputs,
      final String buildTool) {
    this(
        schemaVersion,
        workspaceRoot,
        serverVersion,
        runnerClasspath,
        jdk,
        dependencySources,
        buildFiles,
        resourceRoots,
        outputs,
        buildTool,
        Map.of());
  }

  public WorkspaceManifestData withBuildFiles(final List<BuildFileData> files) {
    return new WorkspaceManifestData(
        schemaVersion,
        workspaceRoot,
        serverVersion,
        runnerClasspath,
        jdk,
        dependencySources,
        files,
        resourceRoots,
        outputs,
        buildTool,
        runnerClasspaths);
  }
}
