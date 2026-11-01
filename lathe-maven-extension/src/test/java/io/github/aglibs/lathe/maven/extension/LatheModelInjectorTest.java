package io.github.aglibs.lathe.maven.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.aglibs.lathe.core.LatheLayout;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LatheModelInjectorTest {

  private static final String VERSION = "9.9.9";
  private static final String OUTPUT_DIR = "/ws/app/target/classes";
  private static final String TEST_OUTPUT_DIR = "/ws/app/target/test-classes";

  private static MavenProject project() {
    final var build = new Build();
    build.setOutputDirectory(OUTPUT_DIR);
    build.setTestOutputDirectory(TEST_OUTPUT_DIR);
    final var model = new Model();
    model.setBuild(build);
    return new MavenProject(model);
  }

  // injectProject declares the fixture's output dirs to the shim through JVM-wide properties.
  @AfterEach
  void clearTreeScopes() {
    System.clearProperty(LatheLayout.Maven.treeProperty(OUTPUT_DIR));
    System.clearProperty(LatheLayout.Maven.treeProperty(TEST_OUTPUT_DIR));
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

  // Maven has already put the compiler plugin, at the project's version, in a module that compiles.
  private static Plugin addCompiler(final MavenProject project, final String version) {
    final var compiler = new Plugin();
    compiler.setGroupId("org.apache.maven.plugins");
    compiler.setArtifactId("maven-compiler-plugin");
    compiler.setVersion(version);
    project.getBuild().getPlugins().add(compiler);
    return compiler;
  }

  @Test
  void injectProject_compilingModule_shimsTheCompilerAtItsVersionAndAddsCapture() {
    final MavenProject project = project();
    addCompiler(project, "3.14.0");

    new LatheModelInjector(VERSION).injectProject(project);

    assertThat(project.getProperties().getProperty("maven.compiler.compilerId")).isEqualTo("lathe");
    assertThat(compilerPlugin(project).getVersion()).isEqualTo("3.14.0");
    assertThat(compilerPlugin(project).getDependencies())
        .extracting(Dependency::getGroupId, Dependency::getArtifactId, Dependency::getVersion)
        .containsExactly(tuple("io.github.ag-libs", "lathe-compiler", VERSION));
    assertThat(project.getDependencies())
        .extracting(Dependency::getArtifactId)
        .containsExactly("lathe-junit");
  }

  // A pom module compiles nothing: a compiler plugin created there, without a version, made Maven
  // look up the newest one online on every build.
  @Test
  void injectProject_moduleWithoutCompiler_addsNothing() {
    final MavenProject project = project();

    new LatheModelInjector(VERSION).injectProject(project);

    assertThat(project.getBuild().getPlugins()).isEmpty();
    assertThat(project.getProperties()).doesNotContainKey("maven.compiler.compilerId");
    assertThat(project.getDependencies()).isEmpty();
  }

  @Test
  void injectProject_staleCompilerDependency_overwritesVersionWithoutDuplicate() {
    final MavenProject project = project();
    final var stale = new Dependency();
    stale.setGroupId("io.github.ag-libs");
    stale.setArtifactId("lathe-compiler");
    stale.setVersion("0.0.1");
    addCompiler(project, "3.14.0").getDependencies().add(stale);

    new LatheModelInjector(VERSION).injectProject(project);

    assertThat(compilerPlugin(project).getDependencies())
        .extracting(Dependency::getArtifactId, Dependency::getVersion)
        .containsExactly(tuple("lathe-compiler", VERSION));
  }

  @Test
  void injectTreeScopes_customOutputDirs_declareEachDirsScopeForTheShim() {
    final MavenProject project = project();
    project.getBuild().setOutputDirectory("/ws/app/out/main-classes");
    project.getBuild().setTestOutputDirectory("/ws/app/out/check-classes");
    final String mainKey = LatheLayout.Maven.treeProperty("/ws/app/out/main-classes");
    final String testKey = LatheLayout.Maven.treeProperty("/ws/app/out/check-classes");
    try {
      new LatheModelInjector(VERSION).injectTreeScopes(project);

      assertThat(System.getProperty(mainKey)).isEqualTo(LatheLayout.CLASSES_DIR);
      assertThat(System.getProperty(testKey)).isEqualTo(LatheLayout.TEST_CLASSES_DIR);
    } finally {
      System.clearProperty(mainKey);
      System.clearProperty(testKey);
    }
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
