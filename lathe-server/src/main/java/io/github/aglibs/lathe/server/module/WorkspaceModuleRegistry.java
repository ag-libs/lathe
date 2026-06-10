package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.LatheLayout;
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

public final class WorkspaceModuleRegistry implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(WorkspaceModuleRegistry.class.getName());

  private final List<ModuleSourceConfig> moduleSources;
  private final Map<String, CompilationWorker> workers = new HashMap<>();
  private final CompilationWorker externalWorker;

  private WorkspaceModuleRegistry(
      final List<ModuleSourceConfig> moduleSources, final WorkspaceManifest manifest) {
    this.moduleSources = moduleSources;
    this.externalWorker = CompilationWorker.external(manifest);
  }

  public static WorkspaceModuleRegistry empty() {
    return new WorkspaceModuleRegistry(List.of(), WorkspaceManifest.empty());
  }

  public static WorkspaceModuleRegistry scan(
      final Path workspaceRoot, final WorkspaceManifest manifest) {
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    if (!Files.isDirectory(latheDir)) {
      LOG.warning(() -> "[workspace] .lathe/ not found at " + workspaceRoot);
      return new WorkspaceModuleRegistry(List.of(), manifest);
    }

    final List<ModuleSourceConfig> moduleSources = new ArrayList<>();
    try (final Stream<Path> stream = Files.walk(latheDir)) {
      stream
          .filter(LatheLayout::isParamsFile)
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
    return new WorkspaceModuleRegistry(List.copyOf(moduleSources), manifest);
  }

  public List<ModuleSourceConfig> allConfigs() {
    return moduleSources;
  }

  public Optional<ModuleSourceConfig> moduleSourceFor(final Path filePath) {
    return moduleSources.stream()
        .filter(m -> m.sourceRoots().stream().anyMatch(filePath::startsWith))
        .findFirst();
  }

  public List<Path> allSourceRoots() {
    return moduleSources.stream().flatMap(m -> m.sourceRoots().stream()).toList();
  }

  public CompilationWorker workerFor(final ModuleSourceConfig config) {
    return workers.computeIfAbsent(
        config.latheClassesDir().toString(), key -> CompilationWorker.module(config));
  }

  public CompilationWorker externalWorker() {
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
            .map(CompilationWorker::closeAsync)
            .toArray(CompletableFuture[]::new);
    CompletableFuture.allOf(closeFutures).join();
    workers.clear();
  }
}
