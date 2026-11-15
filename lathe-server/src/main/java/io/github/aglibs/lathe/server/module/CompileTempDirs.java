package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.files.ServerFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A compile's temp dir, named for the server's process. A server that exits without closing its
// compilers (killed, crashed) leaves its dirs behind, and the next server to start deletes them.
final class CompileTempDirs {

  private static final Logger LOG = Logger.getLogger(CompileTempDirs.class.getName());
  private static final Pattern OWNED =
      Pattern.compile(
          "%s(\\d{1,18})-.*".formatted(Pattern.quote(LatheLayout.COMPILE_TEMP_DIR_PREFIX)));
  // Once per process: the first compiler created sweeps.
  private static final AtomicBoolean SWEPT = new AtomicBoolean();

  private CompileTempDirs() {}

  static Path create() throws IOException {
    if (SWEPT.compareAndSet(false, true)) {
      deleteOrphaned(Path.of(System.getProperty("java.io.tmpdir")));
    }

    return Files.createTempDirectory(
        "%s%d-".formatted(LatheLayout.COMPILE_TEMP_DIR_PREFIX, ProcessHandle.current().pid()));
  }

  // The dirs of servers no longer running. A live server's is left, and so is one this user cannot
  // delete (another user's server, on a shared temp dir). Only root's own level is read: below it
  // lie other users' entries this one cannot read, and the cleanup must never fail a compile.
  static void deleteOrphaned(final Path root) {
    final List<Path> dirs;
    try {
      dirs = ServerFiles.childDirectories(root);
    } catch (final IOException | UncheckedIOException e) {
      LOG.log(Level.WARNING, e, () -> "[cache] temp dir %s not listed".formatted(root));
      return;
    }

    for (final Path dir : dirs) {
      final Matcher owner = OWNED.matcher(dir.getFileName().toString());
      if (owner.matches() && ProcessHandle.of(Long.parseLong(owner.group(1))).isEmpty()) {
        try {
          FileUtil.deleteDir(dir);
          LOG.fine(() -> "[cache] orphaned temp dir %s deleted".formatted(dir));
        } catch (final IOException e) {
          LOG.log(Level.FINE, e, () -> "[cache] orphaned temp dir %s not deleted".formatted(dir));
        }
      }
    }
  }
}
