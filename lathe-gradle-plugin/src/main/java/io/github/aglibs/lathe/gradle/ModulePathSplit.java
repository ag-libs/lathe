package io.github.aglibs.lathe.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

// Gradle's module-path rule for a source set with a module-info.java: every entry that is a module
// (a module-info.class, also a versioned one, or an Automatic-Module-Name) goes on the module path,
// whether or not the code requires it, and every other entry on the classpath. Gradle applies it
// inside javac's arguments, where Lathe cannot read it, so the capture applies it again.
final class ModulePathSplit {

  private static final String MODULE_INFO_CLASS = "module-info.class";
  private static final String VERSIONS_DIR = "META-INF/versions/";
  private static final String AUTOMATIC_MODULE_NAME = "Automatic-Module-Name";

  private ModulePathSplit() {}

  // true: the module path, false: the classpath.
  static Map<Boolean, List<String>> split(final List<String> entries) {
    return Map.copyOf(
        entries.stream()
            .collect(
                Collectors.partitioningBy(
                    entry -> isModule(Path.of(entry)), Collectors.toUnmodifiableList())));
  }

  static boolean isModule(final Path entry) {
    if (Files.isDirectory(entry)) {
      return Files.isRegularFile(entry.resolve(MODULE_INFO_CLASS));
    }

    if (!Files.isRegularFile(entry)) {
      return false;
    }

    try (final var jar = new JarFile(entry.toFile())) {
      return jar.stream().anyMatch(file -> isModuleInfo(file.getName()))
          || hasAutomaticModuleName(jar.getManifest());
    } catch (final IOException e) {
      return false;
    }
  }

  private static boolean isModuleInfo(final String name) {
    return name.equals(MODULE_INFO_CLASS)
        || (name.startsWith(VERSIONS_DIR) && name.endsWith("/" + MODULE_INFO_CLASS));
  }

  private static boolean hasAutomaticModuleName(final Manifest manifest) {
    return manifest != null && manifest.getMainAttributes().getValue(AUTOMATIC_MODULE_NAME) != null;
  }
}
