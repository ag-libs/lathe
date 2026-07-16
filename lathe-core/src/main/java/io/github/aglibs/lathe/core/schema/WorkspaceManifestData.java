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
    List<String> pomPaths,
    List<ResourceRootData> resourceRoots) {

  public WorkspaceManifestData {
    ValidCheck.check()
        .notBlank(schemaVersion, "schemaVersion")
        .notBlank(workspaceRoot, "workspaceRoot")
        .validate();
    runnerClasspath = Objects.requireNonNullElse(runnerClasspath, List.of());
    dependencySources = Objects.requireNonNullElse(dependencySources, List.of());
    pomPaths = Objects.requireNonNullElse(pomPaths, List.of());
    resourceRoots = Objects.requireNonNullElse(resourceRoots, List.of());
  }
}
