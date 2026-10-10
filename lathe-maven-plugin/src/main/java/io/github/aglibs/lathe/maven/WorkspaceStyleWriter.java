package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.IndentSpec;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import io.github.aglibs.lathe.install.SyncException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

// Derives .lathe/style.json from the reactor's spotless-maven-plugin. googleJavaFormat maps to the
// in-process engine; any other formatter is delegated to `mvn spotless:apply` (command-file), or
// disabled (none) when delegation is opted out — so Lathe never fights the project's own formatter.
final class WorkspaceStyleWriter {

  private static final String SPOTLESS_PLUGIN_KEY = "com.diffplug.spotless:spotless-maven-plugin";

  private final FormatterResolver resolver;
  private final Log log;

  WorkspaceStyleWriter(final FormatterResolver resolver, final Log log) {
    this.resolver = resolver;
    this.log = log;
  }

  // Writes the style with its formatter resolved, and returns it (null when the build has no
  // Spotless Java formatter) so the caller can grant what the formatter needs.
  WorkspaceStyleData write(final Path workspaceRoot, final MavenProject rootProject) {
    final Plugin spotless = spotlessPlugin(rootProject);
    final WorkspaceStyleData detected = detect(spotless);
    if (detected == null) {
      log.debug("[sync] no spotless java formatter — skipping style.json");
      return null;
    }

    final var style =
        new WorkspaceStyleData(
            resolver.resolve(detected.formatter(), spotless.getVersion()), detected.indent());
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    final var stylePath = latheDir.resolve(LatheLayout.STYLE_FILE);
    final var content = Json.toJson(style);
    try {
      if (FileUtil.writeIfChanged(latheDir, stylePath, content, false)) {
        log.info("[sync] style formatter=%s".formatted(style.formatter().engine()));
      }

      return style;
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to write style.json", e);
    }
  }

  static WorkspaceStyleData detect(final MavenProject rootProject) {
    return detect(spotlessPlugin(rootProject));
  }

  private static WorkspaceStyleData detect(final Plugin spotless) {
    return spotless == null
        ? null
        : fromConfig(
            (Xpp3Dom) spotless.getConfiguration(), LatheFlags.isSpotlessDelegationEnabled());
  }

  private static Plugin spotlessPlugin(final MavenProject rootProject) {
    return rootProject.getBuild() == null
        ? null
        : rootProject.getBuild().getPluginsAsMap().get(SPOTLESS_PLUGIN_KEY);
  }

  // Maps a spotless <configuration> to a style, or null when it formats no Java. google runs
  // in-process; other formatters delegate to `mvn spotless:apply` (command-file), else disabled
  // (none). Package-private for tests.
  static WorkspaceStyleData fromConfig(final Xpp3Dom config, final boolean delegate) {
    final Xpp3Dom java = config == null ? null : config.getChild("java");
    if (java == null) {
      return null;
    }

    final Xpp3Dom googleJavaFormat = java.getChild("googleJavaFormat");
    if (googleJavaFormat != null) {
      return googleStyle(googleJavaFormat);
    }

    final FormatterSpec formatter =
        delegate
            ? new FormatterSpec(LatheFlags.FORMATTER_COMMAND_FILE, mavenSpotlessCommand())
            : new FormatterSpec(LatheFlags.FORMATTER_NONE, List.of());
    return new WorkspaceStyleData(formatter, new IndentSpec("editorconfig", 0, 0));
  }

  // Runs `mvn -pl <module> spotless:apply` on the edited file. The engine fills %MODULE%/%FILE%;
  // \Q..\E makes spotless's spotlessFiles regex match the absolute path literally.
  private static List<String> mavenSpotlessCommand() {
    return List.of(
        LatheFlags.FORMAT_MVN_TOKEN,
        "-pl",
        LatheFlags.FORMAT_MODULE_TOKEN,
        "spotless:apply",
        "-DspotlessFiles=\\Q" + LatheFlags.FORMAT_FILE_TOKEN + "\\E");
  }

  private static WorkspaceStyleData googleStyle(final Xpp3Dom googleJavaFormat) {
    final boolean aosp = "AOSP".equalsIgnoreCase(childValue(googleJavaFormat, "style"));
    final var formatter =
        new FormatterSpec(
            aosp ? LatheFlags.FORMATTER_AOSP : LatheFlags.FORMATTER_GOOGLE,
            List.of(),
            childValue(googleJavaFormat, "version"),
            stepOptions(googleJavaFormat),
            List.of());
    return new WorkspaceStyleData(
        formatter, aosp ? new IndentSpec("google", 4, 8) : new IndentSpec("google", 2, 4));
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
