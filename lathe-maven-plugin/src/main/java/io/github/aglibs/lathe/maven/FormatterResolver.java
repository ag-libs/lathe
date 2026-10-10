package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.ExceptionUtil;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.install.ArtifactResolver;
import io.github.aglibs.lathe.install.SyncException;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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
  // spotless-lib holds the google/palantir steps, spotless-lib-extra the eclipse step and its
  // lockfiles; neither has dependencies of its own that these defaults need.
  private static final List<String> SPOTLESS_LIBRARIES =
      List.of("spotless-lib", "spotless-lib-extra");
  private static final Step GOOGLE_STEP =
      new Step(
          "com.diffplug.spotless.java.GoogleJavaFormatStep",
          "com.google.googlejavaformat:google-java-format",
          true,
          "");
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
              true,
              ""),
          LatheFlags.FORMATTER_ECLIPSE,
          new Step(
              "com.diffplug.spotless.extra.java.EclipseJdtFormatterStep",
              "org.eclipse.jdt:org.eclipse.jdt.core",
              false,
              "com/diffplug/spotless/extra/eclipse_jdt_formatter/v%s.lockfile"));

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

    try (var spotless = spotlessLibraries(spotlessVersion)) {
      final FormatterSpec completed =
          withDefaults(spec, spotless.loadClass(step.type()), step.groupArtifact());
      final List<Path> jars =
          step.lockfile().isEmpty()
              ? artifacts.resolveTransitive(
                  "%s:%s"
                      .formatted(
                          completed.options().get(LatheFlags.FORMAT_GROUP_ARTIFACT),
                          completed.version()),
                  projectRepositories)
              : lockfileJars(spotless, step.lockfile().formatted(completed.version()));
      final List<String> classpath = jars.stream().map(Path::toString).toList();
      log.info(
          "[sync] formatter %s %s jars=%d"
              .formatted(spec.engine(), completed.version(), classpath.size()));
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

  // The project's own Spotless libraries, at the versions its spotless-maven-plugin declares (read
  // from the plugin's POM, without resolving the plugin's dependency tree).
  private URLClassLoader spotlessLibraries(final String spotlessVersion)
      throws SyncException, IOException {
    final List<Artifact> declared =
        artifacts
            .directDependencies(
                "%s:%s:%s".formatted(SPOTLESS_GROUP, SPOTLESS_PLUGIN, spotlessVersion),
                pluginRepositories)
            .stream()
            .map(Dependency::getArtifact)
            .filter(artifact -> SPOTLESS_LIBRARIES.contains(artifact.getArtifactId()))
            .toList();
    if (declared.size() != SPOTLESS_LIBRARIES.size()) {
      throw new SyncException(
          "%s %s does not declare %s"
              .formatted(SPOTLESS_PLUGIN, spotlessVersion, SPOTLESS_LIBRARIES),
          null);
    }

    final List<URL> urls = new ArrayList<>();
    for (final Artifact library : declared) {
      urls.add(
          artifacts
              .resolve(
                  "%s:%s:%s"
                      .formatted(SPOTLESS_GROUP, library.getArtifactId(), library.getVersion()),
                  pluginRepositories)
              .toUri()
              .toURL());
    }

    return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
  }

  // Exactly the jars Spotless pins for this formatter release, each resolved on its own. A release
  // without a lockfile is one Spotless fetches from an Eclipse P2 site, which Lathe does not.
  private List<Path> lockfileJars(final ClassLoader spotless, final String lockfile)
      throws IOException {
    try (InputStream in = spotless.getResourceAsStream(lockfile)) {
      if (in == null) {
        throw new SyncException(
            "Spotless has no lockfile %s; only lockfile-pinned Eclipse releases are supported"
                .formatted(lockfile),
            null);
      }

      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .map(String::trim)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .map(coordinates -> artifacts.resolve(coordinates, projectRepositories))
          .toList();
    }
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

  // A Spotless step class (its defaults), the formatter's groupId:artifactId, whether the
  // formatter reaches into javac internals and so needs FORMATTER_JAVAC_GRANTS, and the Spotless
  // lockfile (a resource pattern taking the version) pinning its jars, or empty to resolve the
  // artifact's runtime closure.
  private record Step(String type, String groupArtifact, boolean javacGrants, String lockfile) {

    private Step {
      ValidCheck.check()
          .notBlank(type, "type")
          .notBlank(groupArtifact, "groupArtifact")
          .notNull(lockfile, "lockfile")
          .validate();
    }
  }
}
