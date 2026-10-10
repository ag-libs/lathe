package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.ExceptionUtil;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.install.ArtifactResolver;
import io.github.aglibs.lathe.install.SyncException;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

// Completes a detected formatter from the project's own Spotless, so Lathe runs exactly what
// spotless:apply would: the version and step options Spotless defaults to when the POM leaves them
// out, and the formatter's resolved jars. Spotless's library (no dependencies of its own) is
// loaded in an isolated classloader, so it never becomes a dependency of this plugin.
final class FormatterResolver {

  private static final String SPOTLESS_GROUP = "com.diffplug.spotless";
  private static final String SPOTLESS_PLUGIN = "spotless-maven-plugin";
  private static final String SPOTLESS_LIB = "spotless-lib";
  private static final Step GOOGLE_STEP =
      new Step(
          "com.diffplug.spotless.java.GoogleJavaFormatStep",
          "com.google.googlejavaformat:google-java-format",
          true);
  private static final Map<String, Step> STEPS =
      Map.of(
          LatheFlags.FORMATTER_GOOGLE,
          GOOGLE_STEP,
          LatheFlags.FORMATTER_AOSP,
          GOOGLE_STEP,
          LatheFlags.FORMATTER_PALANTIR,
          new Step(
              "com.diffplug.spotless.java.PalantirJavaFormatStep",
              "com.palantir.javaformat:palantir-java-format",
              true));

  private final ArtifactResolver artifacts;
  private final List<RemoteRepository> pluginRepositories;
  private final List<RemoteRepository> projectRepositories;
  private final Log log;

  FormatterResolver(
      final ArtifactResolver artifacts, final MavenProject rootProject, final Log log) {
    this.artifacts = artifacts;
    this.pluginRepositories = rootProject.getRemotePluginRepositories();
    this.projectRepositories = rootProject.getRemoteProjectRepositories();
    this.log = log;
  }

  // Returns spec unchanged for engines that run nothing pinned, and with an empty classpath when
  // resolution fails: the server then reports the formatter unavailable instead of sync failing.
  FormatterSpec resolve(final FormatterSpec spec, final String spotlessVersion) {
    final Step step = STEPS.get(spec.engine());
    if (step == null) {
      return spec;
    }

    try (var spotless = spotlessLib(spotlessVersion)) {
      final FormatterSpec completed =
          withDefaults(spec, spotless.loadClass(step.type()), step.groupArtifact());
      final String coordinates =
          "%s:%s"
              .formatted(
                  completed.options().get(LatheFlags.FORMAT_GROUP_ARTIFACT), completed.version());
      final List<String> classpath =
          artifacts.resolveTransitive(coordinates, projectRepositories).stream()
              .map(Path::toString)
              .toList();
      log.info(
          "[sync] formatter %s %s jars=%d".formatted(spec.engine(), coordinates, classpath.size()));
      return new FormatterSpec(
          completed.engine(),
          completed.command(),
          completed.version(),
          completed.options(),
          classpath);
    } catch (final SyncException | ReflectiveOperationException | IOException e) {
      log.warn(
          "[sync] formatter %s unresolved, formatting disabled: %s (%s)"
              .formatted(spec.engine(), e.getMessage(), ExceptionUtil.rootCause(e).getMessage()));
      return spec;
    }
  }

  static List<String> javacGrants(final FormatterSpec spec) {
    final Step step = STEPS.get(spec.engine());
    return step != null && step.javacGrants() ? LatheFlags.FORMATTER_JAVAC_GRANTS : List.of();
  }

  private URLClassLoader spotlessLib(final String spotlessVersion)
      throws SyncException, IOException {
    final String libVersion =
        artifacts
            .directDependencies(
                "%s:%s:%s".formatted(SPOTLESS_GROUP, SPOTLESS_PLUGIN, spotlessVersion),
                pluginRepositories)
            .stream()
            .map(Dependency::getArtifact)
            .filter(artifact -> SPOTLESS_LIB.equals(artifact.getArtifactId()))
            .map(Artifact::getVersion)
            .findFirst()
            .orElseThrow(
                () ->
                    new SyncException(
                        "%s %s declares no %s"
                            .formatted(SPOTLESS_PLUGIN, spotlessVersion, SPOTLESS_LIB),
                        null));
    final Path jar =
        artifacts.resolve(
            "%s:%s:%s".formatted(SPOTLESS_GROUP, SPOTLESS_LIB, libVersion), pluginRepositories);
    return new URLClassLoader(
        new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
  }

  // Fills what the POM left out with the Spotless step's own defaults (defaultVersion(),
  // defaultGroupArtifact(), default<Option>()); configured values win. groupArtifact is the
  // formatter Lathe resolves when the step names none (palantir's has no defaultGroupArtifact()).
  static FormatterSpec withDefaults(
      final FormatterSpec spec, final Class<?> step, final String groupArtifact)
      throws ReflectiveOperationException {
    final var options = new HashMap<>(spec.options());
    options.putIfAbsent(
        LatheFlags.FORMAT_GROUP_ARTIFACT,
        stepDefault(step, LatheFlags.FORMAT_GROUP_ARTIFACT).orElse(groupArtifact));
    LatheFlags.FORMAT_STEP_OPTIONS.forEach(
        option -> stepDefault(step, option).ifPresent(value -> options.putIfAbsent(option, value)));
    final String version = spec.version().isBlank() ? call(step, "defaultVersion") : spec.version();
    return new FormatterSpec(spec.engine(), spec.command(), version, options, spec.classpath());
  }

  // A step that has no default for an option does not support it.
  private static Optional<String> stepDefault(final Class<?> step, final String option) {
    final String method =
        "default%s%s"
            .formatted(option.substring(0, 1).toUpperCase(Locale.ROOT), option.substring(1));
    try {
      return Optional.of(call(step, method));
    } catch (final ReflectiveOperationException e) {
      return Optional.empty();
    }
  }

  private static String call(final Class<?> step, final String method)
      throws ReflectiveOperationException {
    return String.valueOf(step.getMethod(method).invoke(null));
  }

  // A Spotless step class (its defaults), the formatter's groupId:artifactId, and whether the
  // formatter reaches into javac internals and so needs FORMATTER_JAVAC_GRANTS.
  private record Step(String type, String groupArtifact, boolean javacGrants) {

    private Step {
      ValidCheck.check().notBlank(type, "type").notBlank(groupArtifact, "groupArtifact").validate();
    }
  }
}
