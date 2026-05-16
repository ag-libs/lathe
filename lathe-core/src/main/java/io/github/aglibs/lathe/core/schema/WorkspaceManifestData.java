package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Objects;

public record WorkspaceManifestData(
    String schemaVersion,
    String workspaceRoot,
    String serverVersion,
    JdkSourceData jdk,
    List<DependencyData> dependencySources) {

  public WorkspaceManifestData {
    ValidCheck.check()
        .notBlank(schemaVersion, "schemaVersion")
        .notBlank(workspaceRoot, "workspaceRoot")
        .validate();
    dependencySources = Objects.requireNonNullElse(dependencySources, List.of());
  }
}
