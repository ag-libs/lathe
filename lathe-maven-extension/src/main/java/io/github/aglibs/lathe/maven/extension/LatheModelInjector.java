package io.github.aglibs.lathe.maven.extension;

import io.github.aglibs.lathe.core.LatheLayout;
import java.util.List;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;

/**
 * Mutates a resolved {@link MavenProject} model in memory to add the Lathe build wiring the user
 * would otherwise hand-edit into the POM. Extension-wins: a stale Lathe version is overwritten so
 * the effective build always matches the registered extension version.
 */
final class LatheModelInjector {

  private final String version;

  LatheModelInjector(final String version) {
    this.version = version;
  }

  /** Per-project injection: the compiler shim and the run/test capture dependency. */
  void injectProject(final MavenProject project) {
    injectCompilerShim(project);
    injectCaptureDependency(project);
  }

  void injectCompilerShim(final MavenProject project) {
    final Plugin compiler =
        findOrCreatePlugin(
            project,
            LatheLayout.MAVEN_COMPILER_PLUGIN_GROUP_ID,
            LatheLayout.MAVEN_COMPILER_PLUGIN_ARTIFACT_ID);
    putLatheDependency(compiler.getDependencies(), LatheLayout.COMPILER_ARTIFACT_ID, null);
    // The compiler is selected via the property, not a <compilerId> config node: a node added in
    // afterProjectsRead does not reach the default compile executions (lathe-maven-extension.md
    // §9).
    project
        .getProperties()
        .setProperty(LatheLayout.MAVEN_COMPILER_ID_PROPERTY, LatheLayout.COMPILER_ID);
  }

  void injectCaptureDependency(final MavenProject project) {
    putLatheDependency(project.getDependencies(), LatheLayout.JUNIT_ARTIFACT_ID, "test");
  }

  /**
   * Binds {@code lathe:init} and {@code lathe:sync} on the reactor root only. Mutating the
   * already-resolved top-level model does not propagate to child modules, reproducing the manual
   * {@code <inherited>false</inherited>} setup.
   */
  void injectRootExecutions(final MavenProject topLevelProject) {
    final Plugin plugin =
        findOrCreatePlugin(
            topLevelProject, LatheLayout.LATHE_GROUP_ID, LatheLayout.PLUGIN_ARTIFACT_ID);
    plugin.setVersion(version);
    bindGoal(plugin, LatheLayout.INIT_EXECUTION_ID, LatheLayout.INIT_GOAL);
    bindGoal(plugin, LatheLayout.SYNC_EXECUTION_ID, LatheLayout.SYNC_GOAL);
  }

  private Plugin findOrCreatePlugin(
      final MavenProject project, final String groupId, final String artifactId) {
    return project.getBuild().getPlugins().stream()
        .filter(
            plugin ->
                groupId.equals(plugin.getGroupId()) && artifactId.equals(plugin.getArtifactId()))
        .findFirst()
        .orElseGet(
            () -> {
              final var created = new Plugin();
              created.setGroupId(groupId);
              created.setArtifactId(artifactId);
              project.getBuild().getPlugins().add(created);
              return created;
            });
  }

  private void putLatheDependency(
      final List<Dependency> dependencies, final String artifactId, final String scope) {
    dependencies.stream()
        .filter(
            dependency ->
                LatheLayout.LATHE_GROUP_ID.equals(dependency.getGroupId())
                    && artifactId.equals(dependency.getArtifactId()))
        .findFirst()
        .ifPresentOrElse(
            existing -> existing.setVersion(version),
            () -> {
              final var dependency = new Dependency();
              dependency.setGroupId(LatheLayout.LATHE_GROUP_ID);
              dependency.setArtifactId(artifactId);
              dependency.setVersion(version);
              if (scope != null) {
                dependency.setScope(scope);
              }

              dependencies.add(dependency);
            });
  }

  private void bindGoal(final Plugin plugin, final String executionId, final String goal) {
    final boolean alreadyBound =
        plugin.getExecutions().stream().anyMatch(execution -> execution.getGoals().contains(goal));
    if (alreadyBound) {
      return;
    }

    final var execution = new PluginExecution();
    execution.setId(executionId);
    execution.addGoal(goal);
    plugin.getExecutions().add(execution);
  }
}
