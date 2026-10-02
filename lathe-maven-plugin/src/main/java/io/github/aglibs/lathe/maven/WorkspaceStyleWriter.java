package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.IndentSpec;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

// Derives .lathe/style.json from the reactor's spotless-maven-plugin. googleJavaFormat maps to the
// in-process engine; any other formatter is delegated to `mvn spotless:apply` (command-file), or
// disabled (none) when delegation is opted out — so Lathe never fights the project's own formatter.
final class WorkspaceStyleWriter {

  private static final String SPOTLESS_PLUGIN_KEY = "com.diffplug.spotless:spotless-maven-plugin";

  private final Log log;

  WorkspaceStyleWriter(final Log log) {
    this.log = log;
  }

  void write(final Path workspaceRoot, final MavenProject rootProject) {
    final WorkspaceStyleData style = detect(rootProject);
    if (style == null) {
      log.debug("[sync] no spotless java formatter — skipping style.json");
      return;
    }

    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    final var stylePath = latheDir.resolve(LatheLayout.STYLE_FILE);
    final var content = Json.toJson(style);
    try {
      Files.createDirectories(latheDir);
      if (Files.exists(stylePath)
          && content.equals(Files.readString(stylePath, StandardCharsets.UTF_8))) {
        return;
      }

      FileUtil.writeAtomically(latheDir, stylePath, content, false);
      log.info("[sync] style formatter=%s".formatted(style.formatter().engine()));
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to write style.json", e);
    }
  }

  static WorkspaceStyleData detect(final MavenProject rootProject) {
    final Plugin spotless =
        rootProject.getBuild() == null
            ? null
            : rootProject.getBuild().getPluginsAsMap().get(SPOTLESS_PLUGIN_KEY);
    return spotless == null
        ? null
        : fromConfig(
            (Xpp3Dom) spotless.getConfiguration(), LatheFlags.isSpotlessDelegationEnabled());
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
    final Xpp3Dom styleEl = googleJavaFormat.getChild("style");
    final boolean aosp = styleEl != null && "AOSP".equalsIgnoreCase(styleEl.getValue().trim());
    return aosp
        ? new WorkspaceStyleData(
            new FormatterSpec("aosp", List.of()), new IndentSpec("google", 4, 8))
        : new WorkspaceStyleData(
            new FormatterSpec("google", List.of()), new IndentSpec("google", 2, 4));
  }
}
