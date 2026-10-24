package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
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
import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
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

// Applied to every project by LatheSettingsPlugin. A Java project gets a capture task per main and
// test source set, finalizing its compile; the compile itself is left untouched, so its build-cache
// key is the same with or without Lathe. Every project publishes its facts, which the root
// project's latheSync merges.
public class LatheProjectPlugin implements Plugin<Project> {

  static final String SYNC_TASK = "latheSync";
  static final String FACTS_TASK = "latheFacts";
  private static final String FACTS_USAGE = "io.github.ag-libs.lathe-facts";
  private static final String FACTS_ELEMENTS = "latheFactsElements";
  private static final String PROJECT_FACTS = "latheProjectFacts";
  private static final String SERVER = "latheServer";
  private static final String MCP_SERVER = "latheMcpServer";
  private static final String BUILD_LOCK_SERVICE = "latheBuildLock";
  private static final String LIBRARY_SOURCES = "latheLibrarySources";

  @Override
  public void apply(final Project project) {
    final TaskProvider<LatheFactsTask> facts =
        project
            .getTasks()
            .register(
                FACTS_TASK,
                LatheFactsTask.class,
                task -> {
                  task.getFacts()
                      .set(
                          project.provider(
                              () ->
                                  Json.toJson(
                                      ProjectFacts.of(module(project), buildFile(project)))));
                  task.getFactsFile()
                      .set(
                          project
                              .getLayout()
                              .getBuildDirectory()
                              .file(LatheLayout.Gradle.FACTS_FILE));
                });
    factsConfiguration(project, FACTS_ELEMENTS, false)
        .getOutgoing()
        .artifact(facts.flatMap(LatheFactsTask::getFactsFile));
    project.getPlugins().withType(JavaPlugin.class, java -> registerJava(project, facts));
  }

  // The root's sync: every other project's facts arrive through a configuration, so the root reads
  // no other project's model, and the server jars are declared now so Gradle can resolve them.
  static void registerSync(
      final Project project, final Map<String, String> modulePaths, final boolean addMavenCentral) {
    final Configuration projectFacts = factsConfiguration(project, PROJECT_FACTS, true);
    modulePaths.values().stream()
        .filter(path -> !path.equals(project.getPath()))
        .forEach(
            path ->
                project
                    .getDependencies()
                    .add(PROJECT_FACTS, project.getDependencies().project(Map.of("path", path))));
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
    project
        .getTasks()
        .register(
            SYNC_TASK,
            LatheSyncTask.class,
            task -> {
              task.setDescription("Syncs the workspace for the Lathe language server.");
              useBuildLock(project, task, task.getBuildLock());
              task.getFacts()
                  .from(
                      (Callable<List<Object>>)
                          () ->
                              task.getModules().get().isEmpty()
                                  ? List.of(projectFacts, ownFacts)
                                  : List.of());
              task.dependsOn(
                  (Callable<List<String>>)
                      () ->
                          task.getModules().get().stream()
                              .map(module -> factsTaskPath(modulePaths, module))
                              .toList());
              task.getServerJars().from(server);
              task.getMcpServerJars().from(mcpServer);
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

    return ":".equals(path) ? ":" + FACTS_TASK : "%s:%s".formatted(path, FACTS_TASK);
  }

  private static Configuration resolvable(
      final Project project, final String name, final String coordinates) {
    final Configuration configuration = configuration(project, name, true);
    project.getDependencies().add(name, coordinates);
    return configuration;
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
    final Path moduleDir =
        rootDir
            .resolve(LatheLayout.LATHE_DIR)
            .resolve(rootDir.relativize(project.getProjectDir().toPath()));
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
    final DependencyFactory dependencies = project.getDependencyFactory();
    sources
        .getDependencies()
        .addAllLater(
            testClasspath
                .getIncoming()
                .getResolutionResult()
                .getRootComponent()
                .map(root -> sourcesOf(dependencies, root)));
    final ArtifactCollection librarySources =
        sources.getIncoming().artifactView(view -> view.lenient(true)).getArtifacts();
    facts.configure(
        task -> {
          task.getLibraries()
              .set(libraries.getResolvedArtifacts().map(LatheProjectPlugin::byCoordinates));
          task.getLibraryFiles().from(libraries.getArtifactFiles());
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

  private static Map<String, String> byCoordinates(final Set<ResolvedArtifactResult> artifacts) {
    return artifacts.stream()
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

  // Both shapes a sibling takes on another project's classpath, its classes directory and its jar,
  // map to the mirror.
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
                Map.entry(mainCompile.getDestinationDirectory(), LatheLayout.CLASSES_DIR),
                Map.entry(jar.getArchiveFile(), LatheLayout.CLASSES_DIR),
                Map.entry(testCompile.getDestinationDirectory(), LatheLayout.TEST_CLASSES_DIR))
            .map(
                output ->
                    new OutputData(
                        relative(root, output.getKey().get().getAsFile()),
                        module,
                        output.getValue()))
            .toList();
    final List<ResourceRootData> resourceRoots =
        Stream.of(main, test).flatMap(sourceSet -> resourceRoots(root, module, sourceSet)).toList();
    final JavaInstallationMetadata jdk = mainCompile.getJavaCompiler().get().getMetadata();
    return new ProjectFacts(
        module,
        buildFile(project),
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
        List.of());
  }

  private static Stream<ResourceRootData> resourceRoots(
      final Path root, final String module, final SourceSet sourceSet) {
    final String output = relative(root, sourceSet.getOutput().getResourcesDir());
    return sourceSet.getResources().getSrcDirs().stream()
        .map(dir -> new ResourceRootData(relative(root, dir), output, "", false, module));
  }

  private static String module(final Project project) {
    return relative(project.getRootDir().toPath(), project.getProjectDir());
  }

  private static String buildFile(final Project project) {
    return relative(project.getRootDir().toPath(), project.getBuildFile());
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
                  task.dependsOn(compile);
                  useBuildLock(project, task, task.getBuildLock());
                  task.getSourceTree().set(sourceTree);
                  task.getModuleDir().set(moduleDir.toFile());
                  task.getSources().from(sourceSet.getJava());
                  task.getSourceRoots().from(sourceSet.getJava().getSourceDirectories());
                  task.getClassesDir().set(javac.getDestinationDirectory());
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
                  task.getRelease()
                      .set(
                          javac
                              .getOptions()
                              .getRelease()
                              .map(String::valueOf)
                              .orElse(
                                  project.provider(
                                      () ->
                                          JavaVersion.toVersion(javac.getSourceCompatibility())
                                              .getMajorVersion())));
                  task.getEncoding().set(project.provider(() -> javac.getOptions().getEncoding()));
                  task.getInferModulePath().set(javac.getModularity().getInferModulePath());
                });
    compile.configure(javac -> javac.finalizedBy(capture));
    return capture;
  }
}
