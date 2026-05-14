package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.maven.dependency.DependencySource;
import io.github.aglibs.lathe.maven.jdk.JdkSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.logging.Log;

final class WorkspaceManifestWriter {

  private WorkspaceManifestWriter() {}

  static void write(
      final Path workspaceRoot,
      final List<DependencySource> dependencySources,
      final JdkSource jdkSource,
      final Log log) {
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    Path tempFile = null;
    try {
      Files.createDirectories(latheDir);
      tempFile = Files.createTempFile(latheDir, LatheLayout.WORKSPACE_JSON, ".tmp");
      write(tempFile, workspaceRoot, dependencySources, jdkSource);
      FileUtil.moveReplacing(tempFile, latheDir.resolve(LatheLayout.WORKSPACE_JSON));
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to write workspace manifest", e);
    } finally {
      if (tempFile != null) {
        try {
          Files.deleteIfExists(tempFile);
        } catch (final IOException e) {
          log.debug("[sync] failed to clean temporary workspace manifest", e);
        }
      }
    }
  }

  private static void write(
      final Path file,
      final Path workspaceRoot,
      final List<DependencySource> dependencySources,
      final JdkSource jdkSource)
      throws IOException {
    final var data =
        new WorkspaceManifestData(
            LatheLayout.SCHEMA_VERSION,
            workspaceRoot.toString(),
            jdkSource.toData(),
            dependencySources.stream().map(DependencySource::toData).toList());
    Json.write(data, file);
  }
}
