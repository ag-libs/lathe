package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.ParamStore;
import io.github.aglibs.lathe.core.maven.DependencyEntry;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

final class WorkspaceManifest {

  private static final Logger LOG = Logger.getLogger(WorkspaceManifest.class.getName());

  private final Map<Path, String> jarToGav;
  private final String jdkVersion;

  private WorkspaceManifest(final Map<Path, String> jarToGav, final String jdkVersion) {
    this.jarToGav = jarToGav;
    this.jdkVersion = jdkVersion;
  }

  static WorkspaceManifest empty() {
    return new WorkspaceManifest(Map.of(), null);
  }

  static WorkspaceManifest load(final Path workspaceRoot) {
    final var file =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_PROPERTIES);
    if (!Files.exists(file)) {
      return empty();
    }

    try {
      final var props = ParamStore.load(file);
      if (!LatheLayout.SCHEMA_VERSION.equals(props.get("schemaVersion"))) {
        LOG.warning(
            () ->
                "[manifest] unexpected schemaVersion %s — ignoring"
                    .formatted(props.get("schemaVersion")));
        return empty();
      }

      final var jarToGav =
          props.readIndexed("dependencySource", DependencyEntry::readFrom).stream()
              .filter(e -> e.jar() != null)
              .collect(Collectors.toUnmodifiableMap(e -> Path.of(e.jar()), DependencyEntry::gav));
      return new WorkspaceManifest(jarToGav, props.get("jdk.version"));
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[manifest] failed to load workspace properties");
      return empty();
    }
  }

  Optional<String> originLabel(final Element element, final StandardJavaFileManager fm) {
    final var topLevel = DefinitionLocator.topLevelClass(element);
    if (topLevel == null) {
      return Optional.empty();
    }

    final var pkgElement = (PackageElement) topLevel.getEnclosingElement();
    final var enclosingModule = pkgElement.getEnclosingElement();
    try {
      if (enclosingModule instanceof final ModuleElement me && !me.isUnnamed()) {
        return originLabelForModule(
            me.getQualifiedName().toString(), topLevel.getQualifiedName().toString(), fm);
      }

      return classpathLabel(topLevel.getQualifiedName().toString(), fm);
    } catch (final IOException e) {
      LOG.log(
          Level.FINE,
          e,
          () -> "[origin] lookup failed for %s".formatted(topLevel.getQualifiedName()));
      return Optional.empty();
    }
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

    final var moduleLoc = fm.getLocationForModule(StandardLocation.MODULE_PATH, moduleName);
    if (moduleLoc != null) {
      final var jfo = fm.getJavaFileForInput(moduleLoc, qualifiedName, JavaFileObject.Kind.CLASS);
      if (jfo != null) {
        final var gav = extractJarPath(jfo.toUri()).map(jarToGav::get);
        LOG.fine(
            () ->
                "[origin] module %s → uri=%s gav=%s"
                    .formatted(moduleName, jfo.toUri(), gav.orElse(null)));
        return gav.map(s -> moduleName + " (" + s + ")").or(() -> Optional.of(moduleName));
      }
    }

    return Optional.of(moduleName);
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
