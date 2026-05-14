package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.analysis.AnalysisEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ModuleRegistry implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(ModuleRegistry.class.getName());

  private final List<ModuleConfig> modules;
  private final Map<ModuleConfig, ModuleCompiler> compilers = new ConcurrentHashMap<>();

  private ModuleRegistry(final List<ModuleConfig> modules) {
    this.modules = modules;
  }

  public static ModuleRegistry empty() {
    return new ModuleRegistry(List.of());
  }

  public static ModuleRegistry scan(final Path workspaceRoot) {
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    if (!Files.isDirectory(latheDir)) {
      LOG.warning(() -> "[registry] .lathe/ not found at " + workspaceRoot);
      return new ModuleRegistry(List.of());
    }

    final var modules = new ArrayList<ModuleConfig>();
    try (final var stream = Files.walk(latheDir)) {
      stream
          .filter(p -> p.getFileName().toString().startsWith("lsp-params-"))
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .forEach(
              paramsFile -> {
                try {
                  modules.add(ModuleConfig.load(paramsFile, paramsFile.getParent()));
                } catch (final IOException e) {
                  LOG.log(
                      Level.WARNING, e, () -> "[registry] failed to load %s".formatted(paramsFile));
                }
              });
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[registry] scan failed");
    }

    LOG.info(
        () -> "[registry] loaded %d module(s) from %s".formatted(modules.size(), workspaceRoot));
    return new ModuleRegistry(List.copyOf(modules));
  }

  public AnalysisEngine engineFor(final ModuleConfig config) {
    return compilers.computeIfAbsent(config, ModuleCompiler::new).analysis();
  }

  public void dropFromAllCaches(final String uri) {
    compilers.values().forEach(c -> c.analysis().dropFromCache(uri));
  }

  @Override
  public void close() {
    compilers.values().forEach(ModuleCompiler::close);
    compilers.clear();
  }

  public Optional<ModuleConfig> moduleFor(final Path filePath) {
    return modules.stream()
        .filter(m -> m.sourceRoots().stream().anyMatch(filePath::startsWith))
        .findFirst();
  }

  public List<Path> allSourceRoots() {
    return modules.stream().flatMap(m -> m.sourceRoots().stream()).toList();
  }
}
