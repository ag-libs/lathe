package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Keeps .lathe/ to what the build has: deletes the capture of a module the build no longer has, and
// of a source tree a module no longer has, so no file is compiled against a removed module's
// settings or a removed processor's output. Only a full sync calls it, since a scoped one sees part
// of the build. It deletes files, not directories: modules nest, and .lathe/a/ holds .lathe/a/b/.
public final class CapturePruner {

  private static final Logger LOG = LoggerFactory.getLogger(CapturePruner.class);

  private CapturePruner() {}

  // current: each module's directory, relative to the root, to the source trees it still has.
  public static void prune(final Path workspaceRoot, final Map<Path, Set<String>> current) {
    final Path latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    final List<Path> stale =
        IOUtil.unchecked(() -> FileUtil.walkFiles(latheDir, LatheLayout::isParamsFile)).stream()
            .filter(
                params ->
                    !current
                        .getOrDefault(module(latheDir, params), Set.of())
                        .contains(LatheLayout.paramsSourceTree(params)))
            .toList();
    stale.forEach(params -> delete(params, !current.containsKey(module(latheDir, params))));
    if (!stale.isEmpty()) {
      LOG.info("[sync] pruned {} stale capture(s) from {}", stale.size(), latheDir);
    }
  }

  private static Path module(final Path latheDir, final Path params) {
    return latheDir.relativize(params.getParent());
  }

  // The tree's params, stamps and mirrors; for a removed module, its launch captures too.
  private static void delete(final Path params, final boolean moduleRemoved) {
    final Path moduleDir = params.getParent();
    final String tree = LatheLayout.paramsSourceTree(params);
    List.of(tree, LatheLayout.generatedSourcesDir(tree))
        .forEach(dir -> IOUtil.unchecked(() -> FileUtil.deleteDir(moduleDir.resolve(dir))));
    final List<String> files =
        Stream.concat(
                Stream.of(
                    params.getFileName().toString(), LatheLayout.compiledStampsFileName(tree)),
                moduleRemoved
                    ? Stream.of(LatheLayout.MAIN_LAUNCH_FILE, LatheLayout.TEST_LAUNCH_FILE)
                    : Stream.empty())
            .toList();
    files.forEach(file -> IOUtil.unchecked(() -> Files.deleteIfExists(moduleDir.resolve(file))));
  }
}
