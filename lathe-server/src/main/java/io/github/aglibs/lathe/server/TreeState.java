package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.schema.SourceStamp;
import io.github.aglibs.lathe.server.files.ServerFiles;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// A source tree as one pass sees it: each hand-written source with its mtime, from one walk per
// root, and the tree's stamps -- so its stale and orphaned sources follow without a further
// file-system call (a scan repeated every few seconds; such calls are costly on macOS). Only a
// source whose mtime moved is read, to tell a touch from a change.
record TreeState(
    ModuleSourceConfig config,
    Map<Path, Map<Path, Long>> sourcesByRoot,
    Map<Path, SourceStamp> stamps,
    Map<Path, SourceStamp> touched) {

  private static final Logger LOG = Logger.getLogger(TreeState.class.getName());

  TreeState {
    ValidCheck.check()
        .notNull(config, "config")
        .notNull(sourcesByRoot, "sourcesByRoot")
        .notNull(stamps, "stamps")
        .notNull(touched, "touched")
        .validate();
    sourcesByRoot = Map.copyOf(sourcesByRoot);
    stamps = Map.copyOf(stamps);
    touched = Map.copyOf(touched);
  }

  static TreeState read(final ModuleSourceConfig config) {
    final Map<Path, Map<Path, Long>> sourcesByRoot =
        config.sourceRoots().stream()
            .collect(
                Collectors.toMap(
                    root -> root, TreeState::javaSourceMtimes, (first, ignored) -> first));
    final Map<Path, SourceStamp> stamps =
        CompiledStamps.load(config.moduleDir(), config.sourceTree());
    return new TreeState(config, sourcesByRoot, stamps, touched(sourcesByRoot, stamps));
  }

  // No stamp (never built) or an mtime other than the one compiled -- older too, as a restore
  // that keeps file times (rsync -a, cp -p, tar) brings back older content -- unless only
  // touched. Open files count like any other: an unsaved buffer is not on disk.
  List<Path> stale() {
    return moved(sourcesByRoot, stamps)
        .filter(source -> !touched.containsKey(source.rel()))
        .map(ScannedSource::file)
        .toList();
  }

  // Moved sources whose content is still what their class was compiled from (a touch, a branch
  // switch and back, a build that compiled nothing), re-stamped at their new mtime.
  private static Map<Path, SourceStamp> touched(
      final Map<Path, Map<Path, Long>> sourcesByRoot, final Map<Path, SourceStamp> stamps) {
    return moved(sourcesByRoot, stamps)
        .filter(ScannedSource::touched)
        .collect(Collectors.toUnmodifiableMap(ScannedSource::rel, ScannedSource::restamped));
  }

  private static Stream<ScannedSource> moved(
      final Map<Path, Map<Path, Long>> sourcesByRoot, final Map<Path, SourceStamp> stamps) {
    return sourcesByRoot.entrySet().stream()
        .flatMap(
            root ->
                root.getValue().entrySet().stream()
                    .map(source -> ScannedSource.of(root.getKey(), source, stamps)))
        .filter(ScannedSource::moved);
  }

  // A source on disk with its stamp, null when it was never compiled.
  private record ScannedSource(Path file, Path rel, long mtime, SourceStamp stamp) {
    ScannedSource {
      ValidCheck.check().notNull(file, "file").notNull(rel, "rel").validate();
    }

    static ScannedSource of(
        final Path root, final Map.Entry<Path, Long> source, final Map<Path, SourceStamp> stamps) {
      final Path rel = root.relativize(source.getKey());
      return new ScannedSource(source.getKey(), rel, source.getValue(), stamps.get(rel));
    }

    boolean moved() {
      return stamp == null || stamp.mtime() != mtime;
    }

    boolean touched() {
      return stamp != null && ServerFiles.hasContent(file, stamp.content());
    }

    SourceStamp restamped() {
      return new SourceStamp(mtime, stamp.content());
    }
  }

  // Stamped sources no longer on disk: their classes are left in the mirror.
  Set<Path> orphans() {
    return stamps.keySet().stream()
        .filter(
            rel ->
                sourcesByRoot.entrySet().stream()
                    .noneMatch(root -> root.getValue().containsKey(root.getKey().resolve(rel))))
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Map<Path, Long> javaSourceMtimes(final Path root) {
    try {
      return ServerFiles.fileMtimes(root, FileUtil::isJavaFile);
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[watcher] source scan failed under %s".formatted(root));
      return Map.of();
    }
  }
}
