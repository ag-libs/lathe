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
    Map<String, List<String>> runnerClasspaths,
    // Each module's build directory (target/, build/, or where the build moved it), workspace-
    // relative: build output, never a source a search must read (WS-52).
    List<String> buildDirs) {

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
    buildDirs = List.copyOf(Objects.requireNonNullElse(buildDirs, List.of()));
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
        runnerClasspaths,
        buildDirs);
  }
}
