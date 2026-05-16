package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.maven.dependency.DependencySource;
import io.github.aglibs.lathe.maven.dependency.DependencySourceResolver;
import io.github.aglibs.lathe.maven.dependency.DependencySourceSync;
import io.github.aglibs.lathe.maven.jdk.JdkSourceResolver;
import io.github.aglibs.lathe.maven.jdk.JdkSourceSync;
import java.nio.file.Path;
import java.util.List;
import javax.inject.Inject;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;

@SuppressWarnings("unused")
@Mojo(
    name = "sync",
    aggregator = true,
    defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true)
public final class SyncMojo extends AbstractMojo {

  @Inject private RepositorySystem repositorySystem;

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
  private PluginDescriptor pluginDescriptor;

  @Override
  public void execute() throws MojoExecutionException {
    if (LatheFlags.isDisabled()) {
      getLog().info("[sync] disabled (CI or lathe.skip) — skipping");
      return;
    }

    if (!session.getCurrentProject().equals(session.getTopLevelProject())) {
      getLog().info("[sync] skipping non top-level project");
      return;
    }

    try {
      final var workspaceRoot = session.getTopLevelProject().getBasedir().toPath();
      final var projects = ReactorProjects.sorted(session, workspaceRoot);
      logModules(workspaceRoot, projects);
      final var remoteRepos = ReactorProjects.remoteRepositories(projects);
      final var resolver = new DependencySourceResolver(repositorySystem, session, getLog());
      final var dependencySources =
          resolver.resolve(
              ReactorProjects.externalArtifacts(projects),
              ReactorProjects.artifactClasspaths(projects),
              remoteRepos);
      DependencySourceSync.extract(DependencySource.present(dependencySources), getLog());
      final var jdkSource = JdkSourceResolver.resolve();
      JdkSourceSync.extract(jdkSource, getLog());
      final var installer =
          new ServerInstaller(
              repositorySystem,
              session.getRepositorySession(),
              remoteRepos,
              getLog(),
              pluginDescriptor.getVersion());
      installer.install();
      if (isPartialReactor() && !LatheFlags.isForcedSync()) {
        getLog().debug("[sync] partial reactor (-pl) — skipping workspace.json write");
      } else {
        new WorkspaceManifestWriter(getLog())
            .write(workspaceRoot, dependencySources, jdkSource, pluginDescriptor.getVersion());
      }
    } catch (final SyncException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }
  }

  private boolean isPartialReactor() {
    return !session.getRequest().getSelectedProjects().isEmpty();
  }

  private void logModule(final Path workspaceRoot, final MavenProject project) {
    getLog()
        .info(
            "[sync] module "
                + ReactorProjects.moduleRel(workspaceRoot, project)
                + " "
                + ReactorProjects.gav(project));
  }

  private void logModules(final Path workspaceRoot, final List<MavenProject> projects) {
    projects.forEach(project -> logModule(workspaceRoot, project));
  }
}
