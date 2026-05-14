package io.github.aglibs.lathe.core.schema;

import java.util.List;

public record WorkspaceManifestData(
    String schemaVersion,
    String workspaceRoot,
    JdkSourceData jdk,
    List<DependencyData> dependencySources) {}
