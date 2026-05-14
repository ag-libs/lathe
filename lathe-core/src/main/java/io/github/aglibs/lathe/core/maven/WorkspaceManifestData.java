package io.github.aglibs.lathe.core.maven;

import java.util.List;

public record WorkspaceManifestData(
    String schemaVersion,
    String workspaceRoot,
    JdkSourceEntry jdk,
    List<DependencyEntry> dependencySources) {}
