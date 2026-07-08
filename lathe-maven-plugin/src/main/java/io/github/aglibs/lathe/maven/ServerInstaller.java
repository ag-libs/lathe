package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.util.artifact.JavaScopes;

final class ServerInstaller {

  private static final String NEOVIM_BUNDLE_RESOURCE =
      "/META-INF/lathe/%s".formatted(LatheLayout.NEOVIM_BUNDLE);
  private static final String MARKER_SCHEMA = "schema";
  private static final String MARKER_BUNDLE_SIZE = "bundleSize";
  private static final String MARKER_BUNDLE_MODIFIED = "bundleModified";

  private final RepositorySystem repositorySystem;
  private final RepositorySystemSession repoSession;
  private final List<RemoteRepository> remoteRepositories;
  private final Log log;

  ServerInstaller(
      final RepositorySystem repositorySystem,
      final RepositorySystemSession repoSession,
      final List<RemoteRepository> remoteRepositories,
      final Log log) {
    this.repositorySystem = repositorySystem;
    this.repoSession = repoSession;
    this.remoteRepositories = remoteRepositories;
    this.log = log;
  }

  void install() throws SyncException {
    final String version = PluginProps.version();
    final Path versionDir = LatheLayout.serverVersionDir(version);
    final var launcherScript = versionDir.resolve(LatheLayout.LAUNCHER_SCRIPT);

    final var jars = resolveServerJars();
    final String modulePath = jars.stream().map(Path::toString).collect(Collectors.joining(":"));
    final var script = renderLauncherScript(modulePath);

    try {
      Files.createDirectories(versionDir);
      if (Files.exists(launcherScript)
          && Files.isExecutable(launcherScript)
          && script.equals(Files.readString(launcherScript, StandardCharsets.UTF_8))) {
        log.debug("[server] launcher unchanged — skipping write");
      } else {
        final boolean isUpdate = Files.exists(launcherScript);
        FileUtil.writeAtomically(versionDir, launcherScript, script, true);
        log.info(
            "[server] %s launcher at %s"
                .formatted(isUpdate ? "updated" : "installed", launcherScript));
      }
      installNeovim(versionDir);
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to install server files", e);
    }

    updateCurrentLink(versionDir);
  }

  Path resolveRunnerJar() throws SyncException {
    final var artifact =
        new DefaultArtifact(
            PluginProps.groupId(),
            PluginProps.TEST_RUNNER_ARTIFACT_ID,
            "jar",
            PluginProps.version());
    final var request = new ArtifactRequest(artifact, remoteRepositories, null);
    try {
      return repositorySystem
          .resolveArtifact(repoSession, request)
          .getArtifact()
          .getFile()
          .toPath();
    } catch (final ArtifactResolutionException e) {
      throw new SyncException(
          "lathe:sync failed to resolve lathe-test-runner artifact for version %s"
              .formatted(PluginProps.version()),
          e);
    }
  }

  private void installNeovim(final Path versionDir) throws IOException {
    final URL bundleUrl = ServerInstaller.class.getResource(NEOVIM_BUNDLE_RESOURCE);
    if (bundleUrl == null) {
      throw new IOException("Neovim runtime bundle not found");
    }

    final URLConnection connection = bundleUrl.openConnection();
    connection.setUseCaches(false);
    final long bundleSize = connection.getContentLengthLong();
    final long bundleModified = connection.getLastModified();
    final Path neovimDir = versionDir.resolve(LatheLayout.NEOVIM_DIR);
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
    final Path neovimDir = versionDir.resolve(LatheLayout.NEOVIM_DIR);
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
    final Path marker = neovimDir.resolve(LatheLayout.NEOVIM_MARKER);
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
    try (final var out = Files.newOutputStream(neovimDir.resolve(LatheLayout.NEOVIM_MARKER))) {
      properties.store(out, null);
    }
  }

  private List<Path> resolveServerJars() throws SyncException {
    return resolveTransitiveJars(
        PluginProps.groupId(), PluginProps.SERVER_ARTIFACT_ID, PluginProps.version());
  }

  /**
   * The runner jar plus whatever JUnit Platform launcher/engine jars the replay JVM needs.
   * Surefire's own JUnit Platform provider carries junit-platform-launcher (and auto-detects the
   * matching engine, e.g. junit-jupiter-engine) as a provider-internal dependency -- it never
   * touches the project's own classpath, so captured test-launch.json never records it. Resolved
   * here, at sync time, against whatever JUnit Platform/Jupiter version the reactor actually uses,
   * so the versions match what the project resolved rather than a hardcoded pin.
   */
  List<Path> resolveRunnerClasspath(final Map<String, Artifact> externalArtifacts)
      throws SyncException {
    final var classpath = new ArrayList<Path>();
    classpath.add(resolveRunnerJar());
    classpath.addAll(resolveJUnitSupportJars(externalArtifacts));
    return List.copyOf(classpath);
  }

  private List<Path> resolveJUnitSupportJars(final Map<String, Artifact> externalArtifacts)
      throws SyncException {
    final String platformVersion =
        versionOf(externalArtifacts, "org.junit.platform", "junit-platform-commons");
    final String jupiterVersion =
        versionOf(externalArtifacts, "org.junit.jupiter", "junit-jupiter-api");
    if (platformVersion == null || jupiterVersion == null) {
      log.debug("[sync] no JUnit Jupiter dependency detected — skipping runner support jars");
      return List.of();
    }

    final var jars = new ArrayList<Path>();
    jars.addAll(
        resolveTransitiveJars("org.junit.platform", "junit-platform-launcher", platformVersion));
    jars.addAll(resolveTransitiveJars("org.junit.jupiter", "junit-jupiter-engine", jupiterVersion));
    return List.copyOf(jars);
  }

  private static String versionOf(
      final Map<String, Artifact> externalArtifacts,
      final String groupId,
      final String artifactId) {
    return externalArtifacts.values().stream()
        .filter(a -> groupId.equals(a.getGroupId()) && artifactId.equals(a.getArtifactId()))
        .map(Artifact::getVersion)
        .findFirst()
        .orElse(null);
  }

  private List<Path> resolveTransitiveJars(
      final String groupId, final String artifactId, final String version) throws SyncException {
    final var artifact = new DefaultArtifact(groupId, artifactId, "jar", version);
    final var dep = new Dependency(artifact, JavaScopes.RUNTIME);
    final var collectRequest = new CollectRequest(dep, remoteRepositories);
    final var depRequest = new DependencyRequest(collectRequest, null);
    try {
      return repositorySystem
          .resolveDependencies(repoSession, depRequest)
          .getArtifactResults()
          .stream()
          .filter(ArtifactResult::isResolved)
          .map(r -> r.getArtifact().getFile().toPath())
          .toList();
    } catch (final DependencyResolutionException e) {
      throw new SyncException(
          "lathe:sync failed to resolve %s:%s:%s".formatted(groupId, artifactId, version), e);
    }
  }

  private void updateCurrentLink(final Path versionDir) throws SyncException {
    final Path currentLink = LatheLayout.currentLink();
    try {
      Files.createDirectories(currentLink.getParent());
      Files.deleteIfExists(currentLink);
      Files.createSymbolicLink(currentLink, versionDir);
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to update current server symlink", e);
    }

    log.debug("[server] current → %s".formatted(versionDir));
  }

  static String renderLauncherScript(final String modulePath) {
    // java.net.http: not in the default module graph and not declared in module-info.java because
    // lathe-server does not use it directly. Error Prone loads as a classpath javac plugin
    // (-Xplugin:ErrorProne) and runs inside the lathe-server JVM. Its WellKnownMutability class
    // references HttpClient at class-load time and throws ClassNotFoundException if java.net.http
    // is absent from the module graph. jdk.unsupported is declared in module-info.java.
    //
    // Classpath javac plugins (e.g. Error Prone, loaded via -Xplugin: on the processor path) run
    // in the unnamed module. They access javac internals directly and need ALL-UNNAMED exports.
    // Without these, didSave full passes that replay -Xplugin:ErrorProne throw IllegalAccessError.
    //
    // google-java-format is a named module on the module path and uses module-qualified exports.
    return """
        #!/bin/sh
        exec java \\
          --add-modules java.net.http \\
        %s%s%s%s  --module-path %s \\
          -m io.github.aglibs.lathe.server/io.github.aglibs.lathe.server.LatheServer "$@"
        """
        .formatted(
            javacAccessLines(
                "--add-exports",
                "ALL-UNNAMED",
                "api",
                "code",
                "comp",
                "file",
                "main",
                "model",
                "parser",
                "processing",
                "tree",
                "util"),
            javacAccessLines("--add-opens", "ALL-UNNAMED", "code", "comp"),
            javacAccessLines(
                "--add-exports",
                "com.google.googlejavaformat",
                "api",
                "code",
                "comp",
                "file",
                "main",
                "model",
                "parser",
                "tree",
                "util"),
            javacAccessLines("--add-opens", "com.google.googlejavaformat", "code", "comp"),
            modulePath);
  }

  private static String javacAccessLines(
      final String flag, final String target, final String... pkgs) {
    final var sb = new StringBuilder();
    for (final var pkg : pkgs) {
      sb.append("  %s jdk.compiler/com.sun.tools.javac.%s=%s \\\n".formatted(flag, pkg, target));
    }
    return sb.toString();
  }
}
