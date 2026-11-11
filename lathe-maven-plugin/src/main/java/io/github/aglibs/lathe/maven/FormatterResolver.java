package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.ExceptionUtil;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.install.FormatterSteps;
import io.github.aglibs.lathe.install.SyncException;
import io.github.aglibs.lathe.install.maven.MavenArtifactResolver;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.Dependency;

// Completes a formatter from Spotless, so Lathe runs exactly what spotless:apply would: the version
// and step options Spotless defaults to when the project leaves them out, and the formatter's
// resolved jars. Spotless's libraries (no dependencies of their own) are loaded in an isolated
// classloader, so they never become a dependency of this plugin.
final class FormatterResolver {

  private static final String SPOTLESS_PLUGIN = "spotless-maven-plugin";

  // Spotless itself comes from the plugin repositories, the formatter it runs from the project's.
  private final MavenArtifactResolver pluginArtifacts;
  private final MavenArtifactResolver projectArtifacts;
  private final Log log;

  FormatterResolver(
      final MavenArtifactResolver pluginArtifacts,
      final MavenArtifactResolver projectArtifacts,
      final Log log) {
    this.pluginArtifacts = pluginArtifacts;
    this.projectArtifacts = projectArtifacts;
    this.log = log;
  }

  // spotlessVersion is the build's spotless-maven-plugin version, or empty for a build without
  // Spotless, which gets the defaults of the release Lathe pins. Returns spec unchanged for engines
  // that run nothing pinned, and with an empty classpath when resolution fails: the server then
  // reports the formatter unavailable instead of sync failing.
  FormatterSpec resolve(final FormatterSpec spec, final String spotlessVersion) {
    if (!FormatterSteps.pinned(spec)) {
      return spec;
    }

    try (var spotless = spotlessLibraries(spotlessVersion)) {
      final FormatterSpec resolved = FormatterSteps.resolve(spec, spotless, projectArtifacts);
      log.info(
          "[sync] formatter %s %s jars=%d"
              .formatted(spec.engine(), resolved.version(), resolved.classpath().size()));
      return resolved;
    } catch (final SyncException | ReflectiveOperationException | IOException e) {
      log.warn(
          "[sync] formatter %s unresolved, formatting disabled: %s (%s)"
              .formatted(spec.engine(), e.getMessage(), ExceptionUtil.rootCause(e).getMessage()));
      return spec;
    }
  }

  private URLClassLoader spotlessLibraries(final String spotlessVersion) throws IOException {
    final List<String> libraries =
        spotlessVersion.isEmpty() ? FormatterSteps.pinnedSpotless() : declared(spotlessVersion);
    final List<URL> urls = new ArrayList<>();
    for (final String library : libraries) {
      urls.add(pluginArtifacts.resolve(library).toUri().toURL());
    }

    return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
  }

  // The Spotless libraries the project's spotless-maven-plugin declares, at their versions (read
  // from the plugin's POM, without resolving the plugin's dependency tree).
  private List<String> declared(final String spotlessVersion) {
    final List<Artifact> declared =
        pluginArtifacts
            .directDependencies(
                "%s:%s:%s"
                    .formatted(FormatterSteps.SPOTLESS_GROUP, SPOTLESS_PLUGIN, spotlessVersion))
            .stream()
            .map(Dependency::getArtifact)
            .filter(
                artifact -> FormatterSteps.SPOTLESS_LIBRARIES.contains(artifact.getArtifactId()))
            .toList();
    if (declared.size() != FormatterSteps.SPOTLESS_LIBRARIES.size()) {
      throw new SyncException(
          "%s %s does not declare %s"
              .formatted(SPOTLESS_PLUGIN, spotlessVersion, FormatterSteps.SPOTLESS_LIBRARIES),
          null);
    }

    return declared.stream()
        .map(
            library ->
                "%s:%s:%s"
                    .formatted(
                        FormatterSteps.SPOTLESS_GROUP,
                        library.getArtifactId(),
                        library.getVersion()))
        .toList();
  }
}
