package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.maven.dependency.DependencySource;
import io.github.aglibs.lathe.maven.dependency.DependencySourceResolver;
import io.github.aglibs.lathe.maven.dependency.DependencySourceSync;
import io.github.aglibs.lathe.maven.jdk.JdkSource;
import io.github.aglibs.lathe.maven.jdk.JdkSourceResolver;
import io.github.aglibs.lathe.maven.jdk.JdkSourceSync;
import io.github.aglibs.lathe.maven.typeindex.DependencyTypeIndexSync;
import io.github.aglibs.lathe.maven.typeindex.JdkTypeIndexSync;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.repository.RemoteRepository;

final class SyncCoordinator {

  private final RepositorySystem repositorySystem;
  private final MavenSession session;
  private final Log log;

  SyncCoordinator(
      final RepositorySystem repositorySystem, final MavenSession session, final Log log) {
    this.repositorySystem = repositorySystem;
    this.session = session;
    this.log = log;
  }

  void run() throws SyncException {
    final var workspaceRoot = session.getTopLevelProject().getBasedir().toPath();
    final List<MavenProject> projects = ReactorProjects.sorted(session, workspaceRoot);
    logModules(workspaceRoot, projects);
    final List<RemoteRepository> remoteRepos = ReactorProjects.remoteRepositories(projects);
    final Map<String, Artifact> externalArtifacts = ReactorProjects.externalArtifacts(projects);
    final var resolver = new DependencySourceResolver(repositorySystem, session, log);
    final List<DependencySource> dependencySources =
        resolver.resolve(
            externalArtifacts, ReactorProjects.artifactClasspaths(projects), remoteRepos);
    DependencySourceSync.extract(DependencySource.withSources(dependencySources), log);
    DependencyTypeIndexSync.index(externalArtifacts.values(), log);
    final Map<Path, Path> jarToTypeIndex =
        externalArtifacts.values().stream()
            .collect(
                Collectors.toMap(
                    a -> a.getFile().toPath(),
                    DependencyTypeIndexSync::indexPath,
                    (first, ignored) -> first));
    final List<DependencySource> enrichedSources =
        dependencySources.stream().map(s -> enrichWithTypeIndex(s, jarToTypeIndex)).toList();
    final JdkSource jdkSource = JdkSourceResolver.resolve();
    JdkSourceSync.extract(jdkSource, log);
    final JdkSource enrichedJdkSource = JdkTypeIndexSync.index(jdkSource, log);
    new ServerInstaller(repositorySystem, session.getRepositorySession(), remoteRepos, log)
        .install();
    if (isPartialReactor() && !LatheFlags.isForcedSync()) {
      log.debug("[sync] partial reactor (-pl) — skipping workspace.json write");
    } else {
      final List<String> pomPaths =
          projects.stream()
              .map(p -> workspaceRoot.relativize(p.getFile().toPath()).toString())
              .toList();
      new WorkspaceManifestWriter(log)
          .write(
              workspaceRoot, enrichedSources, enrichedJdkSource, PluginProps.version(), pomPaths);
    }
  }

  private static DependencySource enrichWithTypeIndex(
      final DependencySource s, final Map<Path, Path> jarToTypeIndex) {
    final Path idx = s.jar() != null ? jarToTypeIndex.get(s.jar()) : null;
    return idx != null ? s.withTypeIndex(idx) : s;
  }

  private boolean isPartialReactor() {
    return !session.getRequest().getSelectedProjects().isEmpty();
  }

  private void logModules(final Path workspaceRoot, final List<MavenProject> projects) {
    for (final var project : projects) {
      log.info(
          "[sync] module %s %s"
              .formatted(
                  ReactorProjects.moduleRel(workspaceRoot, project), ReactorProjects.gav(project)));
    }
  }
}
