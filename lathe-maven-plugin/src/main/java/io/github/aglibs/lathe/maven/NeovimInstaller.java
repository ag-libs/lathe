package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.install.SyncException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.apache.maven.plugin.logging.Log;

// Unpacks the bundled Neovim client into a server version dir. The bundle ships only in the Maven
// plugin jar (it owns the client sources and the assembly), so this stays here rather than in the
// shared installer; other front-ends install their editor client through its own channel.
final class NeovimInstaller {

  private static final String NVIM_BUNDLE_RESOURCE =
      "/META-INF/lathe/%s".formatted(LatheLayout.NVIM_BUNDLE);
  private static final String MARKER_SCHEMA = "schema";
  private static final String MARKER_BUNDLE_SIZE = "bundleSize";
  private static final String MARKER_BUNDLE_MODIFIED = "bundleModified";

  private final Log log;

  NeovimInstaller(final Log log) {
    this.log = log;
  }

  void install(final Path versionDir) throws SyncException {
    try {
      installFromResource(versionDir);
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to install the Neovim runtime", e);
    }
  }

  private void installFromResource(final Path versionDir) throws IOException {
    final URL bundleUrl = NeovimInstaller.class.getResource(NVIM_BUNDLE_RESOURCE);
    if (bundleUrl == null) {
      throw new IOException("Neovim runtime bundle not found");
    }

    final URLConnection connection = bundleUrl.openConnection();
    connection.setUseCaches(false);
    final long bundleSize = connection.getContentLengthLong();
    final long bundleModified = connection.getLastModified();
    final Path neovimDir = versionDir.resolve(LatheLayout.NVIM_DIR);
    if (isNeovimCurrent(neovimDir, bundleSize, bundleModified)) {
      log.debug("[server] Neovim runtime unchanged — skipping unzip");
      return;
    }

    try (final InputStream in = connection.getInputStream()) {
      installNeovimBundle(in, versionDir, bundleSize, bundleModified);
      log.debug("[server] installed Neovim runtime at %s".formatted(versionDir));
    }
  }

  static boolean installNeovimBundle(
      final InputStream bundle,
      final Path versionDir,
      final long bundleSize,
      final long bundleModified)
      throws IOException {
    final Path neovimDir = versionDir.resolve(LatheLayout.NVIM_DIR);
    if (isNeovimCurrent(neovimDir, bundleSize, bundleModified)) {
      return false;
    }

    if (Files.exists(neovimDir)) {
      FileUtil.deleteDir(neovimDir);
    }
    Files.createDirectories(neovimDir);
    FileUtil.unzip(bundle, neovimDir);
    writeNeovimMarker(neovimDir, bundleSize, bundleModified);
    return true;
  }

  private static boolean isNeovimCurrent(
      final Path neovimDir, final long bundleSize, final long bundleModified) {
    final Path marker = neovimDir.resolve(LatheLayout.NVIM_MARKER);
    if (!Files.exists(marker)) {
      return false;
    }

    final var properties = new Properties();
    try (final InputStream in = Files.newInputStream(marker)) {
      properties.load(in);
      return LatheLayout.SCHEMA_VERSION.equals(properties.getProperty(MARKER_SCHEMA))
          && Long.toString(bundleSize).equals(properties.getProperty(MARKER_BUNDLE_SIZE))
          && Long.toString(bundleModified).equals(properties.getProperty(MARKER_BUNDLE_MODIFIED));
    } catch (final IOException e) {
      return false;
    }
  }

  private static void writeNeovimMarker(
      final Path neovimDir, final long bundleSize, final long bundleModified) throws IOException {
    final var properties = new Properties();
    properties.setProperty(MARKER_SCHEMA, LatheLayout.SCHEMA_VERSION);
    properties.setProperty(MARKER_BUNDLE_SIZE, Long.toString(bundleSize));
    properties.setProperty(MARKER_BUNDLE_MODIFIED, Long.toString(bundleModified));
    try (final var out = Files.newOutputStream(neovimDir.resolve(LatheLayout.NVIM_MARKER))) {
      properties.store(out, null);
    }
  }
}
