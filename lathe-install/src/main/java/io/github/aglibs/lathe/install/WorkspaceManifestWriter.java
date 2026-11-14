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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
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
    try {
      if (jdkSource.home() != null) {
        writeJavaHome(latheDir, jdkSource.home());
      }

      writeManifest(latheDir, data);
    } catch (final IOException e) {
      throw new SyncException("Lathe sync failed to write workspace manifest", e);
    }
  }

  // The manifest, written only when its content changed; unchanged, it is touched, as reactor
  // classes may have been recompiled and the server's watcher re-scans the mirror on its mtime.
  public static void writeManifest(final Path latheDir, final WorkspaceManifestData data)
      throws IOException {
    final var manifestPath = latheDir.resolve(LatheLayout.WORKSPACE_JSON);
    if (!FileUtil.writeIfChanged(latheDir, manifestPath, Json.toJson(data), false)) {
      FileUtil.touch(manifestPath);
      LOG.info("[sync] workspace unchanged — touched manifest for mirror refresh");
    }
  }

  // The content each POM had when Maven read it, so the server asks for a sync only when one
  // really changed, not when it was merely touched or put back. lathe-style.json is one too: the
  // sync resolves the formatter it declares.
  private static List<BuildFileData> fingerprinted(
      final Path workspaceRoot, final List<String> buildFiles) {
    final Stream<String> files =
        Files.isRegularFile(workspaceRoot.resolve(LatheLayout.STYLE_SHARED_FILE))
            ? Stream.concat(buildFiles.stream(), Stream.of(LatheLayout.STYLE_SHARED_FILE))
            : buildFiles.stream();
    return files
        .map(
            rel ->
                new BuildFileData(
                    rel, IOUtil.unchecked(() -> FileUtil.fingerprint(workspaceRoot.resolve(rel)))))
        .toList();
  }

  // The build JDK, as a plain path the launcher reads to run the server's javac at the project's
  // language level.
  public static void writeJavaHome(final Path latheDir, final Path javaHome) throws IOException {
    final var javaHomePath = latheDir.resolve(LatheLayout.JAVA_HOME_FILE);
    FileUtil.writeIfChanged(latheDir, javaHomePath, javaHome.toString(), false);
  }
}
