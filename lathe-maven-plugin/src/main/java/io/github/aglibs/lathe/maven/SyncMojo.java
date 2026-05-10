package io.github.aglibs.lathe.maven;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

@SuppressWarnings("unused")
@Mojo(
    name = "sync",
    aggregator = true,
    defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
    requiresDependencyResolution = ResolutionScope.TEST)
public final class SyncMojo extends AbstractMojo {

  private static final int ROOT_REL_LENGTH = 0;

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  @Override
  public void execute() {
    final Path workspaceRoot = session.getTopLevelProject().getBasedir().toPath();
    final List<MavenProject> projects = sortedProjects(workspaceRoot);
    projects.forEach(project -> logModule(workspaceRoot, project));
    projects.forEach(this::logDependencies);
  }

  private List<MavenProject> sortedProjects(final Path workspaceRoot) {
    return session.getProjects().stream()
        .sorted(Comparator.comparing(project -> moduleRel(workspaceRoot, project)))
        .toList();
  }

  private void logModule(final Path workspaceRoot, final MavenProject project) {
    getLog().info("[sync] module " + moduleRel(workspaceRoot, project) + " " + gav(project));
  }

  private void logDependencies(final MavenProject project) {
    project
        .getArtifacts()
        .forEach(
            artifact ->
                getLog().info("[sync] dependency " + gav(artifact) + " from " + gav(project)));
  }

  private static String moduleRel(final Path workspaceRoot, final MavenProject project) {
    final String rel = workspaceRoot.relativize(project.getBasedir().toPath()).toString();
    if (rel.length() == ROOT_REL_LENGTH) {
      return ".";
    }

    return rel;
  }

  private static String gav(final MavenProject project) {
    return "%s:%s:%s"
        .formatted(project.getGroupId(), project.getArtifactId(), project.getVersion());
  }

  private static String gav(final Artifact artifact) {
    return "%s:%s:%s:%s"
        .formatted(
            artifact.getGroupId(),
            artifact.getArtifactId(),
            artifact.getType(),
            artifact.getVersion());
  }
}
