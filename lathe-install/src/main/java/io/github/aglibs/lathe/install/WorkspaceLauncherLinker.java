package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.logging.Log;

// Links a workspace's .lathe/ at the server version it pins: .lathe/lathe-launcher.sh and
// .lathe/lathe-mcp-launcher.sh point at the matching scripts under servers/<version>/. These are
// the discovery contract every editor client and the MCP agent resolve, pinned per workspace, so
// no machine-global `current` pointer can drift. Shared by the Maven and OpenJDK sync paths, each
// of which installs the server then links its workspace at it.
public final class WorkspaceLauncherLinker {

  private static final List<String> LAUNCHER_SCRIPTS =
      List.of(LatheLayout.LAUNCHER_SCRIPT, LatheLayout.MCP_LAUNCHER_SCRIPT);

  private final Log log;

  public WorkspaceLauncherLinker(final Log log) {
    this.log = log;
  }

  public void link(final Path workspaceRoot, final Path versionDir) {
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    try {
      Files.createDirectories(latheDir);
      for (final var script : LAUNCHER_SCRIPTS) {
        linkScript(latheDir.resolve(script), versionDir.resolve(script));
      }
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to link workspace launchers", e);
    }
  }

  private void linkScript(final Path link, final Path target) throws IOException {
    if (!FileUtil.linkSymbolic(link, target)) {
      log.debug("[sync] %s unchanged — skipping link".formatted(link.getFileName()));
      return;
    }

    log.info("[sync] linked %s → %s".formatted(link.getFileName(), target));
  }
}
