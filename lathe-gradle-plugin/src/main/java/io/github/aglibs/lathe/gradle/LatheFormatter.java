package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.ExceptionUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.install.FormatterSteps;
import io.github.aglibs.lathe.install.SyncException;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.java.TargetJvmEnvironment;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;

// The formatter the root's lathe-style.json declares, else the spring-javaformat plugin the root
// applies, completed from Spotless's defaults (spring's from the plugin's release) and resolved
// through Gradle for the workspace task. Gradle resolves only what a configuration declares, so the
// formatter's coordinates are added as its configuration resolves, once Spotless's step classes
// (the applied plugin's, else the release Lathe pins) say which release and jars.
final class LatheFormatter {

  static final String SPOTLESS_PLUGIN = "com.diffplug.spotless";
  private static final String SPRING_PLUGIN = "io.spring.javaformat";
  private static final String SPOTLESS_CONFIGURATION = "latheSpotless";
  private static final String FORMATTER_CONFIGURATION = "latheFormatter";

  private final Project project;
  // Null when lathe-style.json declares no formatter.
  private final FormatterSpec declared;
  private final FileCollection jars;
  // Why a jar the formatter runs from did not resolve: a lenient view keeps the build going, and a
  // formatter missing one would fail at format time instead of being reported unresolved.
  private final Provider<String> unresolved;
  // The formatter's jars; null until a pinned formatter is declared or the root applies
  // spring-javaformat, so a build without either gets no configurations of Lathe's.
  private ArtifactCollection artifacts;
  private FileCollection pinnedSpotless;
  private boolean completed;
  // Null when nothing pinned is declared or detected, or it cannot be completed.
  private Completion completion;

  private LatheFormatter(final Project project, final FormatterSpec declared) {
    this.project = project;
    this.declared = declared;
    this.jars = project.files((Callable<Object>) this::artifactFiles);
    this.unresolved = project.provider(this::failures);
    if (declared != null) {
      if (FormatterSteps.pinned(declared.engine())) {
        artifacts = formatterArtifacts();
      }

      return;
    }

    // The root's build script applies spring-javaformat only after this runs.
    project
        .getPluginManager()
        .withPlugin(SPRING_PLUGIN, applied -> artifacts = formatterArtifacts());
  }

  private Object artifactFiles() {
    return artifacts != null ? artifacts.getArtifactFiles() : List.of();
  }

  private String failures() {
    if (artifacts == null) {
      return "";
    }

    return artifacts.getFailures().stream()
        .map(failure -> ExceptionUtil.rootCause(failure).getMessage())
        .collect(Collectors.joining("; "));
  }

  private ArtifactCollection formatterArtifacts() {
    final Configuration spotless =
        LatheProjectPlugin.resolvable(
            project,
            SPOTLESS_CONFIGURATION,
            FormatterSteps.pinnedSpotless().toArray(String[]::new));
    spotless.setTransitive(false);
    pinnedSpotless = LatheProjectPlugin.lenient(spotless);
    final Configuration formatter =
        LatheProjectPlugin.configuration(project, FORMATTER_CONFIGURATION, true);
    // A library published with Gradle module metadata (Guava) offers several variants: take the
    // one a JVM application runs, as the formatter runs on the server's JVM.
    final ObjectFactory objects = project.getObjects();
    formatter.attributes(
        attributes -> {
          attributes.attribute(
              Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.JAVA_RUNTIME));
          attributes.attribute(
              Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
          attributes.attribute(
              LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
              objects.named(LibraryElements.class, LibraryElements.JAR));
          attributes.attribute(
              Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.class, Bundling.EXTERNAL));
          attributes.attribute(
              TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
              objects.named(TargetJvmEnvironment.class, TargetJvmEnvironment.STANDARD_JVM));
        });
    formatter.withDependencies(
        dependencies ->
            completed()
                .ifPresent(
                    completed ->
                        completed
                            .jars()
                            .coordinates()
                            .forEach(
                                coordinates ->
                                    dependencies.add(
                                        dependency(coordinates, completed.jars().transitive())))));
    return formatter.getIncoming().artifactView(view -> view.lenient(true)).getArtifacts();
  }

  // Read through the providers, so a changed lathe-style.json invalidates the configuration
  // cache. A malformed file declares no formatter here; the server reports it when it reads it.
  static LatheFormatter of(final Project project) {
    final String style =
        project
            .getProviders()
            .fileContents(
                project.getLayout().getProjectDirectory().file(LatheLayout.STYLE_SHARED_FILE))
            .getAsText()
            .getOrNull();
    if (style == null) {
      return new LatheFormatter(project, null);
    }

    try {
      return new LatheFormatter(project, FormatterSteps.declared(style).orElse(null));
    } catch (final SyncException e) {
      project
          .getLogger()
          .warn("[lathe] {} unreadable: {}", LatheLayout.STYLE_SHARED_FILE, e.getMessage());
      return new LatheFormatter(project, null);
    }
  }

  // The engine lathe-style.json declares, or null when it declares none.
  String declaredEngine() {
    return declared != null ? declared.engine() : null;
  }

  // The engine detected without a declaration (spring, when the root applies spring-javaformat),
  // or null.
  String detectedEngine() {
    return declared == null && project.getPluginManager().hasPlugin(SPRING_PLUGIN)
        ? LatheFlags.FORMATTER_SPRING
        : null;
  }

  // The completed spec, without its jars, or null when nothing pinned is declared or detected, or
  // it cannot be completed. A task input, so it is computed when the configuration cache is stored.
  String completedJson() {
    return completed().map(completed -> Json.toJson(completed.spec())).orElse(null);
  }

  FileCollection jars() {
    return jars;
  }

  // Why some of the formatter's jars did not resolve; empty when all did.
  Provider<String> unresolved() {
    return unresolved;
  }

  // Whether the version came from the Spotless Lathe pins: none was declared, and the root applies
  // no Spotless of its own. A subproject's Spotless may run another.
  boolean pinnedDefaults() {
    return completed().map(Completion::pinnedDefaults).orElse(false);
  }

  // Computed once, when the formatter configuration resolves or the task's inputs are read,
  // whichever comes first; a failure is a warning, as every build runs the workspace task.
  private synchronized Optional<Completion> completed() {
    if (!completed) {
      completion = complete();
      completed = true;
    }

    return Optional.ofNullable(completion);
  }

  // Null when there is nothing to resolve.
  private Completion complete() {
    final FormatterSpec wanted =
        declared != null ? withRootSpringVersion(declared) : detectedSpring();
    if (wanted == null || !FormatterSteps.pinned(wanted.engine())) {
      return null;
    }

    final Optional<FormatterSpec> local =
        FormatterSteps.withLocalProfile(wanted, project.getProjectDir().toPath());
    if (local.isEmpty()) {
      project
          .getLogger()
          .warn(
              "[lathe] formatter {} profile {} is not a local file, formatting disabled",
              wanted.engine(),
              wanted.options().get(LatheFlags.FORMAT_FILE));
      return null;
    }

    // An engine that is no Spotless step (spring) completes without Spotless, and its version
    // owes nothing to the Spotless Lathe pins.
    final boolean spotlessStep = FormatterSteps.spotlessStep(wanted.engine());
    final Object applied = project.getPlugins().findPlugin(SPOTLESS_PLUGIN);
    try (var pinned = applied == null && spotlessStep ? pinnedSpotlessLoader() : null) {
      final ClassLoader spotless = applied != null ? applied.getClass().getClassLoader() : pinned;
      final FormatterSpec spec = FormatterSteps.withDefaults(local.get(), spotless);
      return new Completion(
          spec,
          FormatterSteps.jars(spec, spotless),
          spotlessStep && applied == null && wanted.version().isBlank());
    } catch (final ReflectiveOperationException | IOException | RuntimeException e) {
      project
          .getLogger()
          .warn(
              "[lathe] formatter {} unresolved, formatting disabled: {} ({})",
              wanted.engine(),
              e.getMessage(),
              ExceptionUtil.rootCause(e).getMessage());
      return null;
    }
  }

  // The root's spring-javaformat, unless the root applies Spotless too: the build then formats
  // nothing (the workspace task says why), so the formatter's jars are not resolved for nothing.
  private FormatterSpec detectedSpring() {
    return project.getPluginManager().hasPlugin(SPOTLESS_PLUGIN) ? null : rootSpring();
  }

  // The spring engine at the release of the spring-javaformat plugin the root applies; null when it
  // applies none. Only the root's is read: another project's plugins are out of the root's reach.
  private FormatterSpec rootSpring() {
    final Plugin<?> spring = project.getPlugins().findPlugin(SPRING_PLUGIN);
    if (spring == null) {
      return null;
    }

    final String version = pluginVersion(spring);
    if (version.isEmpty()) {
      project
          .getLogger()
          .warn(
              "[lathe] formatter spring: the {} plugin records no release, so the editor runs the"
                  + " one Lathe pins; declare the version in {}",
              SPRING_PLUGIN,
              LatheLayout.STYLE_SHARED_FILE);
    }

    return new FormatterSpec(LatheFlags.FORMATTER_SPRING, List.of(), version, Map.of(), List.of());
  }

  // A declared spring engine without a version runs the root plugin's release, if it applies one.
  private FormatterSpec withRootSpringVersion(final FormatterSpec spec) {
    if (!LatheFlags.FORMATTER_SPRING.equals(spec.engine()) || !spec.version().isBlank()) {
      return spec;
    }

    final FormatterSpec root = rootSpring();
    return root == null
        ? spec
        : new FormatterSpec(
            spec.engine(), spec.command(), root.version(), spec.options(), spec.classpath());
  }

  // The plugin jar's Implementation-Version, as spring-javaformat's jars record their release;
  // empty, and so the release Lathe pins, when it records none.
  private static String pluginVersion(final Plugin<?> plugin) {
    return Objects.requireNonNullElse(
        plugin.getClass().getPackage().getImplementationVersion(), "");
  }

  private URLClassLoader pinnedSpotlessLoader() {
    final List<Path> libraries = pinnedSpotless.getFiles().stream().map(File::toPath).toList();
    if (libraries.size() != FormatterSteps.SPOTLESS_LIBRARIES.size()) {
      throw new SyncException(
          "could not resolve %s".formatted(FormatterSteps.pinnedSpotless()), null);
    }

    return FormatterSteps.spotlessLoader(libraries);
  }

  private ExternalModuleDependency dependency(final String coordinates, final boolean transitive) {
    final ExternalModuleDependency dependency = project.getDependencyFactory().create(coordinates);
    dependency.setTransitive(transitive);
    return dependency;
  }

  // The completed spec, the jars it runs from, and whether its defaults are the pinned Spotless's.
  private record Completion(FormatterSpec spec, FormatterSteps.Jars jars, boolean pinnedDefaults) {

    private Completion {
      ValidCheck.check().notNull(spec, "spec").notNull(jars, "jars").validate();
    }
  }
}
