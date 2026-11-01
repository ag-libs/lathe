package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.ResourceRootData;
import io.github.aglibs.lathe.install.JvmArgsWriter;
import io.github.aglibs.lathe.install.ServerInstaller;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.dsl.DependencyFactory;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.attributes.Usage;
import org.gradle.api.configuration.BuildFeatures;
import org.gradle.api.file.FileCollection;
import org.gradle.api.initialization.IncludedBuild;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.tasks.Jar;
import org.gradle.jvm.toolchain.JavaInstallationMetadata;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.util.GradleVersion;

// Applied to every project by LatheSettingsPlugin. A Java project gets a capture task per main and
// test source set, finalizing its compile; the compile itself is left untouched, so its build-cache
// key is the same with or without Lathe. Every project writes its facts, which the root
// project's latheWorkspace merges.
public class LatheProjectPlugin implements Plugin<Project> {

  static final String WORKSPACE_TASK = "latheWorkspace";
  static final String FACTS_TASK = "latheFacts";
  private static final String FACTS_USAGE = "io.github.ag-libs.lathe-facts";
  private static final String FACTS_ELEMENTS = "latheFactsElements";
  private static final String PROJECT_FACTS = "latheProjectFacts";
  private static final String SERVER = "latheServer";
  private static final String MCP_SERVER = "latheMcpServer";
  private static final String BUILD_LOCK_SERVICE = "latheBuildLock";
  static final String ROOT_PATH = ":";
  private static final String LIBRARY_SOURCES = "latheLibrarySources";

  // Gradle 8.x under Isolated Projects rejects a project naming another's task, so there only the
  // root's own lifecycle tasks trigger the workspace write: a whole-build run refreshes it, a
  // one-project run does not, as Maven's -pl.
  private static final GradleVersion CROSS_PROJECT_TRIGGER = GradleVersion.version("9.0");
  private static final Set<String> ROOT_LIFECYCLE = Set.of("build", "check", "test");

  private final BuildFeatures features;

  @Inject
  public LatheProjectPlugin(final BuildFeatures features) {
    this.features = features;
  }

  @Override
  public void apply(final Project project) {
    if (!insideRoot(project.getRootDir().toPath(), project.getProjectDir().toPath())) {
      // Its module would name a directory outside .lathe/ (../shared): left out, as an included
      // build's projects are.
      project
          .getLogger()
          .info(
              "[lathe] {} left out: its directory is outside the root build's", project.getPath());
      return;
    }

    final TaskProvider<LatheFactsTask> facts =
        project
            .getTasks()
            .register(
                FACTS_TASK,
                LatheFactsTask.class,
                task -> {
                  task.getFacts()
                      .set(project.provider(() -> Json.toJson(ProjectFacts.of(module(project)))));
                  task.getFactsFile()
                      .set(
                          LatheWorkspaceTask.factsFile(
                                  project.getRootDir().toPath(), module(project))
                              .toFile());
                  useBuildLock(project, task, task.getBuildLock());
                });
    factsConfiguration(project, FACTS_ELEMENTS, false)
        .getOutgoing()
        .artifact(facts.flatMap(LatheFactsTask::getFactsFile));
    triggerWorkspace(project, facts);
    project.getPlugins().withType(JavaPlugin.class, java -> registerJava(project, facts));
  }

  private void triggerWorkspace(final Project project, final TaskProvider<LatheFactsTask> facts) {
    final boolean isolated = features.getIsolatedProjects().getActive().getOrElse(false);
    if (!isolated
        || GradleVersion.current().getBaseVersion().compareTo(CROSS_PROJECT_TRIGGER) >= 0) {
      facts.configure(task -> task.finalizedBy(ROOT_PATH + WORKSPACE_TASK));
      return;
    }

    if (ROOT_PATH.equals(project.getPath())) {
      project
          .getTasks()
          .matching(task -> ROOT_LIFECYCLE.contains(task.getName()))
          .configureEach(task -> task.finalizedBy(WORKSPACE_TASK));
    }
  }

  // The root's workspace write and sync: the sync builds every other project's facts through a
  // configuration, so the root reads no other project's model, and the server jars are declared
  // now so Gradle can resolve them.
  static void registerSync(
      final Project project, final Map<String, String> modulePaths, final boolean addMavenCentral) {
    final Configuration projectFacts = factsConfiguration(project, PROJECT_FACTS, true);
    for (final String path : modulePaths.values()) {
      if (!path.equals(project.getPath())) {
        project
            .getDependencies()
            .add(PROJECT_FACTS, project.getDependencies().project(Map.of("path", path)));
      }
    }

    final Configuration server = resolvable(project, SERVER, ServerInstaller.SERVER_COORDINATES);
    final Configuration mcpServer =
        resolvable(project, MCP_SERVER, ServerInstaller.MCP_SERVER_COORDINATES);
    // Only when nothing else could resolve the server: a project repository would hide the
    // settings' repositories from this project.
    if (addMavenCentral) {
      project.afterEvaluate(
          evaluated -> {
            if (evaluated.getRepositories().isEmpty()) {
              evaluated.getRepositories().mavenCentral();
            }
          });
    }

    final TaskProvider<LatheFactsTask> ownFacts =
        project.getTasks().named(FACTS_TASK, LatheFactsTask.class);
    final TaskProvider<LatheWorkspaceTask> workspace =
        project
            .getTasks()
            .register(
                WORKSPACE_TASK,
                LatheWorkspaceTask.class,
                task -> {
                  task.setDescription(
                      "Writes the Lathe workspace files from every project's facts.");
                  useBuildLock(project, task, task.getBuildLock());
                  task.getModuleDirs().set(modulePaths.keySet().stream().sorted().toList());
                  task.getServerJars().from(lenient(server));
                  task.getMcpServerJars().from(lenient(mcpServer));
                  task.getWorkspaceRoot().set(project.getLayout().getProjectDirectory());
                  task.getBuildLogicDirs().set(project.provider(() -> buildLogicDirs(project)));
                  task.getGradleHome().set(project.getGradle().getGradleHomeDir().getPath());
                  task.getDaemonJavaHome()
                      .set(project.getProviders().systemProperty(LatheLayout.JAVA_HOME_PROPERTY));
                  task.getInitScripts()
                      .set(
                          project.getGradle().getStartParameter().getInitScripts().stream()
                              .map(File::getPath)
                              .toList());
                  task.getDaemonJvmArgs()
                      .set(
                          project
                              .getProviders()
                              .gradleProperty(LatheLayout.Gradle.DAEMON_JVM_ARGS_PROPERTY));
                });
    project
        .getTasks()
        .register(
            LatheLayout.Gradle.SYNC_TASK,
            LatheSyncTask.class,
            task -> {
              task.setDescription("Syncs the workspace for the Lathe language server.");
              task.dependsOn(
                  (Callable<List<Object>>)
                      () ->
                          task.getModules().get().isEmpty()
                              ? List.of(projectFacts, ownFacts)
                              : task.getModules().get().stream()
                                  .<Object>map(module -> factsTaskPath(modulePaths, module))
                                  .toList());
              task.finalizedBy(workspace);
            });
  }

  // buildSrc/ and every included build: where Gradle builds keep their compiler settings.
  private static List<String> buildLogicDirs(final Project project) {
    final File buildSrc = new File(project.getRootDir(), LatheLayout.Gradle.BUILD_SRC_DIR);
    return Stream.concat(
            Stream.of(buildSrc).filter(File::isDirectory),
            project.getGradle().getIncludedBuilds().stream().map(IncludedBuild::getProjectDir))
        .map(File::getPath)
        .toList();
  }

  // One service for the whole build, whichever project registers it first.
  private static void useBuildLock(
      final Project project, final Task task, final Property<LatheBuildLockService> lock) {
    final Provider<LatheBuildLockService> service =
        project
            .getGradle()
            .getSharedServices()
            .registerIfAbsent(
                BUILD_LOCK_SERVICE,
                LatheBuildLockService.class,
                spec ->
                    spec.getParameters()
                        .getLatheDir()
                        .set(new File(project.getRootDir(), LatheLayout.LATHE_DIR)));
    lock.set(service);
    task.usesService(service);
  }

  private static String factsTaskPath(final Map<String, String> modulePaths, final String module) {
    final String path = modulePaths.get(module);
    if (path == null) {
      throw new GradleException(
          "[lathe] no project in directory '%s'; --%s takes a project directory, relative to the root"
              .formatted(module, LatheSyncTask.MODULE_OPTION));
    }

    return ROOT_PATH.equals(path) ? ROOT_PATH + FACTS_TASK : "%s:%s".formatted(path, FACTS_TASK);
  }

  // Its dependencies are added as the configuration resolves, not now: a build script that walks
  // every configuration's dependencies (configurations.all { dependencies.removeAll { ... } })
  // would otherwise remove them.
  static Configuration resolvable(
      final Project project, final String name, final String... coordinates) {
    final Configuration configuration = configuration(project, name, true);
    final DependencyFactory factory = project.getDependencyFactory();
    configuration.withDependencies(
        dependencies ->
            List.of(coordinates)
                .forEach(coordinate -> dependencies.add(factory.create(coordinate))));
    return configuration;
  }

  // A configuration's files, without failing when some cannot be resolved.
  static FileCollection lenient(final Configuration configuration) {
    return configuration.getIncoming().artifactView(view -> view.lenient(true)).getFiles();
  }

  // Facts travel between projects as a variant of their own usage, which no Java configuration
  // matches.
  private static Configuration factsConfiguration(
      final Project project, final String name, final boolean resolvable) {
    final Configuration configuration = configuration(project, name, resolvable);
    configuration
        .getAttributes()
        .attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, FACTS_USAGE));
    return configuration;
  }

  private static Configuration configuration(
      final Project project, final String name, final boolean resolvable) {
    return project
        .getConfigurations()
        .create(
            name,
            created -> {
              created.setCanBeConsumed(!resolvable);
              created.setCanBeResolved(resolvable);
            });
  }

  private static void registerJava(
      final Project project, final TaskProvider<LatheFactsTask> facts) {
    final var rootDir = project.getRootDir().toPath();
    final Path moduleDir = rootDir.resolve(LatheLayout.moduleDir(module(project)));
    final SourceSetContainer sourceSets =
        project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
    final SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
    final SourceSet test = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME);
    final TaskProvider<LatheCaptureTask> mainCapture =
        registerCapture(project, main, "latheCaptureMain", LatheLayout.CLASSES_DIR, moduleDir);
    final TaskProvider<LatheCaptureTask> testCapture =
        registerCapture(project, test, "latheCaptureTest", LatheLayout.TEST_CLASSES_DIR, moduleDir);
    facts.configure(
        task -> {
          task.dependsOn(mainCapture, testCapture);
          task.getFacts().set(project.provider(() -> Json.toJson(javaFacts(project, main, test))));
          task.getMainSources().from(main.getJava());
          task.getTestSources().from(test.getJava());
        });
    project
        .getTasks()
        .named(test.getCompileJavaTaskName())
        .configure(task -> task.finalizedBy(facts));
    LatheTestCapture.register(
        project,
        facts,
        test,
        moduleDir,
        project
            .getProviders()
            .gradleProperty(LatheFlags.CAPTURE_ONLY)
            .map("true"::equals)
            .getOrElse(false));
    registerLibraries(project, facts, main, test);
    final TaskProvider<LatheMainLaunchTask> mainLaunch = registerMainLaunch(project, main);
    facts.configure(task -> task.dependsOn(mainLaunch));
  }

  private static TaskProvider<LatheMainLaunchTask> registerMainLaunch(
      final Project project, final SourceSet main) {
    final JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
    final JavaToolchainService toolchains =
        project.getExtensions().getByType(JavaToolchainService.class);
    final String module = module(project);
    return project
        .getTasks()
        .register(
            "latheMainLaunch",
            LatheMainLaunchTask.class,
            task -> {
              final JavaCompile javac =
                  project.getTasks().named(main.getCompileJavaTaskName(), JavaCompile.class).get();
              task.getRuntimeClasspath().from(main.getRuntimeClasspath());
              // It reads the compiled module-info.class, and the classpath, which only its paths
              // are taken from, no longer orders it after the compile.
              task.dependsOn(javac);
              task.getClassesDir().set(javac.getDestinationDirectory());
              task.getInferModulePath().set(javac.getModularity().getInferModulePath());
              task.getJavaHome()
                  .set(
                      toolchains
                          .launcherFor(java.getToolchain())
                          .map(
                              launcher ->
                                  launcher
                                      .getMetadata()
                                      .getInstallationPath()
                                      .getAsFile()
                                      .getPath()));
              task.getModule().set(module.isEmpty() ? "." : module);
              task.getWorkspaceRoot().set(project.getRootDir());
              useBuildLock(project, task, task.getBuildLock());
            });
  }

  // The external libraries, from the test compile classpath, which holds main's too, and a
  // -sources dependency for each: the classifier, as on Maven, not Gradle's documentation variant,
  // which libraries such as Guava do not declare. Resolved leniently: a library without sources is
  // not an error.
  private static void registerLibraries(
      final Project project,
      final TaskProvider<LatheFactsTask> facts,
      final SourceSet main,
      final SourceSet test) {
    final Configuration testClasspath =
        project.getConfigurations().getByName(test.getCompileClasspathConfigurationName());
    final ArtifactCollection libraries = external(testClasspath);
    final ArtifactCollection mainLibraries =
        external(
            project.getConfigurations().getByName(main.getCompileClasspathConfigurationName()));
    final Configuration sources = configuration(project, LIBRARY_SOURCES, true);
    sources.setTransitive(false);
    // As the sources resolve, not before: a provider would be forced by a build script walking
    // every configuration's dependencies, resolving the test classpath while the build is still
    // configured, when the script may yet change it.
    final DependencyFactory dependencies = project.getDependencyFactory();
    sources.withDependencies(
        added ->
            added.addAll(
                sourcesOf(
                    dependencies,
                    testClasspath.getIncoming().getResolutionResult().getRootComponent().get())));
    final ArtifactCollection librarySources =
        sources.getIncoming().artifactView(view -> view.lenient(true)).getArtifacts();
    facts.configure(
        task -> {
          task.getLibraries()
              .set(libraries.getResolvedArtifacts().map(LatheProjectPlugin::byCoordinates));
          task.getLibraryFiles()
              .from(libraries.getArtifactFiles().filter(file -> FileUtil.isJarFile(file.toPath())));
          task.getLibrarySources()
              .set(librarySources.getResolvedArtifacts().map(LatheProjectPlugin::byCoordinates));
          task.getLibraryClasspath().from(mainLibraries.getArtifactFiles());
        });
  }

  private static ArtifactCollection external(final Configuration configuration) {
    return configuration
        .getIncoming()
        .artifactView(view -> view.componentFilter(ModuleComponentIdentifier.class::isInstance))
        .getArtifacts();
  }

  // A -sources dependency for every external module in the resolved graph. From the graph, not the
  // artifacts: a classpath can hold the output of the build's own tasks (Caffeine's codeGen), which
  // the configuration cache would need before those tasks run. A loop: components repeat.
  private static List<Dependency> sourcesOf(
      final DependencyFactory dependencies, final ResolvedComponentResult root) {
    final var modules = new TreeSet<String>();
    final var visited = new HashSet<ComponentIdentifier>();
    final var pending = new ArrayDeque<ResolvedComponentResult>(List.of(root));
    while (!pending.isEmpty()) {
      final ResolvedComponentResult component = pending.pop();
      if (!visited.add(component.getId())) {
        continue;
      }

      if (component.getId() instanceof ModuleComponentIdentifier id) {
        modules.add(coordinates(id));
      }

      for (final DependencyResult dependency : component.getDependencies()) {
        if (dependency instanceof ResolvedDependencyResult resolved) {
          pending.push(resolved.getSelected());
        }
      }
    }

    return modules.stream()
        .<Dependency>map(module -> dependencies.create("%s:sources".formatted(module)))
        .toList();
  }

  // Jars only: a POM-only module (GraalVM's polyglot "tools") puts its .pom on the classpath.
  private static Map<String, String> byCoordinates(final Set<ResolvedArtifactResult> artifacts) {
    return artifacts.stream()
        .filter(artifact -> FileUtil.isJarFile(artifact.getFile().toPath()))
        .collect(
            Collectors.toUnmodifiableMap(
                LatheProjectPlugin::coordinates,
                artifact -> artifact.getFile().getPath(),
                (first, ignored) -> first));
  }

  // group:name:version of an external library: the artifact views keep only modules.
  private static String coordinates(final ResolvedArtifactResult artifact) {
    return coordinates((ModuleComponentIdentifier) artifact.getId().getComponentIdentifier());
  }

  private static String coordinates(final ModuleComponentIdentifier id) {
    return "%s:%s:%s".formatted(id.getGroup(), id.getModule(), id.getVersion());
  }

  // Every shape a sibling takes on another project's classpath, its classes and resources
  // directories and its jar, maps to the mirror, which holds both.
  private static ProjectFacts javaFacts(
      final Project project, final SourceSet main, final SourceSet test) {
    final var tasks = project.getTasks();
    final JavaCompile mainCompile =
        tasks.named(main.getCompileJavaTaskName(), JavaCompile.class).get();
    final JavaCompile testCompile =
        tasks.named(test.getCompileJavaTaskName(), JavaCompile.class).get();
    final Jar jar = tasks.named(JavaPlugin.JAR_TASK_NAME, Jar.class).get();
    final String module = module(project);
    final Path root = project.getRootDir().toPath();
    final List<OutputData> outputs =
        Stream.of(
                Map.entry(
                    mainCompile.getDestinationDirectory().get().getAsFile(),
                    LatheLayout.CLASSES_DIR),
                Map.entry(jar.getArchiveFile().get().getAsFile(), LatheLayout.CLASSES_DIR),
                Map.entry(main.getOutput().getResourcesDir(), LatheLayout.CLASSES_DIR),
                Map.entry(
                    testCompile.getDestinationDirectory().get().getAsFile(),
                    LatheLayout.TEST_CLASSES_DIR),
                Map.entry(test.getOutput().getResourcesDir(), LatheLayout.TEST_CLASSES_DIR))
            .map(
                output ->
                    new OutputData(relative(root, output.getKey()), module, output.getValue()))
            .toList();
    final List<ResourceRootData> resourceRoots =
        Stream.of(main, test).flatMap(sourceSet -> resourceRoots(root, module, sourceSet)).toList();
    final JavaInstallationMetadata jdk = mainCompile.getJavaCompiler().get().getMetadata();
    return new ProjectFacts(
        module,
        outputs,
        resourceRoots,
        jdk.getInstallationPath().getAsFile().getPath(),
        jdk.getLanguageVersion().asInt(),
        JvmArgsWriter.accessFlags(
            Stream.of(mainCompile, testCompile)
                .flatMap(javac -> javac.getOptions().getForkOptions().getAllJvmArgs().stream())
                .toList()),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static Stream<ResourceRootData> resourceRoots(
      final Path root, final String module, final SourceSet sourceSet) {
    final String output = relative(root, sourceSet.getOutput().getResourcesDir());
    return sourceSet.getResources().getSrcDirs().stream()
        .map(dir -> new ResourceRootData(relative(root, dir), output, "", false, module));
  }

  static boolean insideRoot(final Path root, final Path projectDir) {
    return projectDir.normalize().startsWith(root.normalize());
  }

  private static String module(final Project project) {
    return relative(project.getRootDir().toPath(), project.getProjectDir());
  }

  private static String relative(final Path root, final File file) {
    return FileUtil.slashed(root.relativize(file.toPath()));
  }

  private static TaskProvider<LatheCaptureTask> registerCapture(
      final Project project,
      final SourceSet sourceSet,
      final String name,
      final String sourceTree,
      final Path moduleDir) {
    final TaskProvider<JavaCompile> compile =
        project.getTasks().named(sourceSet.getCompileJavaTaskName(), JavaCompile.class);
    final TaskProvider<LatheCaptureTask> capture =
        project
            .getTasks()
            .register(
                name,
                LatheCaptureTask.class,
                task -> {
                  final JavaCompile javac = compile.get();
                  task.dependsOn(compile, sourceSet.getProcessResourcesTaskName());
                  useBuildLock(project, task, task.getBuildLock());
                  task.getSourceTree().set(sourceTree);
                  task.getModuleDir().set(moduleDir.toFile());
                  task.getSources().from(sourceSet.getJava());
                  task.getSourceRoots().from(sourceSet.getJava().getSourceDirectories());
                  task.getClassesDir().set(javac.getDestinationDirectory());
                  task.getResourcesDir().set(sourceSet.getOutput().getResourcesDir());
                  task.getGeneratedSourcesDir()
                      .set(javac.getOptions().getGeneratedSourceOutputDirectory());
                  task.getClasspath().from(javac.getClasspath());
                  // A Callable may return null, Gradle's "no processor path".
                  task.getProcessorPath()
                      .from(
                          (Callable<FileCollection>)
                              () -> javac.getOptions().getAnnotationProcessorPath());
                  task.getCompilerArgs()
                      .set(project.provider(() -> javac.getOptions().getAllCompilerArgs()));
                  // Only a --release the compile used, as on Maven: one Gradle did not pass
                  // (it compiled with -source/-target) would forbid the --add-exports of JDK
                  // internals the compile may also pass.
                  task.getRelease().set(javac.getOptions().getRelease().map(String::valueOf));
                  task.getEncoding().set(project.provider(() -> javac.getOptions().getEncoding()));
                  task.getInferModulePath().set(javac.getModularity().getInferModulePath());
                });
    compile.configure(javac -> javac.finalizedBy(capture));
    return capture;
  }
}
