package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.JavaArgfile;
import io.github.aglibs.lathe.core.LatheLayout;
import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleDescriptor.Version;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServerInstaller {

  private static final Logger LOG = LoggerFactory.getLogger(ServerInstaller.class);

  // jdk.compiler internals the MCP launcher grants to ALL-UNNAMED: it runs entirely on the
  // classpath, so its in-process javac is in the unnamed module there.
  private static final String[] JAVAC_EXPORT_PACKAGES = {
    "api", "code", "comp", "file", "main", "model", "parser", "processing", "tree", "util"
  };
  private static final String[] JAVAC_OPEN_PACKAGES = {"code", "comp"};

  // java.net.http is not in the default module graph, and lathe-server does not declare it since
  // it never uses it, but Error Prone (loaded as a classpath javac plugin, -Xplugin:ErrorProne)
  // references HttpClient when it loads.
  private static final List<String> COMMON_JVM_ARGS = List.of("--add-modules", "java.net.http");

  // What install() resolves, each with its runtime closure. Gradle declares them as configurations
  // before it resolves anything.
  public static final String SERVER_COORDINATES = latheCoordinates(PluginProps.SERVER_ARTIFACT_ID);
  public static final String MCP_SERVER_COORDINATES =
      latheCoordinates(PluginProps.MCP_SERVER_ARTIFACT_ID);

  private final ArtifactResolver artifacts;

  public ServerInstaller(final ArtifactResolver artifacts) {
    this.artifacts = artifacts;
  }

  // Installs the server + MCP launchers for this plugin's version into an immutable,
  // version-addressed dir shared across workspaces, and returns it so the caller can copy them into
  // each workspace's .lathe/. Version-correct discovery is per-workspace (see LauncherCopier);
  // `latest` is only the best-effort copy for user-global MCP.
  public Path install() throws SyncException {
    final String version = PluginProps.version();
    final Path versionDir = LatheLayout.serverVersionDir(version);

    final List<Path> serverJars = resolveServerJars();
    final List<Path> mcpJars = resolveMcpServerJars();

    try {
      JavaArgfile.write(
          versionDir,
          versionDir.resolve(LatheLayout.SERVER_ARGS_FILE),
          serverArgs(serverJars),
          JavaArgfile.NATIVE_CHARSET);
      JavaArgfile.write(
          versionDir,
          versionDir.resolve(LatheLayout.MCP_ARGS_FILE),
          mcpArgs(mcpJars),
          JavaArgfile.NATIVE_CHARSET);
      writeLauncher(
          versionDir,
          LatheLayout.LAUNCHER_SCRIPT,
          new LauncherScript(version, LatheLayout.SERVER_ARGS_FILE).render());
      writeLauncher(
          versionDir,
          LatheLayout.MCP_LAUNCHER_SCRIPT,
          new LauncherScript(version, LatheLayout.MCP_ARGS_FILE).render());
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to install server files", e);
    }

    updateLatest();
    removeLegacyCurrentLink();
    return versionDir;
  }

  private void updateLatest() {
    try {
      LauncherCopier.copyNewestToLatest(LatheLayout.serversDir(), LatheLayout.latestDir());
    } catch (final IOException e) {
      LOG.debug("[server] could not update latest: %s".formatted(e.getMessage()));
    }
  }

  // Deletes a leftover `current` symlink so an un-upgraded client fails loudly rather than silently
  // running a stale server. Guarded to a symlink so a real directory is never removed.
  private void removeLegacyCurrentLink() {
    final Path currentLink = LatheLayout.legacyCurrentLink();
    try {
      if (Files.isSymbolicLink(currentLink)) {
        Files.delete(currentLink);
        LOG.info("[server] removed stale current symlink");
      }
    } catch (final IOException e) {
      LOG.debug("[server] could not remove stale current symlink: %s".formatted(e.getMessage()));
    }
  }

  // The JDK's version ordering: numeric parts as numbers, a pre-release (-SNAPSHOT) before its
  // release. A directory whose name is no version is not a server install.
  static Path newestVersionDir(final Path serversDir) {
    try {
      return FileUtil.subdirectories(serversDir).stream()
          .filter(path -> serversDir.equals(path.getParent()))
          .flatMap(path -> versionOf(path).map(version -> Map.entry(version, path)).stream())
          .max(Map.Entry.comparingByKey())
          .map(Map.Entry::getValue)
          .orElse(null);
    } catch (final IOException e) {
      return null;
    }
  }

  private static Optional<Version> versionOf(final Path versionDir) {
    try {
      return Optional.of(Version.parse(versionDir.getFileName().toString()));
    } catch (final IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private void writeLauncher(final Path versionDir, final String scriptName, final String script)
      throws IOException {
    final var launcherScript = versionDir.resolve(scriptName);
    final boolean isUpdate = Files.exists(launcherScript);
    if (!FileUtil.writeIfChanged(versionDir, launcherScript, script, true)) {
      LOG.debug("[server] %s unchanged — skipping write".formatted(scriptName));
      return;
    }

    LOG.info(
        "[server] %s %s at %s"
            .formatted(isUpdate ? "updated" : "installed", scriptName, launcherScript));
  }

  // Classpath javac plugins (e.g. Error Prone) and a project's pinned formatter run in the unnamed
  // module and need javac internals exported to it. Those grants are per workspace, in
  // .lathe/jvm.args, copied from the build's own -J / .mvn/jvm.config flags; the server, a named
  // module, needs none itself.
  static List<String> serverArgs(final List<Path> jars) {
    return Stream.of(
            COMMON_JVM_ARGS,
            List.of(
                "--module-path",
                pathList(jars),
                "-m",
                "io.github.aglibs.lathe.server/io.github.aglibs.lathe.server.LatheServer"))
        .flatMap(List::stream)
        .toList();
  }

  // On the classpath lathe-server's module-info is ignored, so LatheEngine and the in-process javac
  // it drives run in the unnamed module, which needs jdk.compiler internals exported to it.
  static List<String> mcpArgs(final List<Path> jars) {
    return Stream.of(
            COMMON_JVM_ARGS,
            javacAccess("--add-exports", JAVAC_EXPORT_PACKAGES),
            javacAccess("--add-opens", JAVAC_OPEN_PACKAGES),
            List.of("-cp", pathList(jars), "io.github.aglibs.lathe.mcp.LatheMcpServer"))
        .flatMap(List::stream)
        .toList();
  }

  private static List<String> javacAccess(final String flag, final String... packages) {
    return Arrays.stream(packages)
        .flatMap(
            pkg ->
                Stream.of(flag, "jdk.compiler/com.sun.tools.javac.%s=ALL-UNNAMED".formatted(pkg)))
        .toList();
  }

  private static String pathList(final List<Path> jars) {
    return jars.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
  }

  Path resolveRunnerJar() throws SyncException {
    return artifacts.resolve(latheCoordinates(PluginProps.TEST_RUNNER_ARTIFACT_ID));
  }

  private List<Path> resolveServerJars() throws SyncException {
    return artifacts.resolveTransitive(SERVER_COORDINATES);
  }

  // The full runtime closure of lathe-mcp-server (itself, lathe-server, lathe-core and the MCP SDK)
  // for the classpath launcher.
  private List<Path> resolveMcpServerJars() throws SyncException {
    return artifacts.resolveTransitive(MCP_SERVER_COORDINATES);
  }

  private static String latheCoordinates(final String artifactId) {
    return "%s:%s:%s".formatted(PluginProps.groupId(), artifactId, PluginProps.version());
  }

  // The runner plus the JUnit launcher and engine for replay: the build's test runner brings its
  // own launcher, so the captured classpath lacks it. Resolved at the project's JUnit versions.
  public List<Path> resolveRunnerClasspath(final Collection<String> externalCoordinates)
      throws SyncException {
    final var classpath = new ArrayList<Path>();
    classpath.add(resolveRunnerJar());
    classpath.addAll(resolveJUnitSupportJars(externalCoordinates));
    return List.copyOf(classpath);
  }

  private List<Path> resolveJUnitSupportJars(final Collection<String> externalCoordinates)
      throws SyncException {
    final String platformVersion =
        versionOf(externalCoordinates, "org.junit.platform:junit-platform-commons");
    final String jupiterVersion =
        versionOf(externalCoordinates, "org.junit.jupiter:junit-jupiter-api");
    if (platformVersion == null || jupiterVersion == null) {
      LOG.debug("[sync] no JUnit Jupiter dependency detected — skipping runner support jars");
      return List.of();
    }

    final var jars = new ArrayList<Path>();
    jars.addAll(
        resolveTransitiveJars("org.junit.platform", "junit-platform-launcher", platformVersion));
    jars.addAll(resolveTransitiveJars("org.junit.jupiter", "junit-jupiter-engine", jupiterVersion));
    return List.copyOf(jars);
  }

  // The version of groupId:artifactId among groupId:artifactId:version coordinates.
  private static String versionOf(
      final Collection<String> coordinates, final String groupArtifact) {
    final String prefix = "%s:".formatted(groupArtifact);
    return coordinates.stream()
        .filter(gav -> gav.startsWith(prefix))
        .map(gav -> gav.substring(prefix.length()))
        .findFirst()
        .orElse(null);
  }

  private List<Path> resolveTransitiveJars(
      final String groupId, final String artifactId, final String version) throws SyncException {
    return artifacts.resolveTransitive("%s:%s:%s".formatted(groupId, artifactId, version));
  }
}
