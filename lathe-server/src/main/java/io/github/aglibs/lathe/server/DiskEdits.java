package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.Positions;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.LatheUri;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.eclipse.lsp4j.TextEdit;

// How the in-process engine writes an edit to disk, as one transaction under .lathe/'s build lock:
// the text edits, then the file moves, then the files reported to the write pass so the mirror
// has them, then the manifest touched so an editor's server on the workspace re-scans the mirror.
// Shared by rename, format and fix imports.
final class DiskEdits {

  private final Path latheDir;
  private final Consumer<Set<Path>> reportWritten;

  DiskEdits(final Path latheDir, final Consumer<Set<Path>> reportWritten) {
    this.latheDir = latheDir;
    this.reportWritten = reportWritten;
  }

  // changed: the files as they are after the moves.
  void apply(
      final Map<String, List<TextEdit>> textEdits,
      final Map<String, String> moves,
      final Set<Path> changed) {
    IOUtil.unchecked(() -> LatheLock.acquire(LatheLayout.reactorLockFile(latheDir)));
    try {
      textEdits.forEach(DiskEdits::applyFileEdits);
      moves.forEach(
          (oldUri, newUri) ->
              IOUtil.unchecked(() -> Files.move(LatheUri.toPath(oldUri), LatheUri.toPath(newUri))));
      reportWritten.accept(changed);
      // An editor's server on this workspace sees the files freshly stamped, so it would never
      // re-index the renamed types; the touch makes it re-scan the mirror, as after a Maven sync.
      final Path manifest = latheDir.resolve(LatheLayout.WORKSPACE_JSON);
      if (Files.exists(manifest)) {
        IOUtil.unchecked(() -> FileUtil.touch(manifest));
      }

    } finally {
      IOUtil.unchecked(() -> LatheLock.release(LatheLayout.reactorLockFile(latheDir)));
    }
  }

  private static void applyFileEdits(final String uri, final List<TextEdit> edits) {
    final var path = LatheUri.toPath(uri);
    final String updated =
        Positions.applyEdits(IOUtil.unchecked(() -> Files.readString(path)), edits);
    IOUtil.unchecked(() -> Files.writeString(path, updated));
  }
}
