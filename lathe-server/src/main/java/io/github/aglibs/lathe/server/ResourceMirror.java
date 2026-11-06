package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheUri;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

// The reactor's resources in the .lathe/ mirror: a resource edit is copied in without a build, and
// the copy of a deleted resource removed. Event-loop state: the resources the last scan found.
final class ResourceMirror {

  private static final Logger LOG = Logger.getLogger(ResourceMirror.class.getName());

  private final WorkspaceState state;
  // The resource files the last scan found, so the next one can drop the copies of deleted ones.
  private Set<Path> scannedResources = Set.of();

  ResourceMirror(final WorkspaceState state) {
    this.state = state;
  }

  // Copies a changed resource into .lathe/ so a resource-only edit is picked up without a rebuild.
  // Returns the .lathe/ destination written, or empty if the file maps to no resource root or the
  // copy failed.
  Optional<Path> refresh(final String uri) {
    final var file = LatheUri.toPath(uri);
    final Optional<Path> dest = state.manifest().resourceDestination(file);
    if (dest.isEmpty()) {
      LOG.fine(() -> "[resource] %s not a tracked resource → ignored".formatted(uri));
      return Optional.empty();
    }

    return copyResource(file, dest.get());
  }

  private Optional<Path> copyResource(final Path file, final Path dest) {
    try {
      FileUtil.copyFileAtomically(file, dest);
      LOG.info(() -> "[resource] %s → %s".formatted(file, dest));
      return Optional.of(dest);
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[resource] copy failed for %s".formatted(file));
      return Optional.empty();
    }
  }

  // Copy any resource whose .lathe/ destination is missing or older than the source, so an external
  // resource edit is picked up without Maven; the copy bumps the destination mtime, so the next
  // pass sees it as fresh. Remove the copy of a resource deleted since the last pass. Returns how
  // many resources were checked.
  int reconcile() {
    final Map<Path, Long> resources =
        state.manifest().resourceSourceDirs().stream()
            .flatMap(sourceDir -> resourceMtimesUnder(sourceDir).entrySet().stream())
            .collect(
                Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    resources.forEach(this::copyIfStale);
    // Only files the last scan saw: a copy with no source may be the build's own output.
    final Set<Path> deleted =
        scannedResources.stream()
            .filter(file -> !resources.containsKey(file) && Files.notExists(file))
            .collect(Collectors.toUnmodifiableSet());
    deleted.forEach(
        file ->
            state
                .manifest()
                .resourceDestination(file)
                .ifPresent(dest -> deleteResource(file, dest)));
    scannedResources = resources.keySet();
    return resources.size();
  }

  private Map<Path, Long> resourceMtimesUnder(final Path sourceDir) {
    try {
      return FileUtil.fileMtimes(sourceDir, file -> true);
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[resource] scan failed under %s".formatted(sourceDir));
      return Map.of();
    }
  }

  private void deleteResource(final Path file, final Path dest) {
    try {
      Files.deleteIfExists(dest);
      LOG.info(() -> "[resource] %s deleted → removed %s".formatted(file, dest));
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[resource] delete failed for %s".formatted(dest));
    }
  }

  // A missing copy reads as mtime 0, so it is copied too.
  private void copyIfStale(final Path file, final long mtime) {
    final Optional<Path> dest = state.manifest().resourceDestination(file);
    if (dest.isEmpty()) {
      return;
    }

    if (mtime > WritePass.mtimeMillis(dest.get())) {
      copyResource(file, dest.get());
    }
  }
}
