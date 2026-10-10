package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

public final class ServerInstaller {

  // jdk.compiler internals the MCP launcher grants to ALL-UNNAMED: it runs entirely on the
  // classpath, so google-java-format is in the unnamed module there.
  private static final String[] JAVAC_EXPORT_PACKAGES = {
    "api", "code", "comp", "file", "main", "model", "parser", "processing", "tree", "util"
  };
  private static final String[] JAVAC_OPEN_PACKAGES = {"code", "comp"};

  // stdout is the LSP channel; route JVM startup errors and -Xlog output to stderr so they reach
  // the editor's log.
  private static final String STDOUT_GUARD_JVM_OPTS =
      "-XX:+DisplayVMOutputToStderr -Xlog:disable -Xlog:all=warning:stderr";

  private final ArtifactResolver artifactResolver;
  private final List<RemoteRepository> remoteRepositories;
  private final Log log;

  public ServerInstaller(
      final RepositorySystem repositorySystem,
      final RepositorySystemSession repoSession,
      final List<RemoteRepository> remoteRepositories,
      final Log log) {
    this.artifactResolver = new ArtifactResolver(repositorySystem, repoSession);
    this.remoteRepositories = remoteRepositories;
    this.log = log;
  }

  // Installs the server + MCP launchers for this plugin's version into an immutable,
  // version-addressed dir shared across workspaces, and returns it so the caller can link each
  // workspace's .lathe/ at it. Version-correct discovery is per-workspace (see
  // WorkspaceLauncherLinker); `latest` is only the best-effort pointer for user-global MCP.
  public Path install() throws SyncException {
    final String version = PluginProps.version();
    final Path versionDir = LatheLayout.serverVersionDir(version);

    final String modulePath = pathList(resolveServerJars());
    final String mcpClasspath = pathList(resolveMcpServerJars());

    try {
      Files.createDirectories(versionDir);
      writeLauncher(versionDir, LatheLayout.LAUNCHER_SCRIPT, renderLauncherScript(modulePath));
      writeLauncher(
          versionDir, LatheLayout.MCP_LAUNCHER_SCRIPT, renderMcpLauncherScript(mcpClasspath));
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to install server files", e);
    }

    updateLatestLink();
    removeLegacyCurrentLink();
    return versionDir;
  }

  // Repoints the machine-global `latest` symlink at the newest installed version. By newest version
  // (not last synced) so syncing an older repo cannot drag it backward.
  private void updateLatestLink() {
    final Path newest = newestVersionDir(LatheLayout.serversDir());
    if (newest == null) {
      return;
    }

    try {
      if (FileUtil.linkSymbolic(LatheLayout.latestLink(), newest)) {
        log.info("[server] latest → %s".formatted(newest.getFileName()));
      }
    } catch (final IOException e) {
      log.debug("[server] could not update latest symlink: %s".formatted(e.getMessage()));
    }
  }

  // Deletes a leftover `current` symlink so an un-upgraded client fails loudly rather than silently
  // running a stale server. Guarded to a symlink so a real directory is never removed.
  private void removeLegacyCurrentLink() {
    final Path currentLink = LatheLayout.legacyCurrentLink();
    try {
      if (Files.isSymbolicLink(currentLink)) {
        Files.delete(currentLink);
        log.info("[server] removed stale current symlink");
      }
    } catch (final IOException e) {
      log.debug("[server] could not remove stale current symlink: %s".formatted(e.getMessage()));
    }
  }

  static Path newestVersionDir(final Path serversDir) {
    try {
      return FileUtil.subdirectories(serversDir).stream()
          .filter(path -> serversDir.equals(path.getParent()))
          .max(Comparator.comparing(ServerInstaller::versionOf))
          .orElse(null);
    } catch (final IOException e) {
      return null;
    }
  }

  private static ComparableVersion versionOf(final Path versionDir) {
    return new ComparableVersion(versionDir.getFileName().toString());
  }

  private void writeLauncher(final Path versionDir, final String scriptName, final String script)
      throws IOException {
    final var launcherScript = versionDir.resolve(scriptName);
    final boolean isUpdate = Files.exists(launcherScript);
    if (!FileUtil.writeIfChanged(versionDir, launcherScript, script, true)) {
      log.debug("[server] %s unchanged — skipping write".formatted(scriptName));
      return;
    }

    log.info(
        "[server] %s %s at %s"
            .formatted(isUpdate ? "updated" : "installed", scriptName, launcherScript));
  }

  private static String pathList(final List<Path> jars) {
    return jars.stream().map(Path::toString).collect(Collectors.joining(":"));
  }

  Path resolveRunnerJar() throws SyncException {
    return artifactResolver.resolve(
        "%s:%s:%s"
            .formatted(
                PluginProps.groupId(), PluginProps.TEST_RUNNER_ARTIFACT_ID, PluginProps.version()),
        remoteRepositories);
  }

  private List<Path> resolveServerJars() throws SyncException {
    return resolveTransitiveJars(
        PluginProps.groupId(), PluginProps.SERVER_ARTIFACT_ID, PluginProps.version());
  }

  // The full runtime closure of lathe-mcp-server (itself + lathe-server + lathe-core + the MCP
  // SDK),
  // for the classpath launcher.
  private List<Path> resolveMcpServerJars() throws SyncException {
    return resolveTransitiveJars(
        PluginProps.groupId(), PluginProps.MCP_SERVER_ARTIFACT_ID, PluginProps.version());
  }

  /**
   * The runner jar plus whatever JUnit Platform launcher/engine jars the replay JVM needs.
   * Surefire's own JUnit Platform provider carries junit-platform-launcher (and auto-detects the
   * matching engine, e.g. junit-jupiter-engine) as a provider-internal dependency -- it never
   * touches the project's own classpath, so captured test-launch.json never records it. Resolved
   * here, at sync time, against whatever JUnit Platform/Jupiter version the reactor actually uses,
   * so the versions match what the project resolved rather than a hardcoded pin.
   */
  public List<Path> resolveRunnerClasspath(final Map<String, Artifact> externalArtifacts)
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
    return artifactResolver.resolveTransitive(
        "%s:%s:%s".formatted(groupId, artifactId, version), remoteRepositories);
  }

  static String renderLauncherScript(final String modulePath) {
    // java.net.http: not in the default module graph and not declared in module-info.java because
    // lathe-server does not use it directly. Error Prone loads as a classpath javac plugin
    // (-Xplugin:ErrorProne) and runs inside the lathe-server JVM. Its WellKnownMutability class
    // references HttpClient at class-load time and throws ClassNotFoundException if java.net.http
    // is absent from the module graph. jdk.unsupported is declared in module-info.java.
    //
    // Classpath javac plugins (e.g. Error Prone) run in the unnamed module and need javac
    // internals exported to ALL-UNNAMED. Those grants are per workspace, in .lathe/jvm.args, copied
    // from the build's own -J / .mvn/jvm.config flags; the server itself needs none. A project's
    // pinned formatter also runs in the unnamed module (an isolated classloader), so lathe:sync
    // adds the grants it needs to the same file.
    return """
        #!/bin/sh
        %s%sexec "$java_bin" %s $jvm_args ${LATHE_JVM_OPTS:-} \\
          --add-modules java.net.http \\
          --module-path %s \\
          -m io.github.aglibs.lathe.server/io.github.aglibs.lathe.server.LatheServer "$@"
        """
        .formatted(javaResolvePrologue(), jvmArgsPrologue(), STDOUT_GUARD_JVM_OPTS, modulePath);
  }

  static String renderMcpLauncherScript(final String classpath) {
    // Classpath launcher: on the classpath lathe-server's module-info is ignored, so LatheEngine
    // and
    // the in-process javac it drives run in the unnamed module. That javac needs the same
    // jdk.compiler
    // internals the editor launcher grants, but targeted at ALL-UNNAMED — there is no named module
    // here.
    return """
        #!/bin/sh
        %s%sexec "$java_bin" %s $jvm_args ${LATHE_JVM_OPTS:-} \\
          --add-modules java.net.http \\
        %s%s  -cp %s \\
          io.github.aglibs.lathe.mcp.LatheMcpServer "$@"
        """
        .formatted(
            javaResolvePrologue(),
            jvmArgsPrologue(),
            STDOUT_GUARD_JVM_OPTS,
            javacAccessLines("--add-exports", "ALL-UNNAMED", JAVAC_EXPORT_PACKAGES),
            javacAccessLines("--add-opens", "ALL-UNNAMED", JAVAC_OPEN_PACKAGES),
            classpath);
  }

  // Picks the JDK for the server's in-process compiler: LATHE_JAVA_HOME, else the build JDK in
  // .lathe/java-home (relative to the server cwd = workspace root), else PATH java. Shared by both
  // launchers. In a method body, not a field, so the plugin descriptor's QDOX parser skips the
  // block.
  // Shared with the sync script, which runs the build on the same JDK the server launcher picks.
  public static String javaResolvePrologue() {
    return """
        jhome="${LATHE_JAVA_HOME:-}"
        if [ -z "$jhome" ] && [ -r .lathe/java-home ]; then
          jhome="$(cat .lathe/java-home)"
        fi
        java_bin=java
        if [ -n "$jhome" ]; then
          if [ -x "$jhome/bin/java" ]; then
            java_bin="$jhome/bin/java"
          else
            echo "lathe: no bin/java under $jhome; using PATH java" >&2
          fi
        fi
        """;
  }

  // The workspace's module-access flags written by sync, passed as a java @argfile so the script
  // never parses them.
  private static String jvmArgsPrologue() {
    final var argsFile = "%s/%s".formatted(LatheLayout.LATHE_DIR, LatheLayout.JVM_ARGS_FILE);
    return """
        jvm_args=
        if [ -r %s ]; then
          jvm_args=@%s
        fi
        """
        .formatted(argsFile, argsFile);
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
