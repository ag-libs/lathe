package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class ModuleWorkspace implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(ModuleWorkspace.class.getName());

  private final List<ModuleConfig> modules;
  private final Map<String, ModuleWorker> workers = new HashMap<>();
  private final ModuleWorker externalWorker;
  private final WorkspaceTypeIndex typeIndex;

  private ModuleWorkspace(
      final List<ModuleConfig> modules,
      final WorkspaceManifest manifest,
      final WorkspaceTypeIndex typeIndex) {
    this.modules = modules;
    this.typeIndex = typeIndex;
    this.externalWorker = ModuleWorker.external(manifest);
  }

  public static ModuleWorkspace empty() {
    return new ModuleWorkspace(List.of(), WorkspaceManifest.empty(), WorkspaceTypeIndex.empty());
  }

  public static ModuleWorkspace scan(
      final Path workspaceRoot,
      final WorkspaceManifest manifest,
      final WorkspaceTypeIndex typeIndex) {
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    if (!Files.isDirectory(latheDir)) {
      LOG.warning(() -> "[workspace] .lathe/ not found at " + workspaceRoot);
      return new ModuleWorkspace(List.of(), manifest, typeIndex);
    }

    final var modules = new ArrayList<ModuleConfig>();
    try (final var stream = Files.walk(latheDir)) {
      stream
          .filter(ModuleWorkspace::isParamsFile)
          .forEach(
              paramsFile -> {
                try {
                  modules.add(ModuleConfig.load(paramsFile, paramsFile.getParent()));
                } catch (final IOException e) {
                  LOG.log(
                      Level.WARNING,
                      e,
                      () -> "[workspace] failed to load %s".formatted(paramsFile));
                }
              });
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[workspace] scan failed");
    }

    LOG.info(
        () -> "[workspace] loaded %d module(s) from %s".formatted(modules.size(), workspaceRoot));
    return new ModuleWorkspace(List.copyOf(modules), manifest, typeIndex);
  }

  public Optional<ModuleConfig> moduleFor(final Path filePath) {
    return modules.stream()
        .filter(m -> m.sourceRoots().stream().anyMatch(filePath::startsWith))
        .findFirst();
  }

  public List<Path> allSourceRoots() {
    return modules.stream().flatMap(m -> m.sourceRoots().stream()).toList();
  }

  public ModuleWorker workerFor(final ModuleConfig config) {
    return workers.computeIfAbsent(
        config.moduleDir().toString(), key -> ModuleWorker.module(config, typeIndex));
  }

  public ModuleWorker externalWorker() {
    return externalWorker;
  }

  public void dropFromAllCaches(final String uri) {
    workers.values().forEach(w -> w.dropFromCache(uri));
    externalWorker.dropFromCache(uri);
  }

  @Override
  public void close() {
    final var closeFutures =
        Stream.concat(workers.values().stream(), Stream.of(externalWorker))
            .map(ModuleWorker::closeAsync)
            .toArray(CompletableFuture[]::new);
    CompletableFuture.allOf(closeFutures).join();
    workers.clear();
  }

  private static boolean isParamsFile(final Path p) {
    final var name = p.getFileName().toString();
    return name.startsWith("lsp-params-") && name.endsWith(".json");
  }
}
