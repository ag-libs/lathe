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

public final class WorkspaceModules implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(WorkspaceModules.class.getName());

  private final List<ModuleSourceConfig> moduleSources;
  private final Map<String, ModuleSourceWorker> workers = new HashMap<>();
  private final ModuleSourceWorker externalWorker;
  private final WorkspaceTypeIndex typeIndex;

  private WorkspaceModules(
      final List<ModuleSourceConfig> moduleSources,
      final WorkspaceManifest manifest,
      final WorkspaceTypeIndex typeIndex) {
    this.moduleSources = moduleSources;
    this.typeIndex = typeIndex;
    this.externalWorker = ModuleSourceWorker.external(manifest);
  }

  public static WorkspaceModules empty() {
    return new WorkspaceModules(List.of(), WorkspaceManifest.empty(), WorkspaceTypeIndex.empty());
  }

  public static WorkspaceModules scan(
      final Path workspaceRoot,
      final WorkspaceManifest manifest,
      final WorkspaceTypeIndex typeIndex) {
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    if (!Files.isDirectory(latheDir)) {
      LOG.warning(() -> "[workspace] .lathe/ not found at " + workspaceRoot);
      return new WorkspaceModules(List.of(), manifest, typeIndex);
    }

    final var moduleSources = new ArrayList<ModuleSourceConfig>();
    try (final var stream = Files.walk(latheDir)) {
      stream
          .filter(WorkspaceModules::isParamsFile)
          .forEach(
              paramsFile -> {
                try {
                  moduleSources.add(ModuleSourceConfig.load(paramsFile, paramsFile.getParent()));
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
        () ->
            "[workspace] loaded %d module source config(s) from %s"
                .formatted(moduleSources.size(), workspaceRoot));
    return new WorkspaceModules(List.copyOf(moduleSources), manifest, typeIndex);
  }

  public Optional<ModuleSourceConfig> moduleSourceFor(final Path filePath) {
    return moduleSources.stream()
        .filter(m -> m.sourceRoots().stream().anyMatch(filePath::startsWith))
        .findFirst();
  }

  public List<Path> allSourceRoots() {
    return moduleSources.stream().flatMap(m -> m.sourceRoots().stream()).toList();
  }

  public ModuleSourceWorker workerFor(final ModuleSourceConfig config) {
    return workers.computeIfAbsent(
        config.latheClassesDir().toString(), key -> ModuleSourceWorker.module(config, typeIndex));
  }

  public ModuleSourceWorker externalWorker() {
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
            .map(ModuleSourceWorker::closeAsync)
            .toArray(CompletableFuture[]::new);
    CompletableFuture.allOf(closeFutures).join();
    workers.clear();
  }

  private static boolean isParamsFile(final Path p) {
    final var name = p.getFileName().toString();
    return name.startsWith("lsp-params-") && name.endsWith(".json");
  }
}
