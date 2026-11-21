package io.github.aglibs.lathe.maven.extension;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mutates a resolved {@link MavenProject} model in memory to add the Lathe build wiring the user
 * would otherwise hand-edit into the POM. Extension-wins: a stale Lathe version is overwritten so
 * the effective build always matches the registered extension version.
 */
final class LatheModelInjector {

  private static final Logger LOG = LoggerFactory.getLogger(LatheModelInjector.class);
  private static final String COMPILER_ARTIFACT_ID = "lathe-compiler";
  private static final String PLUGIN_ARTIFACT_ID = "lathe-maven-plugin";
  private static final String JUNIT_ARTIFACT_ID = "lathe-junit";
  private static final String COMPILER_ID = "lathe";
  private static final String MAVEN_COMPILER_ID_PROPERTY = "maven.compiler.compilerId";
  // Surefire's test selection, which replaces its includes and excludes, and the switch that lets
  // a module with no class matching it pass.
  static final String SUREFIRE_TEST_PROPERTY = "test";
  private static final String SUREFIRE_FAIL_IF_NO_SPECIFIED_TESTS_PROPERTY =
      "surefire.failIfNoSpecifiedTests";
  // How many classes a capture-only run selects: enough that a few it does not run (abstract
  // bases sort first by name: AbstractFooTests) still leave one that starts the test JVM.
  private static final int CAPTURE_CLASSES = 5;
  private static final String ABSTRACT_PREFIX = "Abstract";
  private static final String JAVA_ROOT_NAME = "java";
  private static final List<String> OTHER_TEST_LANGUAGE_EXTENSIONS =
      List.of(".kt", ".groovy", ".scala");
  private static final String INIT_GOAL = "init";
  private static final String INIT_EXECUTION_ID = "lathe-init";
  private static final String SYNC_EXECUTION_ID = "lathe-sync";
  private static final String MAVEN_PLUGINS_GROUP_ID = "org.apache.maven.plugins";
  private static final String CHECKSTYLE_PLUGIN_ARTIFACT_ID = "maven-checkstyle-plugin";
  private static final String NOHTTP_GROUP_ID = "io.spring.nohttp";
  private static final String NOHTTP_ARTIFACT_ID = "nohttp-checkstyle";
  private static final String EXCLUDES = "excludes";
  // The property maven-checkstyle-plugin reads its excludes from when no element sets them.
  private static final String EXCLUDES_PROPERTY = "checkstyle.excludes";
  private static final String LATHE_EXCLUDE = "**/%s/**".formatted(LatheLayout.LATHE_DIR);
  // Excludes that already keep the whole of .lathe/ out; one naming only part of it does not.
  private static final Set<String> LATHE_EXCLUDES =
      Set.of(LATHE_EXCLUDE, "%s/*".formatted(LATHE_EXCLUDE), "%s/".formatted(LATHE_EXCLUDE));

  private final String version;
  // One injector serves a build: a build declaring the extension in every module says so once.
  private boolean extensionsTurnedOff;
  private boolean nohttpExcluded;

  LatheModelInjector(final String version) {
    this.version = version;
  }

  // Per-project injection: the compiler shim, its tree scopes, and the capture dependency. Only
  // where Maven compiles: it has already put the compiler plugin, at the project's version, in
  // every module whose packaging compiles. A pom module has none, and one created without a
  // version would make Maven look up the newest plugin online on every build.
  void injectProject(final MavenProject project) {
    excludeLatheFromNohttp(project);
    compilerPlugin(project)
        .ifPresent(
            compiler -> {
              injectCompilerShim(project, compiler);
              injectTreeScopes(project);
              injectCaptureDependency(project);
            });
  }

  // nohttp's checkstyle execution scans the whole project directory, and from the second build on
  // .lathe/ holds mirrored files with http:// URLs (a copied SBOM), so every sync after the first
  // fails (WS-40). Maven has merged the plugin's configuration into each execution's by now, so
  // each execution's own is edited; the plugin's too, for a goal run from the command line
  // (default-cli). Every execution of a plugin that runs nohttp gets the exclude: one that checks
  // style over src/ never reaches .lathe/, so it changes nothing there. Any project, not only one
  // that compiles: a parent pom can carry the check.
  void excludeLatheFromNohttp(final MavenProject project) {
    final Optional<Plugin> checkstyle =
        findPlugin(project, MAVEN_PLUGINS_GROUP_ID, CHECKSTYLE_PLUGIN_ARTIFACT_ID);
    if (checkstyle.isEmpty() || !runsNohttp(checkstyle.get())) {
      return;
    }

    final String inherited = inheritedExcludes(project);
    boolean excluded =
        checkstyle.get().getConfiguration() instanceof final Xpp3Dom pluginConfiguration
            && excludeLathe(pluginConfiguration, inherited);
    for (final PluginExecution execution : checkstyle.get().getExecutions()) {
      if (execution.getConfiguration() instanceof final Xpp3Dom configuration) {
        excluded |= excludeLathe(configuration, inherited);
      }
    }

    if (excluded && !nohttpExcluded) {
      nohttpExcluded = true;
      LOG.info(
          "[setup] {} excluded from the nohttp check, which scans the whole project",
          LATHE_EXCLUDE);
    }
  }

  private static boolean runsNohttp(final Plugin checkstyle) {
    return checkstyle.getDependencies().stream()
        .anyMatch(
            dependency ->
                NOHTTP_GROUP_ID.equals(dependency.getGroupId())
                    && NOHTTP_ARTIFACT_ID.equals(dependency.getArtifactId()));
  }

  // The excludes a configuration without its own element takes from the checkstyle.excludes
  // property: a -D one (Maven sets those as system properties too), else the project's. An element
  // Lathe adds would hide the property, so it starts from that value.
  private static String inheritedExcludes(final MavenProject project) {
    final String fromCommandLine = System.getProperty(EXCLUDES_PROPERTY);
    return fromCommandLine != null
        ? fromCommandLine
        : project.getProperties().getProperty(EXCLUDES_PROPERTY);
  }

  // Whether it changed the configuration: an execution whose excludes already name .lathe/ keeps
  // them, so a second pass changes nothing.
  private static boolean excludeLathe(final Xpp3Dom configuration, final String inherited) {
    final Xpp3Dom excludes = configuration.getChild(EXCLUDES);
    if (excludes == null) {
      final var added = new Xpp3Dom(EXCLUDES);
      added.setValue(withLathe(inherited));
      configuration.addChild(added);
      return true;
    }

    final String value = excludes.getValue();
    if (value != null
        && Arrays.stream(value.split(",")).map(String::trim).anyMatch(LATHE_EXCLUDES::contains)) {
      return false;
    }

    excludes.setValue(withLathe(value));
    return true;
  }

  private static String withLathe(final String excludes) {
    return excludes == null || excludes.isBlank()
        ? LATHE_EXCLUDE
        : "%s,%s".formatted(excludes, LATHE_EXCLUDE);
  }

  static Optional<Plugin> compilerPlugin(final MavenProject project) {
    return findPlugin(
        project,
        LatheLayout.Maven.MAVEN_COMPILER_PLUGIN_GROUP_ID,
        LatheLayout.Maven.MAVEN_COMPILER_PLUGIN_ARTIFACT_ID);
  }

  void injectCompilerShim(final MavenProject project, final Plugin compiler) {
    // A plugin loaded as a build extension gets its class realm while Maven reads the projects,
    // before this adds lathe-compiler, so the build would fail with "No such compiler 'lathe'"
    // (WS-41). The compiler plugin contributes nothing as an extension: load it as a plain plugin,
    // whose realm Maven builds when it first runs, with lathe-compiler in it.
    if (compiler.isExtensions()) {
      compiler.setExtensions(false);
      if (!extensionsTurnedOff) {
        extensionsTurnedOff = true;
        LOG.info(
            "[setup] maven-compiler-plugin loaded as a plain plugin, not a build extension,"
                + " so its realm holds the lathe compiler");
      }
    }

    putLatheDependency(compiler.getDependencies(), COMPILER_ARTIFACT_ID, null);
    // The compiler is selected via the property, not a <compilerId> config node: a node added in
    // afterProjectsRead does not reach the default compile executions (lathe-maven-extension.md
    // §9).
    project.getProperties().setProperty(MAVEN_COMPILER_ID_PROPERTY, COMPILER_ID);
  }

  // The shim names a .lathe/ tree by scope, not by the output directory's leaf: tell it which
  // scope each of this project's output directories compiles, and the build directory it is in.
  void injectTreeScopes(final MavenProject project) {
    System.setProperty(
        LatheLayout.Maven.treeProperty(project.getBuild().getOutputDirectory()),
        LatheLayout.CLASSES_DIR);
    System.setProperty(
        LatheLayout.Maven.treeProperty(project.getBuild().getTestOutputDirectory()),
        LatheLayout.TEST_CLASSES_DIR);
    final String buildDir = project.getBuild().getDirectory();
    if (buildDir == null) {
      return;
    }

    System.setProperty(
        LatheLayout.Maven.buildDirProperty(project.getBuild().getOutputDirectory()), buildDir);
    System.setProperty(
        LatheLayout.Maven.buildDirProperty(project.getBuild().getTestOutputDirectory()), buildDir);
  }

  void injectCaptureDependency(final MavenProject project) {
    putLatheDependency(project.getDependencies(), JUNIT_ARTIFACT_ID, "test");
  }

  // A capture-only run needs one test JVM to start, not every test class: the launch is the same
  // whichever classes run, while every class costs a discovery of each (minutes on a large module).
  // Surefire reads its test selection from the module's properties when the build names none, and
  // it replaces the module's includes. Nothing is compiled yet, so the classes come from the test
  // sources, a few of them: Surefire starts no JVM for a selection it does not run (an abstract
  // base class, a test helper), and one of several is enough. A module whose tests are generated,
  // or partly in another language, keeps running them all: its Java sources may be only helpers.
  void injectCaptureClass(final MavenProject project) {
    final Properties properties = project.getProperties();
    if (properties.containsKey(SUREFIRE_TEST_PROPERTY)) {
      return;
    }

    final List<Path> roots = project.getTestCompileSourceRoots().stream().map(Path::of).toList();
    if (hasOtherLanguageSources(roots)) {
      return;
    }

    final List<String> selection =
        roots.stream()
            .flatMap(
                root ->
                    IOUtil.unchecked(
                        () -> FileUtil.walkFiles(root, LatheModelInjector::isTypeSource))
                        .stream())
            .map(FileUtil::javaTypeName)
            .distinct()
            .sorted(
                Comparator.comparingInt(LatheModelInjector::captureRank)
                    .thenComparing(Comparator.naturalOrder()))
            .limit(CAPTURE_CLASSES)
            .toList();
    if (selection.isEmpty()) {
      return;
    }

    properties.setProperty(SUREFIRE_TEST_PROPERTY, String.join(",", selection));
    properties.setProperty(SUREFIRE_FAIL_IF_NO_SPECIFIED_TESTS_PROPERTY, "false");
  }

  // The names Surefire's default includes match (Test*, *Test, *Tests, *TestCase) first, an
  // abstract base's by its usual name (AbstractFooTests) after them, then the rest.
  private static int captureRank(final String name) {
    final boolean testName =
        name.startsWith("Test")
            || name.endsWith("Test")
            || name.endsWith("Tests")
            || name.endsWith("TestCase");
    if (!testName) {
      return 2;
    }

    return name.startsWith(ABSTRACT_PREFIX) ? 1 : 0;
  }

  // Kotlin, Groovy or Scala test sources beside the Java ones: in a test root, or, for a root laid
  // out as src/<set>/java, in its source set (src/test/kotlin, which a build helper adds as a root
  // only later in the build). No other parent: one may be the whole module, build output included.
  private static boolean hasOtherLanguageSources(final List<Path> roots) {
    return roots.stream()
        .flatMap(root -> Stream.of(root, javaRootSourceSet(root)))
        .filter(Objects::nonNull)
        .distinct()
        .anyMatch(
            dir ->
                !IOUtil.unchecked(
                        () -> FileUtil.walkFiles(dir, LatheModelInjector::isOtherLanguageSource))
                    .isEmpty());
  }

  private static Path javaRootSourceSet(final Path root) {
    final Path name = root.getFileName();
    return name != null && JAVA_ROOT_NAME.equals(name.toString()) ? root.getParent() : null;
  }

  private static boolean isOtherLanguageSource(final Path path) {
    final String name = path.getFileName().toString();
    return OTHER_TEST_LANGUAGE_EXTENSIONS.stream().anyMatch(name::endsWith);
  }

  private static boolean isTypeSource(final Path path) {
    return FileUtil.isJavaFile(path)
        && !path.endsWith(LatheLayout.MODULE_INFO_JAVA)
        && !path.endsWith(LatheLayout.PACKAGE_INFO_JAVA);
  }

  /**
   * Binds {@code lathe:init} and {@code lathe:sync} on the reactor root only. Mutating the
   * already-resolved top-level model does not propagate to child modules, reproducing the manual
   * {@code <inherited>false</inherited>} setup.
   */
  void injectRootExecutions(final MavenProject topLevelProject) {
    final Plugin plugin =
        findOrCreatePlugin(topLevelProject, LatheLayout.Maven.LATHE_GROUP_ID, PLUGIN_ARTIFACT_ID);
    plugin.setVersion(version);
    bindGoal(plugin, INIT_EXECUTION_ID, INIT_GOAL);
    bindGoal(plugin, SYNC_EXECUTION_ID, LatheLayout.Maven.SYNC_GOAL);
  }

  private static Optional<Plugin> findPlugin(
      final MavenProject project, final String groupId, final String artifactId) {
    return project.getBuild().getPlugins().stream()
        .filter(
            plugin ->
                groupId.equals(plugin.getGroupId()) && artifactId.equals(plugin.getArtifactId()))
        .findFirst();
  }

  private Plugin findOrCreatePlugin(
      final MavenProject project, final String groupId, final String artifactId) {
    return findPlugin(project, groupId, artifactId)
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
                LatheLayout.Maven.LATHE_GROUP_ID.equals(dependency.getGroupId())
                    && artifactId.equals(dependency.getArtifactId()))
        .findFirst()
        .ifPresentOrElse(
            existing -> existing.setVersion(version),
            () -> {
              final var dependency = new Dependency();
              dependency.setGroupId(LatheLayout.Maven.LATHE_GROUP_ID);
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
