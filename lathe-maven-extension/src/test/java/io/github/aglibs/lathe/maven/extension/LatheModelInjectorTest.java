package io.github.aglibs.lathe.maven.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

class LatheModelInjectorTest {

  private static final String VERSION = "9.9.9";

  private static MavenProject project() {
    final var model = new Model();
    model.setBuild(new Build());
    return new MavenProject(model);
  }

  private static Plugin compilerPlugin(final MavenProject project) {
    return project
        .getBuild()
        .getPluginsAsMap()
        .get("org.apache.maven.plugins:maven-compiler-plugin");
  }

  private static Plugin lathePlugin(final MavenProject project) {
    return project.getBuild().getPluginsAsMap().get("io.github.ag-libs:lathe-maven-plugin");
  }

  @Test
  void injectCompilerShim_freshProject_setsPropertyAndAddsCompilerDependency() {
    final MavenProject project = project();

    new LatheModelInjector(VERSION).injectCompilerShim(project);

    assertThat(project.getProperties().getProperty("maven.compiler.compilerId")).isEqualTo("lathe");
    assertThat(compilerPlugin(project).getDependencies())
        .extracting(Dependency::getGroupId, Dependency::getArtifactId, Dependency::getVersion)
        .containsExactly(tuple("io.github.ag-libs", "lathe-compiler", VERSION));
  }

  @Test
  void injectCompilerShim_staleCompilerDependency_overwritesVersionWithoutDuplicate() {
    final MavenProject project = project();
    final var compiler = new Plugin();
    compiler.setGroupId("org.apache.maven.plugins");
    compiler.setArtifactId("maven-compiler-plugin");
    final var stale = new Dependency();
    stale.setGroupId("io.github.ag-libs");
    stale.setArtifactId("lathe-compiler");
    stale.setVersion("0.0.1");
    compiler.getDependencies().add(stale);
    project.getBuild().getPlugins().add(compiler);

    new LatheModelInjector(VERSION).injectCompilerShim(project);

    assertThat(compilerPlugin(project).getDependencies())
        .extracting(Dependency::getArtifactId, Dependency::getVersion)
        .containsExactly(tuple("lathe-compiler", VERSION));
  }

  @Test
  void injectCaptureDependency_freshProject_addsTestScopedJunit() {
    final MavenProject project = project();

    new LatheModelInjector(VERSION).injectCaptureDependency(project);

    assertThat(project.getDependencies())
        .extracting(
            Dependency::getGroupId,
            Dependency::getArtifactId,
            Dependency::getVersion,
            Dependency::getScope)
        .containsExactly(tuple("io.github.ag-libs", "lathe-junit", VERSION, "test"));
  }

  @Test
  void injectCaptureDependency_staleJunit_overwritesVersionWithoutDuplicate() {
    final MavenProject project = project();
    final var stale = new Dependency();
    stale.setGroupId("io.github.ag-libs");
    stale.setArtifactId("lathe-junit");
    stale.setVersion("0.0.1");
    stale.setScope("test");
    project.getDependencies().add(stale);

    new LatheModelInjector(VERSION).injectCaptureDependency(project);

    assertThat(project.getDependencies())
        .extracting(Dependency::getArtifactId, Dependency::getVersion)
        .containsExactly(tuple("lathe-junit", VERSION));
  }

  @Test
  void injectRootExecutions_freshRoot_bindsInitAndSyncAtPluginVersion() {
    final MavenProject root = project();

    new LatheModelInjector(VERSION).injectRootExecutions(root);

    final Plugin plugin = lathePlugin(root);
    assertThat(plugin.getVersion()).isEqualTo(VERSION);
    assertThat(plugin.getExecutions())
        .extracting(PluginExecution::getId, e -> e.getGoals())
        .containsExactlyInAnyOrder(
            tuple("lathe-init", List.of("init")), tuple("lathe-sync", List.of("sync")));
  }

  @Test
  void injectRootExecutions_syncGoalAlreadyBound_doesNotDuplicateIt() {
    final MavenProject root = project();
    final var plugin = new Plugin();
    plugin.setGroupId("io.github.ag-libs");
    plugin.setArtifactId("lathe-maven-plugin");
    final var userSync = new PluginExecution();
    userSync.setId("my-sync");
    userSync.addGoal("sync");
    plugin.getExecutions().add(userSync);
    root.getBuild().getPlugins().add(plugin);

    new LatheModelInjector(VERSION).injectRootExecutions(root);

    assertThat(lathePlugin(root).getExecutions())
        .filteredOn(e -> e.getGoals().contains("sync"))
        .hasSize(1);
    assertThat(lathePlugin(root).getExecutions())
        .anySatisfy(e -> assertThat(e.getGoals()).containsExactly("init"));
  }
}
