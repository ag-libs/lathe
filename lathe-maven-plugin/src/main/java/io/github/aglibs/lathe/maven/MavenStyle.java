package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.IndentSpec;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import io.github.aglibs.lathe.install.StyleWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

// Detects the workspace style for StyleWriter from the reactor's spotless-maven-plugin.
// googleJavaFormat, palantirJavaFormat, and eclipse run in-process from the project's pinned jars;
// any other formatter is disabled (none) rather than guessed at, so Lathe never fights the
// project's own formatter. A committed lathe-style.json can still select an external command.
final class MavenStyle {

  private static final String SPOTLESS_PLUGIN_KEY = "com.diffplug.spotless:spotless-maven-plugin";
  // Formatters whose indentation Lathe does not derive leave it to the project's .editorconfig.
  private static final IndentSpec EDITORCONFIG_INDENT = new IndentSpec("editorconfig", 0, 0);

  private final FormatterResolver resolver;
  private final Log log;

  MavenStyle(final FormatterResolver resolver, final Log log) {
    this.resolver = resolver;
    this.log = log;
  }

  // Writes the style with its formatter resolved, and returns it (null when the build has no
  // Spotless Java formatter) so the caller can grant what the formatter needs.
  WorkspaceStyleData write(
      final Path workspaceRoot, final MavenProject rootProject, final List<MavenProject> projects) {
    final Optional<MavenProject> styleSource = styleSource(rootProject, projects);
    if (styleSource.isEmpty()) {
      StyleWriter.delete(workspaceRoot);
      return null;
    }

    final Plugin spotless = spotlessPlugin(styleSource.get());
    final WorkspaceStyleData detected = detect(spotless);
    if (LatheFlags.FORMATTER_NONE.equals(detected.formatter().engine())) {
      log.warn(
          "[sync] formatter Spotless java formatter has no in-process engine, formatting disabled;"
              + " a committed lathe-style.json can run it as an external command");
    }

    final FormatterSpec formatter =
        withLocalProfile(detected.formatter(), styleSource.get().getBasedir().toPath())
            .map(spec -> resolver.resolve(spec, spotless.getVersion()))
            .orElse(detected.formatter());
    final var style = new WorkspaceStyleData(formatter, detected.indent());
    StyleWriter.write(workspaceRoot, style);
    return style;
  }

  // The Eclipse profile as an absolute path, resolved against the root project as Spotless resolves
  // it; empty when it is not a local file (Spotless also takes URLs and classpath resources), which
  // leaves the formatter unresolved and so disabled.
  private Optional<FormatterSpec> withLocalProfile(final FormatterSpec spec, final Path basedir) {
    final String profile = spec.options().get(LatheFlags.FORMAT_FILE);
    if (profile == null) {
      return Optional.of(spec);
    }

    final Path file = basedir.resolve(profile).normalize();
    if (!Files.isRegularFile(file)) {
      log.warn(
          "[sync] formatter %s profile %s is not a local file, formatting disabled"
              .formatted(spec.engine(), profile));
      return Optional.empty();
    }

    final var options = new HashMap<>(spec.options());
    options.put(LatheFlags.FORMAT_FILE, file.toString());
    return Optional.of(
        new FormatterSpec(
            spec.engine(), spec.command(), spec.version(), options, spec.classpath()));
  }

  // The project whose Spotless configuration is the workspace style: the root when it runs
  // Spotless, else any Java module when all of them run the same one. Modules running different
  // ones, or some none, get no style: one workspace formatter would reformat code their build
  // leaves alone.
  Optional<MavenProject> styleSource(
      final MavenProject rootProject, final List<MavenProject> projects) {
    if (detect(rootProject) != null) {
      return Optional.of(rootProject);
    }

    final List<MavenProject> javaModules =
        projects.stream().filter(ReactorProjects::compilesJava).toList();
    final List<MavenProject> formatted =
        javaModules.stream().filter(project -> detect(project) != null).toList();
    if (formatted.isEmpty()) {
      log.debug("[sync] no spotless java formatter — skipping style.json");
      return Optional.empty();
    }

    if (formatted.size() < javaModules.size()
        || formatted.stream().map(MavenStyle::spotlessIdentity).distinct().count() > 1) {
      log.warn(
          ("[sync] spotless java formatter not the same in every java module (%d of %d run it),"
                  + " formatting disabled; configure it once for all of them, or in the root")
              .formatted(formatted.size(), javaModules.size()));
      return Optional.empty();
    }

    return Optional.of(formatted.get(0));
  }

  static WorkspaceStyleData detect(final MavenProject project) {
    return detect(spotlessPlugin(project));
  }

  // What makes two projects format alike: the plugin version and the style it maps to.
  private static List<Object> spotlessIdentity(final MavenProject project) {
    return Arrays.asList(spotlessPlugin(project).getVersion(), detect(project));
  }

  private static WorkspaceStyleData detect(final Plugin spotless) {
    return spotless == null ? null : fromConfig((Xpp3Dom) spotless.getConfiguration());
  }

  private static Plugin spotlessPlugin(final MavenProject rootProject) {
    return rootProject.getBuild() == null
        ? null
        : rootProject.getBuild().getPluginsAsMap().get(SPOTLESS_PLUGIN_KEY);
  }

  // Maps a spotless <configuration> to a style, or null when it formats no Java. Package-private
  // for tests.
  static WorkspaceStyleData fromConfig(final Xpp3Dom config) {
    final Xpp3Dom java = config == null ? null : config.getChild("java");
    if (java == null) {
      return null;
    }

    final Xpp3Dom googleJavaFormat = java.getChild("googleJavaFormat");
    if (googleJavaFormat != null) {
      final String style = styleOf(googleJavaFormat, LatheFlags.FORMAT_STYLE_GOOGLE);
      return pinnedStyle(
          LatheFlags.FORMAT_STYLE_AOSP.equals(style)
              ? LatheFlags.FORMATTER_AOSP
              : LatheFlags.FORMATTER_GOOGLE,
          googleJavaFormat,
          style);
    }

    final Xpp3Dom palantirJavaFormat = java.getChild("palantirJavaFormat");
    if (palantirJavaFormat != null) {
      return pinnedStyle(
          LatheFlags.FORMATTER_PALANTIR,
          palantirJavaFormat,
          styleOf(palantirJavaFormat, LatheFlags.FORMAT_STYLE_PALANTIR));
    }

    final Xpp3Dom eclipse = java.getChild("eclipse");
    if (eclipse != null) {
      final String profile = childValue(eclipse, LatheFlags.FORMAT_FILE);
      return new WorkspaceStyleData(
          new FormatterSpec(
              LatheFlags.FORMATTER_ECLIPSE,
              List.of(),
              childValue(eclipse, "version"),
              profile.isEmpty() ? Map.of() : Map.of(LatheFlags.FORMAT_FILE, profile),
              List.of()),
          EDITORCONFIG_INDENT);
    }

    return new WorkspaceStyleData(
        new FormatterSpec(LatheFlags.FORMATTER_NONE, List.of()), EDITORCONFIG_INDENT);
  }

  // A formatter run in-process from its pinned jars. Only GOOGLE style indents by 2; AOSP and
  // PALANTIR indent by 4.
  private static WorkspaceStyleData pinnedStyle(
      final String engine, final Xpp3Dom step, final String style) {
    final var formatter =
        new FormatterSpec(
            engine, List.of(), childValue(step, "version"), stepOptions(step), List.of());
    return new WorkspaceStyleData(
        formatter,
        LatheFlags.FORMAT_STYLE_GOOGLE.equals(style)
            ? new IndentSpec("google", 2, 4)
            : new IndentSpec("google", 4, 8));
  }

  private static String styleOf(final Xpp3Dom step, final String defaultStyle) {
    final String style = childValue(step, LatheFlags.FORMAT_STYLE).toUpperCase(Locale.ROOT);
    return style.isEmpty() ? defaultStyle : style;
  }

  // The step's configured options, keyed by their Spotless element names; unset ones are left for
  // FormatterResolver to fill with Spotless's defaults.
  private static Map<String, String> stepOptions(final Xpp3Dom step) {
    return Stream.concat(
            Stream.of(LatheFlags.FORMAT_GROUP_ARTIFACT), LatheFlags.FORMAT_STEP_OPTIONS.stream())
        .filter(option -> !childValue(step, option).isEmpty())
        .collect(
            Collectors.toUnmodifiableMap(option -> option, option -> childValue(step, option)));
  }

  private static String childValue(final Xpp3Dom parent, final String name) {
    final Xpp3Dom child = parent.getChild(name);
    return child == null || child.getValue() == null ? "" : child.getValue().trim();
  }
}
