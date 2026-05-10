package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

class LatheSetupTest {

  @Test
  void detectsCompleteSetupFromEffectiveModel() {
    final var project = project();
    final var build = new Build();
    final var management = new PluginManagement();
    management.addPlugin(compilerPlugin("1.2.3", true));
    build.setPluginManagement(management);
    build.addPlugin(syncPlugin("1.2.3", true));
    project.getModel().setBuild(build);

    final var report = LatheSetup.inspect(project, List.of(project));

    assertThat(report.compilerDependencyConfigured()).isTrue();
    assertThat(report.compilerIdConfigured()).isTrue();
    assertThat(report.syncExecutionConfigured()).isTrue();
    assertThat(report.complete()).isTrue();
    assertThat(report.versionMismatch()).isFalse();
  }

  @Test
  void reportsMissingCompilerIdAndSyncExecution() {
    final var project = project();
    final var build = new Build();
    build.addPlugin(compilerPlugin("1.2.3", false));
    build.addPlugin(syncPlugin("1.2.3", false));
    project.getModel().setBuild(build);

    final var report = LatheSetup.inspect(project, List.of(project));

    assertThat(report.compilerDependencyConfigured()).isTrue();
    assertThat(report.compilerIdConfigured()).isFalse();
    assertThat(report.syncExecutionConfigured()).isFalse();
    assertThat(report.complete()).isFalse();
  }

  @Test
  void detectsVersionMismatch() {
    final var project = project();
    final var build = new Build();
    build.addPlugin(compilerPlugin("1.2.3", true));
    build.addPlugin(syncPlugin("1.2.4", true));
    project.getModel().setBuild(build);

    final var report = LatheSetup.inspect(project, List.of(project));

    assertThat(report.versionMismatch()).isTrue();
  }

  private static MavenProject project() {
    final var model = new Model();
    model.setGroupId("com.example");
    model.setArtifactId("demo");
    model.setVersion("1.0.0");
    return new MavenProject(model);
  }

  private static Plugin compilerPlugin(final String version, final boolean compilerId) {
    final var plugin = new Plugin();
    plugin.setGroupId("org.apache.maven.plugins");
    plugin.setArtifactId("maven-compiler-plugin");
    plugin.addDependency(dependency("io.github.ag-libs", "lathe-compiler", version));
    if (compilerId) {
      final var config = new Xpp3Dom("configuration");
      final var compilerIdNode = new Xpp3Dom("compilerId");
      compilerIdNode.setValue("lathe");
      config.addChild(compilerIdNode);
      plugin.setConfiguration(config);
    }
    return plugin;
  }

  private static Plugin syncPlugin(final String version, final boolean syncGoal) {
    final var plugin = new Plugin();
    plugin.setGroupId("io.github.ag-libs");
    plugin.setArtifactId("lathe-maven-plugin");
    plugin.setVersion(version);
    final var execution = new PluginExecution();
    if (syncGoal) {
      execution.addGoal("sync");
    }
    plugin.addExecution(execution);
    return plugin;
  }

  private static Dependency dependency(
      final String groupId, final String artifactId, final String version) {
    final var dependency = new Dependency();
    dependency.setGroupId(groupId);
    dependency.setArtifactId(artifactId);
    dependency.setVersion(version);
    return dependency;
  }
}
