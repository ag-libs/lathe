package io.github.aglibs.lathe.install;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherCopierTest {

  private static final List<String> SCRIPTS =
      List.of(LatheLayout.LAUNCHER_SCRIPT, LatheLayout.MCP_LAUNCHER_SCRIPT);

  private final LauncherCopier copier = new LauncherCopier(new SystemStreamLog());

  @TempDir private Path td;

  @Test
  void copyToWorkspace_copiesLaunchersIntoLatheDir() throws IOException {
    copier.copyToWorkspace(td.resolve("ws"), installVersion("0.2.0"));

    assertCopies(td.resolve("ws").resolve(LatheLayout.LATHE_DIR), "0.2.0");
  }

  // `latest` was a symlink at servers/<newest>; replacing it must not delete that server's files.
  @Test
  void copyNewestToLatest_oldLatestSymlink_replacedByNewestCopies_serversKept() throws IOException {
    final Path latest = td.resolve(LatheLayout.LATEST_DIR);
    final Path oldVersion = installVersion("0.1.0");
    Files.createSymbolicLink(latest, oldVersion);

    copier.copyNewestToLatest(td.resolve(LatheLayout.SERVERS_DIR), latest);
    installVersion("0.2.0");
    copier.copyNewestToLatest(td.resolve(LatheLayout.SERVERS_DIR), latest);

    assertCopies(latest, "0.2.0");
    assertCopies(oldVersion, "0.1.0");
  }

  @Test
  void copyNewestToLatest_noServerInstalled_createsNothing() throws IOException {
    final Path latest = td.resolve(LatheLayout.LATEST_DIR);

    copier.copyNewestToLatest(td.resolve(LatheLayout.SERVERS_DIR), latest);

    assertThat(latest).doesNotExist();
  }

  private Path installVersion(final String version) throws IOException {
    final Path versionDir = td.resolve(LatheLayout.SERVERS_DIR).resolve(version);
    for (final var script : SCRIPTS) {
      FileUtil.writeIfChanged(
          versionDir, versionDir.resolve(script), launcher(script, version), true);
    }

    return versionDir;
  }

  private static void assertCopies(final Path dir, final String version) {
    for (final var script : SCRIPTS) {
      assertThat(dir.resolve(script)).isExecutable().hasContent(launcher(script, version));
    }
  }

  private static String launcher(final String script, final String version) {
    return "#!/bin/sh\n# %s %s\n".formatted(script, version);
  }
}
