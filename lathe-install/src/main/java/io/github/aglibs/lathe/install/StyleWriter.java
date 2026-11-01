package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// .lathe/style.json: the formatter and indent the build tool detected for the workspace.
public final class StyleWriter {

  private static final Logger LOG = LoggerFactory.getLogger(StyleWriter.class);

  private StyleWriter() {}

  public static void write(final Path workspaceRoot, final WorkspaceStyleData style) {
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    try {
      if (FileUtil.writeIfChanged(
          latheDir, latheDir.resolve(LatheLayout.STYLE_FILE), Json.toJson(style), false)) {
        LOG.info("[sync] style formatter=%s".formatted(style.formatter().engine()));
      }
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to write style.json", e);
    }
  }

  // A build that no longer has a style: the server must not keep formatting with the last one.
  public static void delete(final Path workspaceRoot) {
    final Path file = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.STYLE_FILE);
    try {
      if (Files.deleteIfExists(file)) {
        LOG.info("[sync] style removed, formatting off");
      }
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to delete style.json", e);
    }
  }
}
