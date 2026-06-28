package io.github.aglibs.lathe.server.module;

import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ModuleNameDiscovery {

  private static final Logger LOG = Logger.getLogger(ModuleNameDiscovery.class.getName());

  private ModuleNameDiscovery() {}

  public static List<String> observableModuleNames(final ModuleSourceConfig config) {
    final var names = new TreeSet<String>();
    names.addAll(systemModuleNames());
    names.addAll(modulePathModuleNames(config));
    return List.copyOf(names);
  }

  static List<String> systemModuleNames() {
    return moduleNames(ModuleFinder.ofSystem());
  }

  static List<String> modulePathModuleNames(final ModuleSourceConfig config) {
    if (config == null) {
      return List.of();
    }

    final List<Path> paths = config.remappedModulepath().stream().filter(Files::exists).toList();
    if (paths.isEmpty()) {
      return List.of();
    }

    return moduleNames(ModuleFinder.of(paths.toArray(Path[]::new)));
  }

  private static List<String> moduleNames(final ModuleFinder finder) {
    try {
      return finder.findAll().stream().map(ref -> ref.descriptor().name()).sorted().toList();
    } catch (final FindException e) {
      LOG.log(Level.FINE, e, () -> "[module-completion] module-path scan failed");
      return List.of();
    }
  }
}
