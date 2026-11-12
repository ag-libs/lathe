package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import io.github.aglibs.lathe.install.jdk.JdkSourceResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// .lathe/style.json: the formatter and indent the build tool detected for the workspace.
public final class StyleWriter {

  private static final Logger LOG = LoggerFactory.getLogger(StyleWriter.class);

  private StyleWriter() {}

  public static void write(final Path workspaceRoot, final WorkspaceStyleData style) {
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    try {
      if (FileUtil.writeIfChanged(
          latheDir, latheDir.resolve(LatheLayout.STYLE_FILE), Json.toJson(style), false)) {
        LOG.info("[sync] style formatter=%s".formatted(style.formatter().engine()));
      }
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to write style.json", e);
    }

    unrunnable(workspaceRoot, style.formatter()).ifPresent(LOG::warn);
  }

  // The server runs the formatter on the JDK the sync wrote to .lathe/java-home, the build's
  // compiling one, which may be older than the JVM the build itself runs on (a Gradle toolchain):
  // a formatter release built for a newer Java cannot run there. Said on every sync; the style is
  // still written, for a server started on a newer JDK.
  static Optional<String> unrunnable(final Path workspaceRoot, final FormatterSpec formatter) {
    final OptionalInt needed = FormatterSteps.requiredJava(formatter);
    final OptionalInt server = serverJava(workspaceRoot);
    if (needed.isEmpty() || server.isEmpty() || needed.getAsInt() <= server.getAsInt()) {
      return Optional.empty();
    }

    return Optional.of(
        ("[sync] formatter %s %s needs Java %d, but the server runs on Java %d (the build's"
                + " compiling JDK, unless %s names another): declare a version that runs on Java %d"
                + " in %s, or set %s to a JDK %d or later")
            .formatted(
                formatter.engine(),
                formatter.version(),
                needed.getAsInt(),
                server.getAsInt(),
                LauncherScript.JAVA_HOME_ENV,
                server.getAsInt(),
                LatheLayout.STYLE_SHARED_FILE,
                LauncherScript.JAVA_HOME_ENV,
                needed.getAsInt()));
  }

  // The JDK the launcher starts the server on: LATHE_JAVA_HOME when the build sees it, else the
  // build's compiling JDK.
  private static OptionalInt serverJava(final Path workspaceRoot) {
    final String override = System.getenv(LauncherScript.JAVA_HOME_ENV);
    final Path javaHomeFile =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.JAVA_HOME_FILE);
    if ((override == null || override.isBlank()) && !Files.isRegularFile(javaHomeFile)) {
      return OptionalInt.empty();
    }

    try {
      final Path home =
          override != null && !override.isBlank()
              ? Path.of(override)
              : Path.of(Files.readString(javaHomeFile, StandardCharsets.UTF_8).trim());
      final String version = JdkSourceResolver.resolveAt(home).version();
      return OptionalInt.of(Runtime.Version.parse(version).feature());
    } catch (final IOException | IllegalArgumentException e) {
      return OptionalInt.empty();
    }
  }

  // A build that no longer has a style: the server must not keep formatting with the last one.
  public static void delete(final Path workspaceRoot) {
    final Path file = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.STYLE_FILE);
    try {
      if (Files.deleteIfExists(file)) {
        LOG.info("[sync] style removed, formatting off");
      }
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to delete style.json", e);
    }
  }
}
