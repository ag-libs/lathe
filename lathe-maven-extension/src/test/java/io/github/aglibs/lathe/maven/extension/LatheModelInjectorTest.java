package io.github.aglibs.lathe.maven.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    System.clearProperty(LatheLayout.Maven.buildDirProperty(OUTPUT_DIR));
    System.clearProperty(LatheLayout.Maven.buildDirProperty(TEST_OUTPUT_DIR));
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
  void injectTreeScopes_buildDirectory_declaredForBothOutputDirs() {
    final MavenProject project = project();
    project.getBuild().setDirectory("/ws/app/out");

    new LatheModelInjector(VERSION).injectTreeScopes(project);

    assertThat(System.getProperty(LatheLayout.Maven.buildDirProperty(OUTPUT_DIR)))
        .isEqualTo("/ws/app/out");
    assertThat(System.getProperty(LatheLayout.Maven.buildDirProperty(TEST_OUTPUT_DIR)))
        .isEqualTo("/ws/app/out");
  }

  @Test
  void injectTreeScopes_noBuildDirectory_declaresNone() {
    final MavenProject project = project();

    new LatheModelInjector(VERSION).injectTreeScopes(project);

    assertThat(System.getProperty(LatheLayout.Maven.buildDirProperty(OUTPUT_DIR))).isNull();
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

  // Surefire starts no test JVM for a selection it does not run: an abstract base class or a
  // helper sorting first would leave the module uncaptured (jdbi's core:
  // AbstractJavaTimeArrayTests).
  @Test
  void injectCaptureClass_helpersAndAbstractBasesSortFirst_selectsTheFirstFiveTestNames(
      @TempDir final Path dir) throws IOException {
    final MavenProject project = project();
    for (final String name :
        List.of(
            "AbstractArrayTests",
            "Callback",
            "FooBarType",
            "ArrayTest",
            "BindTests",
            "JdbiTestCase",
            "TestJdbi",
            "TestQuery",
            "ZebraTest")) {
      write(dir.resolve("com/example/%s.java".formatted(name)));
    }

    project.addTestCompileSourceRoot(dir.toString());

    new LatheModelInjector(VERSION).injectCaptureClass(project);

    assertThat(project.getProperties())
        .containsOnly(
            entry("test", "AbstractArrayTests,ArrayTest,BindTests,JdbiTestCase,TestJdbi"),
            entry("surefire.failIfNoSpecifiedTests", "false"));
  }

  // A module that names its own includes (a @Suite) keeps its classes in the selection, after the
  // ones the default includes name; types only, from every test root.
  @Test
  void injectCaptureClass_otherNamesInSeveralRoots_selectedAfterTheTestNames(
      @TempDir final Path dir) throws IOException {
    final MavenProject project = project();
    write(dir.resolve("main/com/example/Gamma.java"));
    write(dir.resolve("main/com/example/package-info.java"));
    write(dir.resolve("main/com/example/MemberTest.java"));
    write(dir.resolve("generated/module-info.java"));
    write(dir.resolve("generated/com/example/AllSuite.java"));
    write(dir.resolve("generated/com/example/Notes.txt"));
    project.addTestCompileSourceRoot(dir.resolve("main").toString());
    project.addTestCompileSourceRoot(dir.resolve("generated").toString());
    project.addTestCompileSourceRoot(dir.resolve("missing").toString());

    new LatheModelInjector(VERSION).injectCaptureClass(project);

    assertThat(project.getProperties())
        .containsOnly(
            entry("test", "MemberTest,AllSuite,Gamma"),
            entry("surefire.failIfNoSpecifiedTests", "false"));
  }

  // Without Java test sources the module keeps running every class; a selection the module makes
  // itself is kept.
  @Test
  void injectCaptureClass_noJavaSourcesOrOwnSelection_leavesTheSelection(@TempDir final Path dir)
      throws IOException {
    final MavenProject withoutSources = project();
    write(dir.resolve("groovy/com/example/AppSpec.groovy"));
    withoutSources.addTestCompileSourceRoot(dir.resolve("groovy").toString());
    final MavenProject ownSelection = project();
    write(dir.resolve("java/com/example/AppTest.java"));
    ownSelection.addTestCompileSourceRoot(dir.resolve("java").toString());
    ownSelection.getProperties().setProperty("test", "SmokeTest");

    new LatheModelInjector(VERSION).injectCaptureClass(withoutSources);
    new LatheModelInjector(VERSION).injectCaptureClass(ownSelection);

    assertThat(withoutSources.getProperties()).isEmpty();
    assertThat(ownSelection.getProperties()).containsOnly(entry("test", "SmokeTest"));
  }

  private static void write(final Path file) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, "", StandardCharsets.UTF_8);
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
