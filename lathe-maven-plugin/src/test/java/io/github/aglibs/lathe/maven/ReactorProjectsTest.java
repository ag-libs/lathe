package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.OutputData;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactorProjectsTest {

  @TempDir Path tmp;

  @Test
  void moduleRel_rootProject_returnsDot() {
    final MavenProject root = project("com.example", "root", "1", tmp);

    assertThat(ReactorProjects.moduleRel(tmp, root)).isEqualTo(".");
  }

  @Test
  void isReactorRoot_noAncestorAggregatesItExactly_true() throws Exception {
    // A genuine root, even with poms above: (a) an ancestor lists an unrelated sibling, and (b) an
    // ancestor lists a module we sit physically *under* but are not -- the invoker case, a fixture
    // deep under lathe-maven-plugin, which the repo root aggregates. Only a <module> pointing
    // exactly at us makes us a submodule. Also verified through a symlink (basedir is
    // toRealPath'd).
    final Path outer = writePom(tmp.resolve("outer"), "sibling", "plugin");
    writePom(outer.resolve("plugin")); // a real module; we live under it, but are not it
    final Path root = writePom(outer.resolve("plugin/target/it/fixture"));
    final Path symlinked = Files.createSymbolicLink(tmp.resolve("link"), root);

    assertThat(ReactorProjects.isReactorRoot(root)).isTrue();
    assertThat(ReactorProjects.isReactorRoot(symlinked)).isTrue();
  }

  @Test
  void isReactorRoot_ancestorAggregatesViaModules_false() throws Exception {
    // A submodule: an aggregator pom above declares it in <modules>, even nested several dirs deep
    // through pom-less intermediate directories (a `<module>a/b/c</module>` layout).
    final Path root = writePom(tmp.resolve("root"), "group/nested/module");
    final Path deepModule = Files.createDirectories(root.resolve("group/nested/module"));

    assertThat(ReactorProjects.isReactorRoot(deepModule)).isFalse();
  }

  @Test
  void escapesReactorRoot_moduleOutsideRoot_true() {
    final Path root = tmp.resolve("root");

    assertThat(ReactorProjects.escapesReactorRoot(root, project("g", "root", "1", root))).isFalse();
    assertThat(
            ReactorProjects.escapesReactorRoot(root, project("g", "app", "1", root.resolve("app"))))
        .isFalse();
    assertThat(
            ReactorProjects.escapesReactorRoot(
                root, project("g", "shared", "1", tmp.resolve("shared"))))
        .isTrue();
  }

  @Test
  void externalArtifacts_filtersReactorProjectsAndDeduplicates() {
    final MavenProject core = project("com.example", "core", "1", tmp.resolve("core"));
    final MavenProject app = project("com.example", "app", "1", tmp.resolve("app"));
    final MavenProject test = project("com.example", "test", "1", tmp.resolve("test"));
    app.setArtifacts(
        Set.of(artifact("com.example", "core", "1"), artifact("org.example", "external", "1")));
    test.setArtifacts(Set.of(artifact("org.example", "external", "1")));

    assertThat(ReactorProjects.externalArtifacts(List.of(core, app, test)).values())
        .extracting(Artifact::getGroupId, Artifact::getArtifactId, Artifact::getVersion)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("org.example", "external", "1"));
  }

  @Test
  void externalArtifacts_usesResolvedArtifactsIncludingTransitives() {
    final MavenProject app = project("com.example", "app", "1", tmp.resolve("app"));
    app.setArtifacts(
        Set.of(artifact("org.example", "direct", "1"), artifact("org.example", "transitive", "2")));

    assertThat(ReactorProjects.externalArtifacts(List.of(app)).values())
        .extracting(Artifact::getGroupId, Artifact::getArtifactId, Artifact::getVersion)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("org.example", "direct", "1"),
            org.assertj.core.groups.Tuple.tuple("org.example", "transitive", "2"));
  }

  @Test
  void artifactClasspaths_recordsCompileAndProvidedExternalJars() {
    final MavenProject core = project("com.example", "core", "1", tmp.resolve("core"));
    final MavenProject app = project("com.example", "app", "1", tmp.resolve("app"));
    final Artifact reactor = artifact("com.example", "core", "1");
    final Artifact compile = artifact("org.example", "compile", "1", Artifact.SCOPE_COMPILE);
    final Artifact provided = artifact("org.example", "provided", "1", Artifact.SCOPE_PROVIDED);
    final Artifact runtime = artifact("org.example", "runtime", "1", Artifact.SCOPE_RUNTIME);
    app.setArtifacts(Set.of(reactor, compile, provided, runtime));

    final Map<String, List<Path>> classpaths =
        ReactorProjects.artifactClasspaths(List.of(core, app));

    assertThat(classpaths.get(ReactorProjects.artifactKey(compile)))
        .containsExactlyInAnyOrder(compile.getFile().toPath(), provided.getFile().toPath());
    assertThat(classpaths.get(ReactorProjects.artifactKey(runtime)))
        .containsExactlyInAnyOrder(compile.getFile().toPath(), provided.getFile().toPath());
  }

  @Test
  void reactorOutputDirs_mapsEachModuleToItsOutputDirectory() {
    final MavenProject core = projectWithOutput("com.example", "core", "1", tmp.resolve("core"));
    final MavenProject app = projectWithOutput("com.example", "app", "1", tmp.resolve("app"));

    assertThat(ReactorProjects.reactorOutputDirs(List.of(core, app)))
        .containsOnly(
            entry("com.example:core", tmp.resolve("core/target/classes").toString()),
            entry("com.example:app", tmp.resolve("app/target/classes").toString()));
  }

  @Test
  void outputs_jarAndPomModules_listsDirsAndJarsUnderTheRealBuildDirSkippingPom() {
    final MavenProject root = projectWithOutput("com.example", "root", "1", tmp, "out");
    final MavenProject app = projectWithOutput("com.example", "app", "2", tmp.resolve("app"));
    final MavenProject parent =
        projectWithOutput("com.example", "parent", "1", tmp.resolve("parent"));
    parent.setPackaging(LatheLayout.Maven.POM_PACKAGING);

    final List<OutputData> outputs = ReactorProjects.outputs(tmp, List.of(root, app, parent));

    assertThat(outputs)
        .containsExactly(
            new OutputData("out/classes", "", LatheLayout.CLASSES_DIR),
            new OutputData("out/test-classes", "", LatheLayout.TEST_CLASSES_DIR),
            new OutputData("out/root-1.jar", "", LatheLayout.CLASSES_DIR),
            new OutputData("out/root-1-tests.jar", "", LatheLayout.TEST_CLASSES_DIR),
            new OutputData("app/target/classes", "app", LatheLayout.CLASSES_DIR),
            new OutputData("app/target/test-classes", "app", LatheLayout.TEST_CLASSES_DIR),
            new OutputData("app/target/app-2.jar", "app", LatheLayout.CLASSES_DIR),
            new OutputData("app/target/app-2-tests.jar", "app", LatheLayout.TEST_CLASSES_DIR));
  }

  @Test
  void shadesMainJar_shadeExecutionReplacingTheMainJar_trueUnlessAttachedOrWrittenElsewhere()
      throws Exception {
    assertThat(ReactorProjects.shadesMainJar(shading("shade", null, null))).isTrue();
    assertThat(ReactorProjects.shadesMainJar(shading("shade", "<relocations/>", null))).isTrue();
    assertThat(
            ReactorProjects.shadesMainJar(
                shading("shade", "<shadedArtifactAttached>true</shadedArtifactAttached>", null)))
        .isFalse();
    assertThat(
            ReactorProjects.shadesMainJar(
                shading(
                    "shade", "<outputFile>${project.build.directory}/all.jar</outputFile>", null)))
        .isFalse();
    // The plugin-level configuration applies to every execution.
    assertThat(
            ReactorProjects.shadesMainJar(
                shading("shade", null, "<shadedArtifactAttached>true</shadedArtifactAttached>")))
        .isFalse();
    assertThat(ReactorProjects.shadesMainJar(shading("help", null, null))).isFalse();
    // A finalName of shade's own leaves the main jar as it is; the build's own name does not.
    assertThat(
            ReactorProjects.shadesMainJar(
                shading("shade", "<finalName>lib-1-uber</finalName>", null)))
        .isFalse();
    assertThat(
            ReactorProjects.shadesMainJar(shading("shade", "<finalName>lib-1</finalName>", null)))
        .isTrue();
    // A child switching off its parent's shade execution.
    final MavenProject disabled = shading("shade", null, null);
    shadeExecution(disabled).setPhase("none");
    assertThat(ReactorProjects.shadesMainJar(disabled)).isFalse();
    assertThat(
            ReactorProjects.shadesMainJar(
                projectWithOutput("com.example", "plain", "1", tmp.resolve("plain"))))
        .isFalse();
  }

  // Reading the configuration leaves Maven's own model as it was, for shade to run with.
  @Test
  void shadesMainJar_executionAndPluginConfiguration_leavesTheModelUnchanged() throws Exception {
    final MavenProject lib =
        shading(
            "shade",
            "<relocations><relocation><pattern>a</pattern></relocation></relocations>",
            "<minimizeJar>true</minimizeJar>");
    final String before = shadeExecution(lib).getConfiguration().toString();

    ReactorProjects.shadesMainJar(lib);

    assertThat(shadeExecution(lib).getConfiguration()).hasToString(before);
  }

  // A shaded main jar holds the classes it relocated, which the mirror lacks: it is not listed as
  // standing for the mirror, so a dependent keeps it on its classpath.
  @Test
  void outputs_moduleShadingItsMainJar_listsNoMainJar() throws Exception {
    final MavenProject lib = shading("shade", null, null);

    assertThat(ReactorProjects.outputs(tmp, List.of(lib)))
        .containsExactly(
            new OutputData("lib/target/classes", "lib", LatheLayout.CLASSES_DIR),
            new OutputData("lib/target/test-classes", "lib", LatheLayout.TEST_CLASSES_DIR),
            new OutputData("lib/target/lib-1-tests.jar", "lib", LatheLayout.TEST_CLASSES_DIR));
  }

  @Test
  void shadedUpstreams_modulesCompilingAgainstAShadedJar_namesItAndCountsThem() throws Exception {
    final MavenProject lib = shading("shade", null, null);
    final MavenProject core = projectWithOutput("com.example", "core", "1", tmp.resolve("core"));
    final MavenProject app = projectWithOutput("com.example", "app", "1", tmp.resolve("app"));
    final MavenProject other = projectWithOutput("com.example", "other", "1", tmp.resolve("other"));
    final Map<MavenProject, List<MavenProject>> upstream =
        Map.of(lib, List.of(), core, List.of(lib), app, List.of(core, lib), other, List.of());

    final ReactorProjects.ShadedUpstreams shaded =
        ReactorProjects.shadedUpstreams(tmp, List.of(lib, core, app, other), upstream::get);
    final ReactorProjects.ShadedUpstreams none =
        ReactorProjects.shadedUpstreams(tmp, List.of(other), project -> List.of(core));

    assertThat(shaded.shadingModules()).containsExactly("lib");
    assertThat(shaded.dependents()).isEqualTo(2);
    assertThat(none.shadingModules()).isEmpty();
    assertThat(none.dependents()).isZero();
  }

  @Test
  void remoteRepositories_deduplicatesByIdAndUrl() {
    final MavenProject first = project("com.example", "first", "1", tmp.resolve("first"));
    final MavenProject second = project("com.example", "second", "1", tmp.resolve("second"));
    final RemoteRepository central =
        new RemoteRepository.Builder("central", "default", "https://repo.example").build();
    ((TestProject) first).remoteProjectRepositories = List.of(central);
    ((TestProject) second).remoteProjectRepositories = List.of(central);

    assertThat(ReactorProjects.remoteRepositories(List.of(first, second))).containsExactly(central);
  }

  private static Path writePom(final Path dir, final String... modules) throws IOException {
    Files.createDirectories(dir);
    final var mods = new StringBuilder();
    for (final String module : modules) {
      mods.append("<module>").append(module).append("</module>");
    }

    Files.writeString(
        dir.resolve("pom.xml"),
        ("<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>a</artifactId>"
                + "<version>1</version><modules>%s</modules></project>")
            .formatted(mods));
    return dir;
  }

  private static MavenProject project(
      final String groupId, final String artifactId, final String version, final Path basedir) {
    final var project = new TestProject();
    project.setGroupId(groupId);
    project.setArtifactId(artifactId);
    project.setVersion(version);
    project.setFile(basedir.resolve("pom.xml").toFile());
    return project;
  }

  private static MavenProject projectWithOutput(
      final String groupId, final String artifactId, final String version, final Path basedir) {
    return projectWithOutput(groupId, artifactId, version, basedir, "target");
  }

  private static MavenProject projectWithOutput(
      final String groupId,
      final String artifactId,
      final String version,
      final Path basedir,
      final String buildDir) {
    final MavenProject project = project(groupId, artifactId, version, basedir);
    final var build = new Build();
    build.setDirectory(basedir.resolve(buildDir).toString());
    build.setOutputDirectory(basedir.resolve(buildDir).resolve("classes").toString());
    build.setTestOutputDirectory(basedir.resolve(buildDir).resolve("test-classes").toString());
    build.setFinalName("%s-%s".formatted(artifactId, version));
    project.setBuild(build);
    return project;
  }

  // Module lib with maven-shade-plugin running the given goal, its execution and plugin-level
  // configuration given as the children of <configuration>, or none.
  private MavenProject shading(
      final String goal, final String executionConfiguration, final String pluginConfiguration)
      throws Exception {
    final MavenProject project = projectWithOutput("com.example", "lib", "1", tmp.resolve("lib"));
    final var execution = new PluginExecution();
    execution.setId("default");
    execution.addGoal(goal);
    execution.setConfiguration(configuration(executionConfiguration));
    final var plugin = new Plugin();
    plugin.setGroupId("org.apache.maven.plugins");
    plugin.setArtifactId("maven-shade-plugin");
    plugin.addExecution(execution);
    plugin.setConfiguration(configuration(pluginConfiguration));
    project.getBuild().addPlugin(plugin);
    return project;
  }

  private static PluginExecution shadeExecution(final MavenProject project) {
    return project
        .getBuild()
        .getPluginsAsMap()
        .get(LatheLayout.Maven.SHADE_PLUGIN_KEY)
        .getExecutions()
        .get(0);
  }

  private static Xpp3Dom configuration(final String children) throws Exception {
    return children == null
        ? null
        : Xpp3DomBuilder.build(
            new StringReader("<configuration>%s</configuration>".formatted(children)));
  }

  private Artifact artifact(final String groupId, final String artifactId, final String version) {
    return artifact(groupId, artifactId, version, Artifact.SCOPE_COMPILE);
  }

  private Artifact artifact(
      final String groupId, final String artifactId, final String version, final String scope) {
    final Artifact artifact =
        new DefaultArtifact(groupId, artifactId, version, scope, "jar", "", null);
    artifact.setFile(tmp.resolve("%s-%s.jar".formatted(artifactId, version)).toFile());
    return artifact;
  }

  private static final class TestProject extends MavenProject {

    private List<RemoteRepository> remoteProjectRepositories = List.of();

    @Override
    public List<RemoteRepository> getRemoteProjectRepositories() {
      return remoteProjectRepositories;
    }
  }
}
