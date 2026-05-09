package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

final class ModuleRegistry {

  private static final Logger LOG = Logger.getLogger(ModuleRegistry.class.getName());

  private final List<ModuleParams> modules;

  private ModuleRegistry(final List<ModuleParams> modules) {
    this.modules = modules;
  }

  static ModuleRegistry empty() {
    return new ModuleRegistry(List.of());
  }

  static ModuleRegistry scan(final Path workspaceRoot) {
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    if (!Files.isDirectory(latheDir)) {
      LOG.warning(() -> "[registry] .lathe/ not found at " + workspaceRoot);
      return new ModuleRegistry(List.of());
    }

    final var modules = new ArrayList<ModuleParams>();
    try (final var stream = Files.walk(latheDir)) {
      stream
          .filter(p -> p.getFileName().toString().startsWith("lsp-params-"))
          .filter(p -> p.getFileName().toString().endsWith(".properties"))
          .forEach(
              paramsFile -> {
                try {
                  modules.add(ModuleParams.load(paramsFile, paramsFile.getParent()));
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

  Optional<ModuleParams> findForFile(final Path filePath) {
    return modules.stream()
        .filter(m -> m.sourceRoots().stream().anyMatch(filePath::startsWith))
        .findFirst();
  }

  List<Path> allSourceRoots() {
    return modules.stream().flatMap(m -> m.sourceRoots().stream()).toList();
  }
}
