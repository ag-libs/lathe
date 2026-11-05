package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.server.module.WorkspaceModuleGraph;
import io.github.aglibs.lathe.server.module.WorkspaceModuleRegistry;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

// The workspace as a load replaces it: the root, the manifest, the module registry, the module
// graph and the reference-candidate index, swapped together. Event-loop state: every accessor
// checks the loop, and the session's components read it on each use rather than keeping its
// parts, so a reload reaches them.
final class WorkspaceState {

  private final ServerEventLoop eventLoop;
  private Path root;
  private WorkspaceManifest manifest = WorkspaceManifest.empty();
  private WorkspaceModuleRegistry registry;
  private WorkspaceModuleGraph moduleGraph = WorkspaceModuleGraph.build(List.of());
  private ReferenceCandidateIndex candidateIndex = ReferenceCandidateIndex.build(List.of());
  private SourceLayout layout = new SourceLayout(List.of(), null);

  WorkspaceState(final ServerEventLoop eventLoop) {
    this.eventLoop = eventLoop;
    this.registry = WorkspaceModuleRegistry.empty(eventLoop::deliver);
  }

  // Reads the workspace at root from its .lathe/ and returns the registry it replaces, which the
  // caller closes once nothing routes through it. Built aside and swapped at once, so a load that
  // fails leaves the previous workspace whole.
  WorkspaceModuleRegistry load(final Path root) {
    eventLoop.checkOnLoop();
    final var newManifest = WorkspaceManifest.load(root);
    final var newRegistry = WorkspaceModuleRegistry.scan(root, newManifest, eventLoop::deliver);
    final var newGraph = WorkspaceModuleGraph.build(newRegistry.allConfigs());
    final var newCandidates = ReferenceCandidateIndex.build(newRegistry.allConfigs());
    final var replaced = registry;
    this.root = root;
    manifest = newManifest;
    registry = newRegistry;
    moduleGraph = newGraph;
    candidateIndex = newCandidates;
    layout = new SourceLayout(newRegistry.allConfigs(), root);
    return replaced;
  }

  // The current workspace's source layout: its modules, packages and test roots.
  SourceLayout layout() {
    eventLoop.checkOnLoop();
    return layout;
  }

  // Where a type's source may live: the reactor's source roots, then the extracted JDK and
  // dependency sources.
  List<Path> typeSourceDirs() {
    eventLoop.checkOnLoop();
    return Stream.of(
            registry.allSourceRoots().stream(),
            manifest.libraries().jdkModuleSourceDirs().stream(),
            manifest.libraries().depSourceDirs().stream())
        .flatMap(stream -> stream)
        .toList();
  }

  Path root() {
    eventLoop.checkOnLoop();
    return root;
  }

  WorkspaceManifest manifest() {
    eventLoop.checkOnLoop();
    return manifest;
  }

  WorkspaceModuleRegistry registry() {
    eventLoop.checkOnLoop();
    return registry;
  }

  WorkspaceModuleGraph moduleGraph() {
    eventLoop.checkOnLoop();
    return moduleGraph;
  }

  ReferenceCandidateIndex candidateIndex() {
    eventLoop.checkOnLoop();
    return candidateIndex;
  }
}
