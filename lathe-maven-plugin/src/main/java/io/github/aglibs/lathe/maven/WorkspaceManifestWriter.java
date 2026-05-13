package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.ParamStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

final class WorkspaceManifestWriter {

  private WorkspaceManifestWriter() {}

  static void write(
      final Path workspaceRoot, final List<DependencySource> dependencySources, final Log log)
      throws MojoExecutionException {
    final Path latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    Path tempFile = null;
    try {
      Files.createDirectories(latheDir);
      tempFile = Files.createTempFile(latheDir, LatheLayout.WORKSPACE_PROPERTIES, ".tmp");
      write(tempFile, workspaceRoot, dependencySources);
      FileUtil.moveReplacing(tempFile, latheDir.resolve(LatheLayout.WORKSPACE_PROPERTIES));
    } catch (final IOException e) {
      throw new MojoExecutionException("lathe:sync failed to write workspace properties", e);
    } finally {
      if (tempFile != null) {
        try {
          Files.deleteIfExists(tempFile);
        } catch (final IOException e) {
          log.debug("[sync] failed to clean temporary workspace properties", e);
        }
      }
    }
  }

  private static void write(
      final Path file, final Path workspaceRoot, final List<DependencySource> dependencySources)
      throws IOException {
    final ParamStore props = new ParamStore();
    props.set("schemaVersion", LatheLayout.SCHEMA_VERSION);
    props.set("workspaceRoot", workspaceRoot.toString());
    props.putIndexed("dependencySource", dependencySources);
    props.store(file);
  }
}
