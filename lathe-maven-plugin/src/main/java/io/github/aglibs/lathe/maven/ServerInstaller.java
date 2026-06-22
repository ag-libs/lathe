package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.util.artifact.JavaScopes;

final class ServerInstaller {

  private static final String ARTIFACT_ID = "lathe-server";

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
    final var version = PluginProps.version();
    final var versionDir = LatheLayout.serverVersionDir(version);
    final var launcherScript = versionDir.resolve(LatheLayout.LAUNCHER_SCRIPT);

    final var jars = resolveServerJars();
    final var modulePath = jars.stream().map(Path::toString).collect(Collectors.joining(":"));
    final var script = renderLauncherScript(modulePath);

    try {
      Files.createDirectories(versionDir);
      if (Files.exists(launcherScript)
          && Files.isExecutable(launcherScript)
          && script.equals(Files.readString(launcherScript, StandardCharsets.UTF_8))) {
        log.debug("[server] launcher unchanged — skipping write");
        updateCurrentLink(versionDir);
        return;
      }
      final boolean isUpdate = Files.exists(launcherScript);
      FileUtil.writeAtomically(versionDir, launcherScript, script, true);
      log.info(
          "[server] %s launcher at %s"
              .formatted(isUpdate ? "updated" : "installed", launcherScript));
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to write launcher script", e);
    }

    updateCurrentLink(versionDir);
  }

  private List<Path> resolveServerJars() throws SyncException {
    final var artifact =
        new DefaultArtifact(PluginProps.groupId(), ARTIFACT_ID, "jar", PluginProps.version());
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
          "lathe:sync failed to resolve server artifacts for version %s"
              .formatted(PluginProps.version()),
          e);
    }
  }

  private void updateCurrentLink(final Path versionDir) throws SyncException {
    final var currentLink = LatheLayout.currentLink();
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
    // java.net.http: not in the default module graph; Error Prone's WellKnownMutability references
    // HttpClient and throws ClassNotFoundException without this.
    // jdk.unsupported: Gson's module-info declares `requires static jdk.unsupported` (optional).
    // Without this, jdk.unsupported is absent from the module graph and sun.misc.Unsafe is
    // invisible to Gson, causing UnsafeAllocator to fall back to its "give up" stub. LSP4J types
    // like TypeHierarchyItem have no no-args constructor and cannot be deserialized otherwise.
    //
    // Classpath javac plugins (e.g. Error Prone, loaded via -Xplugin: on the processor path) run
    // in the unnamed module. They access javac internals directly and need ALL-UNNAMED exports.
    // Without these, didSave full passes that replay -Xplugin:ErrorProne throw IllegalAccessError.
    //
    // google-java-format is a named module on the module path and uses module-qualified exports.
    return """
        #!/bin/sh
        exec java \\
          --add-modules java.net.http,jdk.unsupported \\
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
