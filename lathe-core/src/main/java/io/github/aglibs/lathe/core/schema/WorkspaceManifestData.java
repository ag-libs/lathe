package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Objects;

public record WorkspaceManifestData(
    String schemaVersion,
    String workspaceRoot,
    String serverVersion,
    List<String> runnerClasspath,
    JdkSourceData jdk,
    List<DependencyData> dependencySources,
    List<String> buildFiles,
    List<ResourceRootData> resourceRoots,
    List<OutputData> outputs,
    // The tool that ran the last build (the sync script runs it too), for the formatter's %MVN%.
    String buildTool) {

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
  }
}
