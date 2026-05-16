package io.github.aglibs.lathe.maven;

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

final class ServerInstaller {

  private static final String GROUP_ID = "io.github.ag-libs";
  private static final String ARTIFACT_ID = "lathe-server";
  private static final String SCOPE_RUNTIME = "runtime";
  private static final String ADD_EXPORTS =
      "  --add-exports jdk.compiler/com.sun.tools.javac.%s=com.google.googlejavaformat \\\n";
  private static final String ADD_OPENS =
      "  --add-opens jdk.compiler/com.sun.tools.javac.%s=com.google.googlejavaformat \\\n";

  private final RepositorySystem repositorySystem;
  private final RepositorySystemSession repoSession;
  private final List<RemoteRepository> remoteRepositories;
  private final Log log;
  private final String version;

  ServerInstaller(
      final RepositorySystem repositorySystem,
      final RepositorySystemSession repoSession,
      final List<RemoteRepository> remoteRepositories,
      final Log log,
      final String version) {
    this.repositorySystem = repositorySystem;
    this.repoSession = repoSession;
    this.remoteRepositories = remoteRepositories;
    this.log = log;
    this.version = version;
  }

  void install() throws SyncException {
    final var versionDir = LatheLayout.serverVersionDir(version);
    final var launcherScript = versionDir.resolve(LatheLayout.LAUNCHER_SCRIPT);

    if (Files.exists(launcherScript)) {
      log.debug("[server] launcher already installed at " + launcherScript);
      updateCurrentLink(versionDir);
      return;
    }

    final var jars = resolveServerJars();
    final var modulePath = jars.stream().map(Path::toString).collect(Collectors.joining(":"));
    final var script = renderLauncherScript(modulePath);

    try {
      Files.createDirectories(versionDir);
      Files.writeString(launcherScript, script, StandardCharsets.UTF_8);
      launcherScript.toFile().setExecutable(true, false);
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to write launcher script", e);
    }

    log.info("[server] installed launcher at " + launcherScript);
    updateCurrentLink(versionDir);
  }

  private List<Path> resolveServerJars() throws SyncException {
    final var artifact = new DefaultArtifact(GROUP_ID, ARTIFACT_ID, "jar", version);
    final var dep = new Dependency(artifact, SCOPE_RUNTIME);
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
          "lathe:sync failed to resolve server artifacts for version " + version, e);
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
    log.debug("[server] current → " + versionDir);
  }

  private static String renderLauncherScript(final String modulePath) {
    final var sb = new StringBuilder("#!/bin/sh\nexec java \\\n");
    for (final var pkg :
        new String[] {"api", "code", "comp", "file", "main", "model", "parser", "tree", "util"}) {
      sb.append(ADD_EXPORTS.formatted(pkg));
    }
    for (final var pkg : new String[] {"code", "comp"}) {
      sb.append(ADD_OPENS.formatted(pkg));
    }
    sb.append("  --module-path ").append(modulePath).append(" \\\n");
    sb.append(
        "  -m io.github.aglibs.lathe.server/io.github.aglibs.lathe.server.LatheServer \"$@\"\n");
    return sb.toString();
  }
}
