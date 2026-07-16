package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ResourceRootData;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.maven.dependency.DependencySource;
import io.github.aglibs.lathe.maven.jdk.JdkSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.logging.Log;

final class WorkspaceManifestWriter {

  private final Log log;

  WorkspaceManifestWriter(final Log log) {
    this.log = log;
  }

  void write(
      final Path workspaceRoot,
      final List<DependencySource> dependencySources,
      final JdkSource jdkSource,
      final String serverVersion,
      final List<String> runnerClasspath,
      final List<String> pomPaths,
      final List<ResourceRootData> resourceRoots) {
    final var data =
        new WorkspaceManifestData(
            LatheLayout.SCHEMA_VERSION,
            workspaceRoot.toString(),
            serverVersion,
            runnerClasspath,
            jdkSource.toData(),
            dependencySources.stream().map(DependencySource::toData).toList(),
            pomPaths,
            resourceRoots);
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    final var manifestPath = latheDir.resolve(LatheLayout.WORKSPACE_JSON);
    final var newContent = Json.toJson(data);
    try {
      Files.createDirectories(latheDir);
      if (Files.exists(manifestPath)
          && newContent.equals(Files.readString(manifestPath, StandardCharsets.UTF_8))) {
        log.info("[sync] workspace unchanged — skipping write");
        return;
      }

      FileUtil.writeAtomically(latheDir, manifestPath, newContent, false);
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to write workspace manifest", e);
    }
  }
}
