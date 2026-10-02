package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
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
// in-process engine; anything Lathe cannot run in-process (eclipse, palantir) maps to "none", so it
// never emits a diff the project's own spotless:check would reject.
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
    return spotless == null ? null : fromConfig((Xpp3Dom) spotless.getConfiguration());
  }

  // Maps a spotless <configuration> to the style, or null when it does not format Java.
  // (Package-private for tests.)
  static WorkspaceStyleData fromConfig(final Xpp3Dom config) {
    final Xpp3Dom java = config == null ? null : config.getChild("java");
    if (java == null) {
      return null;
    }

    final Xpp3Dom googleJavaFormat = java.getChild("googleJavaFormat");
    if (googleJavaFormat != null) {
      return googleStyle(googleJavaFormat);
    }

    return new WorkspaceStyleData(
        new FormatterSpec("none", List.of()), new IndentSpec("editorconfig", 0, 0));
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
