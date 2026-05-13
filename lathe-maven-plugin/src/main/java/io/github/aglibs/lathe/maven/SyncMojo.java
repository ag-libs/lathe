package io.github.aglibs.lathe.maven;

import java.nio.file.Path;
import java.util.List;
import javax.inject.Inject;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
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

  @Override
  public void execute() throws MojoExecutionException {
    if (isDirectSyncInvocation(session.getRequest().getGoals())) {
      throw new MojoExecutionException(
          "Do not run lathe:sync directly. Run mvn process-test-classes instead.");
    }

    if (!session.getCurrentProject().equals(session.getTopLevelProject())) {
      getLog().debug("[sync] skipping non top-level project");
      return;
    }

    try {
      final Path workspaceRoot = session.getTopLevelProject().getBasedir().toPath();
      final List<MavenProject> projects = ReactorProjects.sorted(session, workspaceRoot);
      logModules(workspaceRoot, projects);
      final DependencySourceResolver resolver =
          new DependencySourceResolver(repositorySystem, session, getLog());
      final List<DependencySource> dependencySources =
          resolver.resolve(
              ReactorProjects.externalArtifacts(projects),
              ReactorProjects.remoteRepositories(projects));
      DependencySourceExtractor.extract(DependencySource.present(dependencySources), getLog());
      final JdkSource jdkSource = JdkSourceResolver.resolve();
      JdkSourceExtractor.extract(jdkSource, getLog());
      WorkspaceManifestWriter.write(workspaceRoot, dependencySources, jdkSource, getLog());
    } catch (final SyncException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }
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

  static boolean isDirectSyncInvocation(final List<String> goals) {
    return goals.stream().anyMatch(goal -> goal.contains(":sync"));
  }
}
