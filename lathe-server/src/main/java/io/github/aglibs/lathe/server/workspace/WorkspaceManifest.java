package io.github.aglibs.lathe.server.workspace;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.DependencyData;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.server.analysis.DefinitionLocator;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

public final class WorkspaceManifest {

  private static final Logger LOG = Logger.getLogger(WorkspaceManifest.class.getName());

  private final Map<Path, String> jarToGav;
  private final Map<Path, Path> jarToSourceDir;
  private final Map<Path, Path> sourceDirToJar;
  private final String jdkVersion;
  private final Path jdkSourceDir;

  private final Map<Path, List<Path>> sourceDirToClasspath;

  private WorkspaceManifest(
      final Map<Path, String> jarToGav,
      final Map<Path, Path> jarToSourceDir,
      final Map<Path, Path> sourceDirToJar,
      final String jdkVersion,
      final Path jdkSourceDir,
      final Map<Path, List<Path>> sourceDirToClasspath) {
    this.jarToGav = jarToGav;
    this.jarToSourceDir = jarToSourceDir;
    this.sourceDirToJar = sourceDirToJar;
    this.jdkVersion = jdkVersion;
    this.jdkSourceDir = jdkSourceDir;
    this.sourceDirToClasspath = sourceDirToClasspath;
  }

  public static WorkspaceManifest empty() {
    return new WorkspaceManifest(Map.of(), Map.of(), Map.of(), null, null, Map.of());
  }

  public static WorkspaceManifest load(final Path workspaceRoot) {
    final var file =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON);
    if (!Files.exists(file)) {
      return empty();
    }

    try {
      final var data = Json.read(file, WorkspaceManifestData.class);
      if (!LatheLayout.SCHEMA_VERSION.equals(data.schemaVersion())) {
        LOG.warning(
            () ->
                "[manifest] unexpected schemaVersion %s — ignoring"
                    .formatted(data.schemaVersion()));
        return empty();
      }

      final var rawEntries = data.dependencySources();
      final var entries =
          (rawEntries != null ? rawEntries : List.<DependencyData>of())
              .stream().filter(e -> e.jar() != null).toList();
      final var jarToGav =
          entries.stream()
              .collect(Collectors.toUnmodifiableMap(e -> Path.of(e.jar()), DependencyData::gav));
      final var jarToSourceDir =
          entries.stream()
              .filter(e -> e.dir() != null)
              .collect(Collectors.toUnmodifiableMap(e -> Path.of(e.jar()), e -> Path.of(e.dir())));
      final var sourceDirToJar =
          entries.stream()
              .filter(e -> e.dir() != null)
              .collect(Collectors.toUnmodifiableMap(e -> Path.of(e.dir()), e -> Path.of(e.jar())));
      final var sourceDirToClasspath =
          entries.stream()
              .filter(e -> e.dir() != null && e.classpath() != null && !e.classpath().isEmpty())
              .collect(
                  Collectors.toUnmodifiableMap(
                      e -> Path.of(e.dir()), e -> e.classpath().stream().map(Path::of).toList()));
      final var jdk = data.jdk();
      return new WorkspaceManifest(
          jarToGav,
          jarToSourceDir,
          sourceDirToJar,
          jdk != null ? jdk.version() : null,
          jdk != null ? jdk.sourceDir() : null,
          sourceDirToClasspath);
    } catch (final Exception e) {
      LOG.log(Level.WARNING, e, () -> "[manifest] failed to load workspace manifest");
      return empty();
    }
  }

  private record TypeEntry(TypeElement topLevel, String moduleName) {
    String qualifiedName() {
      return topLevel.getQualifiedName().toString();
    }
  }

  private static TypeEntry classify(final Element element) {
    final var topLevel = DefinitionLocator.topLevelClass(element);
    if (topLevel == null) {
      return null;
    }

    final var pkg = (PackageElement) topLevel.getEnclosingElement();
    final var enclosing = pkg.getEnclosingElement();
    final var moduleName =
        enclosing instanceof final ModuleElement me && !me.isUnnamed()
            ? me.getQualifiedName().toString()
            : null;
    return new TypeEntry(topLevel, moduleName);
  }

  public boolean containsFile(final Path file) {
    return externalSourceRootForFile(file).isPresent();
  }

  public List<Path> externalSourceDirs() {
    return Stream.concat(
            jarToSourceDir.values().stream(),
            jdkSourceDir != null ? Stream.of(jdkSourceDir) : Stream.empty())
        .toList();
  }

  public List<Path> depClasspathForFile(final Path file) {
    return externalSourceRootForFile(file)
        .map(
            root -> {
              final var selfJar = sourceDirToJar.get(root);
              final var transitive = sourceDirToClasspath.getOrDefault(root, List.of());
              if (selfJar == null) {
                return transitive;
              }

              final var selfAndTransitive = Stream.concat(Stream.of(selfJar), transitive.stream());
              return Stream.concat(selfAndTransitive, jarToGav.keySet().stream())
                  .distinct()
                  .toList();
            })
        .orElse(List.of());
  }

  public Optional<Path> externalSourceRootForFile(final Path file) {
    if (jdkSourceDir != null && file.startsWith(jdkSourceDir)) {
      return Optional.of(jdkSourceDir);
    }

    return jarToSourceDir.values().stream().filter(file::startsWith).findFirst();
  }

  public Optional<String> jdkModuleForFile(final Path file) {
    if (jdkSourceDir == null || !file.startsWith(jdkSourceDir)) {
      return Optional.empty();
    }

    final Path rel = jdkSourceDir.relativize(file);
    if (rel.getNameCount() == 0) {
      return Optional.empty();
    }

    return Optional.of(rel.getName(0).toString());
  }

  public Optional<String> originLabel(final Element element, final StandardJavaFileManager fm) {
    final var entry = classify(element);
    if (entry == null) {
      return Optional.empty();
    }

    try {
      if (entry.moduleName() != null) {
        return originLabelForModule(entry.moduleName(), entry.qualifiedName(), fm);
      }

      return classpathLabel(entry.qualifiedName(), fm);
    } catch (final IOException e) {
      LOG.log(
          Level.FINE, e, () -> "[origin] lookup failed for %s".formatted(entry.qualifiedName()));
      return Optional.empty();
    }
  }

  public Optional<Path> externalSourceRoot(
      final Element element, final StandardJavaFileManager fm) {
    final var entry = classify(element);
    if (entry == null) {
      return Optional.empty();
    }

    try {
      if (entry.moduleName() != null) {
        return externalSourceRootForModule(entry.moduleName(), entry.qualifiedName(), fm);
      }

      return classpathSourceRoot(entry.qualifiedName(), fm);
    } catch (final IOException e) {
      LOG.log(
          Level.FINE,
          e,
          () -> "[externalSourceRoot] lookup failed for %s".formatted(entry.qualifiedName()));
      return Optional.empty();
    }
  }

  private static Optional<Path> jarForModulePath(
      final String moduleName, final String qualifiedName, final StandardJavaFileManager fm)
      throws IOException {
    final var moduleLoc = fm.getLocationForModule(StandardLocation.MODULE_PATH, moduleName);
    if (moduleLoc == null) {
      return Optional.empty();
    }

    final var jfo = fm.getJavaFileForInput(moduleLoc, qualifiedName, JavaFileObject.Kind.CLASS);
    if (jfo == null) {
      return Optional.empty();
    }

    return extractJarPath(jfo.toUri());
  }

  private Optional<String> originLabelForModule(
      final String moduleName, final String qualifiedName, final StandardJavaFileManager fm)
      throws IOException {
    if (fm.getLocationForModule(StandardLocation.SYSTEM_MODULES, moduleName) != null) {
      final var jdkLabel =
          jdkVersion != null ? moduleName + " (JDK " + jdkVersion + ")" : moduleName;
      LOG.fine(() -> "[origin] module %s → jdk label".formatted(moduleName));
      return Optional.of(jdkLabel);
    }

    final var jar = jarForModulePath(moduleName, qualifiedName, fm);
    final var gav = jar.map(jarToGav::get);
    LOG.fine(
        () ->
            "[origin] module %s → jar=%s gav=%s"
                .formatted(moduleName, jar.orElse(null), gav.orElse(null)));
    return gav.map(s -> moduleName + " (" + s + ")").or(() -> Optional.of(moduleName));
  }

  private Optional<Path> externalSourceRootForModule(
      final String moduleName, final String qualifiedName, final StandardJavaFileManager fm)
      throws IOException {
    if (fm.getLocationForModule(StandardLocation.SYSTEM_MODULES, moduleName) != null) {
      return Optional.ofNullable(jdkSourceDir);
    }

    return jarForModulePath(moduleName, qualifiedName, fm).map(jarToSourceDir::get);
  }

  private Optional<String> classpathLabel(
      final String qualifiedName, final StandardJavaFileManager fm) throws IOException {
    final var jfo =
        fm.getJavaFileForInput(
            StandardLocation.CLASS_PATH, qualifiedName, JavaFileObject.Kind.CLASS);
    if (jfo == null) {
      LOG.fine(() -> "[origin] no class file for %s".formatted(qualifiedName));
      return Optional.empty();
    }

    final var uri = jfo.toUri();
    final var jarPath = extractJarPath(uri);
    final var gav = jarPath.map(jarToGav::get);
    LOG.fine(
        () ->
            "[origin] %s → uri=%s jar=%s gav=%s"
                .formatted(qualifiedName, uri, jarPath.orElse(null), gav.orElse(null)));
    return gav.isPresent() ? gav : extractReactorModuleName(uri);
  }

  private Optional<Path> classpathSourceRoot(
      final String qualifiedName, final StandardJavaFileManager fm) throws IOException {
    final var jfo =
        fm.getJavaFileForInput(
            StandardLocation.CLASS_PATH, qualifiedName, JavaFileObject.Kind.CLASS);
    if (jfo == null) {
      return Optional.empty();
    }

    return extractJarPath(jfo.toUri()).map(jarToSourceDir::get);
  }

  private static Optional<String> extractReactorModuleName(final URI uri) {
    if (!"file".equals(uri.getScheme())) {
      return Optional.empty();
    }

    final var path = Path.of(uri);
    for (int i = 0; i < path.getNameCount() - 1; i++) {
      if (LatheLayout.LATHE_DIR.equals(path.getName(i).toString())) {
        return Optional.of(path.getName(i + 1).toString());
      }
    }

    return Optional.empty();
  }

  private static Optional<Path> extractJarPath(final URI uri) {
    if (!"jar".equals(uri.getScheme())) {
      return Optional.empty();
    }

    final var specific = uri.getSchemeSpecificPart();
    final int sep = specific.indexOf("!/");
    if (sep < 0) {
      return Optional.empty();
    }

    return Optional.of(Path.of(URI.create(specific.substring(0, sep))));
  }
}
