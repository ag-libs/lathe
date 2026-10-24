package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.BuildFileData;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.ResourceRootData;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.install.dependency.DependencySource;
import io.github.aglibs.lathe.install.jdk.JdkSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorkspaceManifestWriter {

  private static final Logger LOG = LoggerFactory.getLogger(WorkspaceManifestWriter.class);

  private WorkspaceManifestWriter() {}

  public static void write(
      final Path workspaceRoot,
      final List<DependencySource> dependencySources,
      final JdkSource jdkSource,
      final String serverVersion,
      final List<String> runnerClasspath,
      final List<String> buildFiles,
      final List<ResourceRootData> resourceRoots,
      final List<OutputData> outputs,
      final Path buildTool) {
    final var data =
        new WorkspaceManifestData(
            LatheLayout.SCHEMA_VERSION,
            workspaceRoot.toString(),
            serverVersion,
            runnerClasspath,
            jdkSource.toData(),
            dependencySources.stream().map(DependencySource::toData).toList(),
            fingerprinted(workspaceRoot, buildFiles),
            resourceRoots,
            outputs,
            buildTool.toString());
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    final var manifestPath = latheDir.resolve(LatheLayout.WORKSPACE_JSON);
    final var newContent = Json.toJson(data);
    try {
      writeJavaHome(latheDir, jdkSource);
      if (!FileUtil.writeIfChanged(latheDir, manifestPath, newContent, false)) {
        // Content is unchanged, but reactor classes may have been recompiled. Touch the mtime so
        // the server's watcher re-scans the mirror even though the manifest itself did not change.
        FileUtil.touch(manifestPath);
        LOG.info("[sync] workspace unchanged — touched manifest for mirror refresh");
      }
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to write workspace manifest", e);
    }
  }

  // The content each POM had when Maven read it, so the server asks for a sync only when one
  // really changed, not when it was merely touched or put back.
  private static List<BuildFileData> fingerprinted(
      final Path workspaceRoot, final List<String> buildFiles) {
    return buildFiles.stream()
        .map(
            rel ->
                new BuildFileData(
                    rel, IOUtil.unchecked(() -> FileUtil.fingerprint(workspaceRoot.resolve(rel)))))
        .toList();
  }

  // The build JDK, as a plain path the launcher reads to run the server's javac at the project's
  // language level.
  private static void writeJavaHome(final Path latheDir, final JdkSource jdkSource)
      throws IOException {
    if (jdkSource.home() == null) {
      return;
    }

    final var javaHomePath = latheDir.resolve(LatheLayout.JAVA_HOME_FILE);
    FileUtil.writeIfChanged(latheDir, javaHomePath, jdkSource.home().toString(), false);
  }
}
