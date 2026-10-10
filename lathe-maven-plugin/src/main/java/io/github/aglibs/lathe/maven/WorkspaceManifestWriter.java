package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ResourceRootData;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.install.SyncException;
import io.github.aglibs.lathe.maven.dependency.DependencySource;
import io.github.aglibs.lathe.maven.jdk.JdkSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
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
      writeJavaHome(latheDir, jdkSource);
      if (!FileUtil.writeIfChanged(latheDir, manifestPath, newContent, false)) {
        // Content is unchanged, but reactor classes may have been recompiled. Touch the mtime so
        // the server's watcher re-scans the mirror even though the manifest itself did not change.
        Files.setLastModifiedTime(manifestPath, FileTime.from(Instant.now()));
        log.info("[sync] workspace unchanged — touched manifest for mirror refresh");
      }
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to write workspace manifest", e);
    }
  }

  // The build JDK, as a plain path the launcher reads to run the server's javac at the project's
  // language level.
  private void writeJavaHome(final Path latheDir, final JdkSource jdkSource) throws IOException {
    if (jdkSource.home() == null) {
      return;
    }

    final var javaHomePath = latheDir.resolve(LatheLayout.JAVA_HOME_FILE);
    FileUtil.writeIfChanged(latheDir, javaHomePath, jdkSource.home().toString(), false);
  }
}
