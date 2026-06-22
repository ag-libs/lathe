package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.LatheFlags;
import javax.inject.Inject;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
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
    if (session.getRequest().getGoals().stream().anyMatch(g -> g.contains("lathe:sync"))) {
      getLog()
          .warn("[sync] direct invocation is not supported — run mvn process-test-classes instead");
      return;
    }

    if (LatheFlags.isDisabled()) {
      getLog().info("[sync] disabled (CI or lathe.skip) — skipping");
      return;
    }

    if (!session.getCurrentProject().equals(session.getTopLevelProject())) {
      getLog().info("[sync] skipping non top-level project");
      return;
    }

    try {
      new SyncCoordinator(repositorySystem, session, getLog()).run();
    } catch (final SyncException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }
  }
}
