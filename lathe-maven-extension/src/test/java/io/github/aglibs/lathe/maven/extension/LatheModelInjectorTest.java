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
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheModelInjectorTest {

  private static final String VERSION = "9.9.9";
  private static final String OUTPUT_DIR = "/ws/app/target/classes";
  private static final String TEST_OUTPUT_DIR = "/ws/app/target/test-classes";

  private static Plugin plugin(final String groupId, final String artifactId) {
    final var plugin = new Plugin();
    plugin.setGroupId(groupId);
    plugin.setArtifactId(artifactId);
    return plugin;
  }

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

  // A project's nohttp check as Spring projects declare it: maven-checkstyle-plugin with nohttp as
  // its dependency, in an execution of its own over the whole project. Returns that execution's
  // configuration; excludes null leaves the element out.
  private static Xpp3Dom addNohttp(final MavenProject project, final String excludes) {
    final Xpp3Dom configuration = addCheckstyle(project, "io.spring.nohttp", "nohttp-checkstyle");
    if (excludes != null) {
      final var element = new Xpp3Dom("excludes");
      element.setValue(excludes);
      configuration.addChild(element);
    }

    return configuration;
  }

  private static Xpp3Dom addCheckstyle(
      final MavenProject project, final String groupId, final String artifactId) {
    final var dependency = new Dependency();
    dependency.setGroupId(groupId);
    dependency.setArtifactId(artifactId);
    final var configuration = new Xpp3Dom("configuration");
    final var sources = new Xpp3Dom("sourceDirectories");
    sources.setValue("${basedir}");
    configuration.addChild(sources);
    final var execution = new PluginExecution();
    execution.setId("nohttp-checkstyle-validation");
    execution.addGoal("check");
    execution.setConfiguration(configuration);
    final var checkstyle = new Plugin();
    checkstyle.setGroupId("org.apache.maven.plugins");
    checkstyle.setArtifactId("maven-checkstyle-plugin");
    checkstyle.addDependency(dependency);
    checkstyle.addExecution(execution);
    project.getBuild().getPlugins().add(checkstyle);
    return configuration;
  }

  private static String excludes(final Xpp3Dom configuration) {
    final Xpp3Dom excludes = configuration.getChild("excludes");
    return excludes == null ? null : excludes.getValue();
  }

  // WS-40: nohttp scans the whole project, and .lathe/ holds mirrored files with http:// URLs.
  @Test
  void injectProject_nohttpCheckWithExcludes_latheAppended() {
    final MavenProject project = project();
    addCompiler(project, "3.14.0");
    final Xpp3Dom configuration = addNohttp(project, "**/.git/**/*,**/target/**/");

    new LatheModelInjector(VERSION).injectProject(project);

    assertThat(excludes(configuration)).isEqualTo("**/.git/**/*,**/target/**/,**/.lathe/**");
  }

  @Test
  void injectProject_nohttpCheckWithoutExcludes_latheExcluded() {
    final MavenProject project = project();
    final Xpp3Dom configuration = addNohttp(project, null);

    new LatheModelInjector(VERSION).injectProject(project);

    assertThat(excludes(configuration)).isEqualTo("**/.lathe/**");
  }

  // A parent pom compiles nothing, yet can run the check over the whole project.
  @Test
  void injectProject_nohttpCheckInPomModule_latheExcluded() {
    final MavenProject project = project();
    final Xpp3Dom configuration = addNohttp(project, "**/target/**/");

    new LatheModelInjector(VERSION).injectProject(project);

    assertThat(excludes(configuration)).isEqualTo("**/target/**/,**/.lathe/**");
    assertThat(project.getProperties()).doesNotContainKey("maven.compiler.compilerId");
  }

  @Test
  void injectProject_excludesAlreadyNameLathe_leftAsTheyAreAndAgainOnASecondPass() {
    final MavenProject project = project();
    final Xpp3Dom configuration = addNohttp(project, "**/target/**/,**/.lathe/**/*");
    final var injector = new LatheModelInjector(VERSION);

    injector.injectProject(project);
    injector.injectProject(project);

    assertThat(excludes(configuration)).isEqualTo("**/target/**/,**/.lathe/**/*");
  }

  // Without an element, the plugin reads its excludes from the checkstyle.excludes property; the
  // element Lathe adds keeps what the property said.
  @Test
  void injectProject_excludesSetByProperty_keptInTheElementLatheAdds() {
    final MavenProject project = project();
    project.getProperties().setProperty("checkstyle.excludes", "**/generated/**");
    final Xpp3Dom configuration = addNohttp(project, null);

    new LatheModelInjector(VERSION).injectProject(project);

    assertThat(excludes(configuration)).isEqualTo("**/generated/**,**/.lathe/**");
  }

  // A pattern naming only part of .lathe/, or another directory that starts like it, keeps nothing
  // of the mirror out.
  @Test
  void injectProject_excludesNameOnlyALookalike_latheStillAppended() {
    final MavenProject project = project();
    final Xpp3Dom configuration = addNohttp(project, "**/.lathe-backup/**, **/.lathe/cache/**");

    new LatheModelInjector(VERSION).injectProject(project);

    assertThat(excludes(configuration))
        .isEqualTo("**/.lathe-backup/**, **/.lathe/cache/**,**/.lathe/**");
  }

  // A goal run from the command line (mvn checkstyle:check) takes the plugin's own configuration.
  @Test
  void injectProject_nohttpConfiguredOnThePlugin_latheExcludedThereToo() {
    final MavenProject project = project();
    addNohttp(project, null);
    final Plugin checkstyle =
        project
            .getBuild()
            .getPluginsAsMap()
            .get("org.apache.maven.plugins:maven-checkstyle-plugin");
    final var pluginConfiguration = new Xpp3Dom("configuration");
    checkstyle.setConfiguration(pluginConfiguration);

    new LatheModelInjector(VERSION).injectProject(project);

    assertThat(excludes(pluginConfiguration)).isEqualTo("**/.lathe/**");
  }

  @Test
  void injectProject_checkstyleWithoutNohttp_excludesUntouched() {
    final MavenProject project = project();
    final Xpp3Dom configuration = addCheckstyle(project, "com.puppycrawl.tools", "checkstyle");

    new LatheModelInjector(VERSION).injectProject(project);

    assertThat(excludes(configuration)).isNull();
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

  // WS-41: Maven builds an extensions plugin's realm before lathe-compiler is added to it, so the
  // flag is turned off and the plugin loads as a plain one, with lathe-compiler.
  @Test
  void injectProject_compilerAsBuildExtension_loadedAsPlainPluginWithTheShim() {
    final MavenProject project = project();
    addCompiler(project, "3.14.0").setExtensions(true);

    new LatheModelInjector(VERSION).injectProject(project);

    assertThat(compilerPlugin(project).isExtensions()).isFalse();
    assertThat(compilerPlugin(project).getDependencies())
        .extracting(Dependency::getArtifactId)
        .containsExactly("lathe-compiler");
  }

  // A compiler plugin that does not ask to be an extension is left as declared.
  @Test
  void injectProject_compilerNotABuildExtension_extensionsLeftUnset() {
    final MavenProject project = project();
    addCompiler(project, "3.14.0");

    new LatheModelInjector(VERSION).injectProject(project);

    assertThat(compilerPlugin(project).getExtensions()).isNull();
    assertThat(compilerPlugin(project).getDependencies())
        .extracting(Dependency::getArtifactId)
        .containsExactly("lathe-compiler");
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
  // AbstractJavaTimeArrayTests). Abstract bases by name go after the other test names.
  @Test
  void injectCaptureClass_helpersAndAbstractBasesSortFirst_selectsRunnableTestNamesFirst(
      @TempDir final Path dir) throws IOException {
    final MavenProject project = project();
    for (final String name :
        List.of(
            "AbstractArrayTests",
            "AbstractBindTests",
            "AbstractJdbiTest",
            "AbstractQueryTests",
            "AbstractTypeTests",
            "Callback",
            "FooBarType",
            "BindTest",
            "TestJdbi")) {
      write(dir.resolve("com/example/%s.java".formatted(name)));
    }

    project.addTestCompileSourceRoot(dir.toString());

    new LatheModelInjector(VERSION).injectCaptureClass(project);

    assertThat(project.getProperties())
        .containsOnly(
            entry(
                "test", "BindTest,TestJdbi,AbstractArrayTests,AbstractBindTests,AbstractJdbiTest"),
            entry("surefire.failIfNoSpecifiedTests", "false"));
  }

  // A module whose tests are partly Kotlin keeps running them all: its Java sources may be only
  // helpers, and src/test/kotlin is not yet a test root when the extension looks. Its build's
  // Kotlin compiler says so, wherever the Kotlin lies (WS-54).
  @Test
  void injectCaptureClass_kotlinBesideTheJavaRoot_leavesTheSelection(@TempDir final Path dir)
      throws IOException {
    final MavenProject project = project();
    project.getBuild().addPlugin(plugin("org.jetbrains.kotlin", "kotlin-maven-plugin"));
    write(dir.resolve("src/test/java/com/example/TypeConversionHelper.java"));
    write(dir.resolve("src/test/kotlin/com/example/ExtensionsTest.kt"));
    project.addTestCompileSourceRoot(dir.resolve("src/test/java").toString());

    new LatheModelInjector(VERSION).injectCaptureClass(project);

    assertThat(project.getProperties()).isEmpty();
  }

  // Groovy compiled with Java through maven-compiler-plugin's Groovy Eclipse compiler is another
  // language's build too.
  @Test
  void injectCaptureClass_groovyEclipseCompiler_leavesTheSelection(@TempDir final Path dir)
      throws IOException {
    final MavenProject project = project();
    final Plugin compiler = plugin("org.apache.maven.plugins", "maven-compiler-plugin");
    final var configuration = new Xpp3Dom("configuration");
    final var compilerId = new Xpp3Dom("compilerId");
    compilerId.setValue("groovy-eclipse-compiler");
    configuration.addChild(compilerId);
    compiler.setConfiguration(configuration);
    project.getBuild().addPlugin(compiler);
    write(dir.resolve("src/test/java/com/example/SpecHelper.java"));
    project.addTestCompileSourceRoot(dir.resolve("src/test/java").toString());

    new LatheModelInjector(VERSION).injectCaptureClass(project);

    assertThat(project.getProperties()).isEmpty();
  }

  // The Groovy Eclipse compiler set for the testCompile execution alone counts too.
  @Test
  void injectCaptureClass_groovyEclipseCompilerForTestCompile_leavesTheSelection(
      @TempDir final Path dir) throws IOException {
    final MavenProject project = project();
    final Plugin compiler = plugin("org.apache.maven.plugins", "maven-compiler-plugin");
    final var execution = new PluginExecution();
    execution.setId("default-testCompile");
    final var configuration = new Xpp3Dom("configuration");
    final var compilerId = new Xpp3Dom("compilerId");
    compilerId.setValue(" groovy-eclipse-compiler ");
    configuration.addChild(compilerId);
    execution.setConfiguration(configuration);
    compiler.addExecution(execution);
    project.getBuild().addPlugin(compiler);
    write(dir.resolve("src/test/java/com/example/SpecHelper.java"));
    project.addTestCompileSourceRoot(dir.resolve("src/test/java").toString());

    new LatheModelInjector(VERSION).injectCaptureClass(project);

    assertThat(project.getProperties()).isEmpty();
  }

  // A kotlin folder no plugin compiles says nothing: the folder name is no fact of the build.
  @Test
  void injectCaptureClass_kotlinFolderWithoutAKotlinBuild_selectsTheJavaTests(
      @TempDir final Path dir) throws IOException {
    final MavenProject project = project();
    write(dir.resolve("src/test/java/com/example/AppTest.java"));
    write(dir.resolve("src/test/kotlin/com/example/Leftover.kt"));
    project.addTestCompileSourceRoot(dir.resolve("src/test/java").toString());

    new LatheModelInjector(VERSION).injectCaptureClass(project);

    assertThat(project.getProperties()).containsEntry("test", "AppTest");
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
