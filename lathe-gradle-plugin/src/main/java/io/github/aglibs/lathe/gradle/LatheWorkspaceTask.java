package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.ResourceRootData;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import io.github.aglibs.lathe.install.CapturePruner;
import io.github.aglibs.lathe.install.FormatterSteps;
import io.github.aglibs.lathe.install.JvmArgsWriter;
import io.github.aglibs.lathe.install.LauncherCopier;
import io.github.aglibs.lathe.install.PluginProps;
import io.github.aglibs.lathe.install.ResolvedJar;
import io.github.aglibs.lathe.install.ServerInstaller;
import io.github.aglibs.lathe.install.StyleWriter;
import io.github.aglibs.lathe.install.SyncScript;
import io.github.aglibs.lathe.install.WorkspaceSync;
import io.github.aglibs.lathe.install.dependency.DependencySource;
import io.github.aglibs.lathe.install.jdk.JdkSource;
import io.github.aglibs.lathe.install.jdk.JdkSourceResolver;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

// The workspace files every build refreshes, the counterpart of the Maven sync: workspace.json and
// the files beside it from every project's facts, read from disk as each build last wrote them, so
// a build of one project refreshes them without building the others; the server install; and the
// pruning of captures the build no longer has. Finalizes each project's facts, and latheSync.
@UntrackedTask(because = "It refreshes the workspace files and the server install every run.")
public abstract class LatheWorkspaceTask extends DefaultTask {

  // The build files a sync fingerprints, so the server notices a changed build: the settings
  // file, gradle.properties and the version catalogs at the root, each project's build file, and
  // the build logic in buildSrc/ and included builds, where Gradle builds keep their compiler
  // settings.
  private static final List<String> ROOT_BUILD_FILES =
      List.of("settings.gradle", "settings.gradle.kts", "gradle.properties");
  private static final List<String> PROJECT_BUILD_FILES =
      List.of("build.gradle", "build.gradle.kts");
  private static final String CATALOG_DIR = "gradle";
  private static final String CATALOG_SUFFIX = ".toml";
  private static final List<String> BUILD_LOGIC_SUFFIXES =
      List.of(".gradle", ".gradle.kts", ".java", ".kt", ".groovy", ".toml", ".properties");
  // Each project's facts for the root's workspace write to merge, in .lathe/<module>/.
  private static final String FACTS_FILE = "lathe-facts.json";
  // Output and state directories inside a build-logic build, never part of it.
  private static final List<String> BUILD_LOGIC_SKIPPED_DIRS = List.of("build", ".gradle");

  private static final String SOURCES_CLASSIFIER = "sources";

  // Every project's directory, relative to the root ("" for the root project), from the settings.
  @Input
  public abstract ListProperty<String> getModuleDirs();

  @Classpath
  public abstract ConfigurableFileCollection getServerJars();

  @Classpath
  public abstract ConfigurableFileCollection getMcpServerJars();

  // The engine lathe-style.json declares, pinned or not; absent when it declares none.
  @Input
  @Optional
  public abstract Property<String> getDeclaredEngine();

  // The declared pinned formatter, completed from Spotless's defaults; absent when there is none.
  @Input
  @Optional
  public abstract Property<String> getFormatter();

  @Classpath
  public abstract ConfigurableFileCollection getFormatterJars();

  // Why some of the formatter's jars did not resolve; empty when all did.
  @Input
  public abstract Property<String> getFormatterUnresolved();

  // Whether the declared formatter's version is the default of the Spotless Lathe pins.
  @Input
  public abstract Property<Boolean> getPinnedSpotlessDefaults();

  @Internal
  public abstract DirectoryProperty getWorkspaceRoot();

  @Input
  public abstract ListProperty<String> getBuildLogicDirs();

  @Input
  public abstract Property<String> getGradleHome();

  @Input
  public abstract Property<String> getDaemonJavaHome();

  @Input
  public abstract ListProperty<String> getInitScripts();

  @Input
  @Optional
  public abstract Property<String> getDaemonJvmArgs();

  // Asked for as the task starts, so the build holds .lathe/lathe.lock from here until it ends.
  @Internal
  public abstract Property<LatheBuildLockService> getBuildLock();

  // Every build runs this, so it never fails one: a problem is a warning, and the editor keeps the
  // workspace files the last refresh wrote.
  @TaskAction
  public void write() {
    getBuildLock().get();
    try {
      write(getWorkspaceRoot().get().getAsFile().toPath());
    } catch (final IOException | RuntimeException e) {
      getLogger().warn("[lathe] workspace files not refreshed: {}", e.getMessage(), e);
    }
  }

  private void write(final Path root) throws IOException {
    install(root);
    final List<ProjectFacts> facts =
        getModuleDirs().get().stream()
            .map(module -> factsFile(root, module))
            .filter(Files::isRegularFile)
            .map(file -> IOUtil.unchecked(() -> Json.read(file, ProjectFacts.class)))
            .sorted(Comparator.comparing(ProjectFacts::module))
            .toList();
    final JdkSource jdk = compilingJdk(facts);
    final List<ResourceRootData> resourceRoots =
        facts.stream().flatMap(fact -> fact.resourceRoots().stream()).toList();
    // Only a tree with Java sources has a mirror to stand in for its outputs: a project without
    // (Micronaut's runtime, resources only) still has a jar, which replay must take as it is.
    final List<OutputData> outputs =
        facts.stream()
            .flatMap(
                fact ->
                    fact.outputs().stream().filter(output -> fact.trees().contains(output.tree())))
            .toList();
    final SyncScript script =
        GradleSyncScript.of(
            Path.of(getGradleHome().get()),
            Path.of(getDaemonJavaHome().get()),
            getInitScripts().get());
    final List<Map.Entry<DependencyFact, List<String>>> libraries = libraries(facts);
    WorkspaceSync.write(
        new WorkspaceSync.Inputs(
            root,
            WorkspaceSync.prepareCaches(
                libraries.stream().map(LatheWorkspaceTask::source).toList(),
                libraries.stream().map(library -> jar(library.getKey())).toList(),
                jdk),
            PluginProps.version(),
            runnerClasspath(
                facts,
                module ->
                    getLogger()
                        .warn(
                            "[lathe] {}: the test runner its last build recorded is gone, so it is"
                                + " not used; build that project again to refresh it",
                            module.isEmpty() ? "the root project" : module)),
            buildFiles(root),
            resourceRoots,
            outputs,
            script));
    final List<String> formatterGrants = writeStyle(root, facts);
    JvmArgsWriter.write(
        root,
        Stream.of(
                facts.stream().flatMap(fact -> fact.accessFlags().stream()),
                Stream.of(getDaemonJvmArgs().getOrElse("").split("\\s+")),
                formatterGrants.stream())
            .flatMap(tokens -> tokens)
            .toList());
    CapturePruner.prune(root, currentTrees(root, facts));
  }

  // .lathe/style.json for the declared pinned formatter, with its resolved jars and the indent it
  // implies; returns the javac access it needs. Without one, there is no generated style: the
  // server reads any other declaration itself. Its warnings are the task's own, so a reused
  // configuration cache still shows them.
  private List<String> writeStyle(final Path root, final List<ProjectFacts> facts) {
    if (!getFormatter().isPresent()) {
      StyleWriter.delete(root);
      warnUnformatted(facts);
      return List.of();
    }

    final FormatterSpec completed = Json.fromJson(getFormatter().get(), FormatterSpec.class);
    final List<String> classpath = paths(getFormatterJars()).stream().map(Path::toString).toList();
    if (classpath.isEmpty() || !getFormatterUnresolved().get().isEmpty()) {
      getLogger()
          .warn(
              "[lathe] formatter {} {}: not every jar resolved, formatting disabled: {}",
              completed.engine(),
              completed.version(),
              getFormatterUnresolved().get());
      StyleWriter.delete(root);
      return List.of();
    }

    if (getPinnedSpotlessDefaults().get() && facts.stream().anyMatch(ProjectFacts::spotless)) {
      getLogger()
          .warn(
              "[lathe] formatter {} {} is the default of the Spotless Lathe pins, which may not be"
                  + " the one your build's Spotless runs; declare its version in {}",
              completed.engine(),
              completed.version(),
              LatheLayout.STYLE_SHARED_FILE);
    }

    final FormatterSpec resolved = FormatterSteps.withClasspath(completed, classpath);
    StyleWriter.write(root, new WorkspaceStyleData(resolved, FormatterSteps.indent(resolved)));
    return FormatterSteps.javacGrants(resolved);
  }

  // A declared pinned formatter that could not be completed, or Spotless with no declaration:
  // Gradle's Spotless keeps its settings out of reach, so the project must say what it formats
  // with; declaring "none" silences this.
  private void warnUnformatted(final List<ProjectFacts> facts) {
    if (getDeclaredEngine().isPresent()) {
      final var declared = new FormatterSpec(getDeclaredEngine().get(), List.of());
      if (FormatterSteps.pinned(declared)) {
        getLogger()
            .warn(
                "[lathe] formatter {} declared in {} could not be completed, formatting disabled;"
                    + " see the warning when the build was configured",
                declared.engine(),
                LatheLayout.STYLE_SHARED_FILE);
      }

      return;
    }

    if (facts.stream().noneMatch(ProjectFacts::spotless)) {
      return;
    }

    getLogger()
        .warn(
            "[lathe] Spotless formats this build, but Lathe cannot read its settings on Gradle, so"
                + " the editor does not format. Declare the formatter Spotless runs in {} at the"
                + " root, for example {}, or {} to keep formatting off",
            LatheLayout.STYLE_SHARED_FILE,
            "{\"formatter\": {\"engine\": \"google\", \"version\": \"<the google-java-format"
                + " version Spotless runs>\"}}",
            "{\"formatter\": {\"engine\": \"none\"}}");
  }

  // What the build has: each project's trees from its facts, and, for a project not built since it
  // joined the build, the trees it already has, so its captures stay until its facts say otherwise.
  // A project gone from the settings is pruned.
  private Map<Path, Set<String>> currentTrees(final Path root, final List<ProjectFacts> facts) {
    final Map<String, Set<String>> known =
        facts.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    ProjectFacts::module, fact -> Set.copyOf(fact.trees())));
    return getModuleDirs().get().stream()
        .collect(
            Collectors.toUnmodifiableMap(
                Path::of,
                module ->
                    known.containsKey(module)
                        ? known.get(module)
                        : capturedTrees(root.resolve(LatheLayout.moduleDir(module)))));
  }

  private static Set<String> capturedTrees(final Path moduleDir) {
    return IOUtil.unchecked(() -> FileUtil.filesIn(moduleDir, LatheLayout::isParamsFile)).stream()
        .map(LatheLayout::paramsSourceTree)
        .collect(Collectors.toUnmodifiableSet());
  }

  static Path factsFile(final Path root, final String module) {
    return root.resolve(LatheLayout.moduleDir(module)).resolve(FACTS_FILE);
  }

  // Each external library once, with the classpath of the first project that compiles against it,
  // as on Maven; sorted by coordinates, so workspace.json does not change with the facts' order.
  private static List<Map.Entry<DependencyFact, List<String>>> libraries(
      final List<ProjectFacts> facts) {
    return List.copyOf(
        facts.stream()
            .flatMap(
                fact ->
                    fact.dependencies().stream()
                        .map(library -> Map.entry(library, fact.dependencyClasspath())))
            .collect(
                Collectors.toMap(
                    library -> library.getKey().gav(),
                    library -> library,
                    (first, ignored) -> first,
                    TreeMap::new))
            .values());
  }

  private static DependencySource source(final Map.Entry<DependencyFact, List<String>> entry) {
    final DependencyFact library = entry.getKey();
    final List<Path> classpath = entry.getValue().stream().map(Path::of).toList();
    if (library.sourcesJar() == null) {
      return DependencySource.missing(library.gav(), Path.of(library.jar()), classpath);
    }

    return DependencySource.present(
        library.gav(),
        Path.of(library.jar()),
        LatheLayout.artifactDir(
            LatheLayout.userCacheRoot().resolve(LatheLayout.CACHE_DEPS_DIR),
            library.group(),
            library.name(),
            library.version()),
        new ResolvedJar(
            library.group(),
            library.name(),
            library.version(),
            SOURCES_CLASSIFIER,
            Path.of(library.sourcesJar())),
        classpath);
  }

  private static ResolvedJar jar(final DependencyFact library) {
    return new ResolvedJar(
        library.group(), library.name(), library.version(), "", Path.of(library.jar()));
  }

  // The server and its launchers, unless the build could not resolve them (offline, or no
  // repository holding them): the editor then keeps the server it has.
  private void install(final Path root) {
    final List<Path> server = paths(getServerJars());
    final List<Path> mcpServer = paths(getMcpServerJars());
    if (server.isEmpty() || mcpServer.isEmpty()) {
      getLogger().warn("[lathe] the Lathe server could not be resolved; not installed");
      return;
    }

    final var installer =
        new ServerInstaller(
            new GradleArtifactResolver(
                Map.of(
                    ServerInstaller.SERVER_COORDINATES,
                    server,
                    ServerInstaller.MCP_SERVER_COORDINATES,
                    mcpServer)));
    LauncherCopier.copyToWorkspace(root, installer.install());
  }

  // The runner of the first project whose runner jars are all in place. A project this build did
  // not refresh keeps its last facts, whose runner may come from a build since deleted, and one
  // stale project would otherwise fail every test run with a missing runner class.
  static List<String> runnerClasspath(
      final List<ProjectFacts> facts, final Consumer<String> staleModule) {
    final List<ProjectFacts> withRunner =
        facts.stream().filter(fact -> !fact.runnerClasspath().isEmpty()).toList();
    withRunner.stream()
        .filter(fact -> !runnerInPlace(fact))
        .map(ProjectFacts::module)
        .forEach(staleModule);
    return withRunner.stream()
        .filter(LatheWorkspaceTask::runnerInPlace)
        .map(ProjectFacts::runnerClasspath)
        .findFirst()
        .orElse(List.of());
  }

  private static boolean runnerInPlace(final ProjectFacts fact) {
    return fact.runnerClasspath().stream().map(Path::of).allMatch(Files::exists);
  }

  // The newest JDK any project compiles with, as the server's: the server then parses what every
  // module's compile accepts. One older than the server runs on does not count (a toolchain on JDK
  // 11; the server's javac then compiles for 11 with --release); a build with none runs the server
  // on the JDK Gradle runs on, which is new enough.
  static JdkSource compilingJdk(final List<ProjectFacts> facts) {
    return facts.stream()
        .filter(
            fact -> fact.javaHome() != null && fact.javaVersion() >= LatheLayout.MIN_JAVA_VERSION)
        .max(Comparator.comparingInt(ProjectFacts::javaVersion))
        .map(fact -> JdkSourceResolver.resolveAt(Path.of(fact.javaHome())))
        .orElseGet(JdkSourceResolver::resolve);
  }

  private List<String> buildFiles(final Path root) {
    final Stream<Path> rootFiles = ROOT_BUILD_FILES.stream().map(root::resolve);
    final Stream<Path> catalogs =
        IOUtil.unchecked(
            () ->
                FileUtil.walkFiles(
                    root.resolve(CATALOG_DIR), path -> path.toString().endsWith(CATALOG_SUFFIX)))
            .stream();
    final Stream<Path> projectFiles =
        getModuleDirs().get().stream()
            .flatMap(
                module ->
                    PROJECT_BUILD_FILES.stream().map(name -> root.resolve(module).resolve(name)));
    final Stream<Path> logic =
        getBuildLogicDirs().get().stream()
            .map(Path::of)
            .flatMap(LatheWorkspaceTask::buildLogicFiles);
    return Stream.of(rootFiles, catalogs, projectFiles, logic)
        .flatMap(files -> files)
        .filter(Files::isRegularFile)
        .map(file -> FileUtil.slashed(root.relativize(file)))
        .distinct()
        .sorted()
        .toList();
  }

  private static Stream<Path> buildLogicFiles(final Path dir) {
    return IOUtil.unchecked(() -> FileUtil.walkFiles(dir, file -> isBuildLogicFile(dir, file)))
        .stream();
  }

  // Any build/ or .gradle/ on the way down is output, also a nested project's.
  private static boolean isBuildLogicFile(final Path dir, final Path file) {
    final var name = file.getFileName().toString();
    return BUILD_LOGIC_SUFFIXES.stream().anyMatch(name::endsWith)
        && StreamSupport.stream(dir.relativize(file).spliterator(), false)
            .noneMatch(part -> BUILD_LOGIC_SKIPPED_DIRS.contains(part.toString()));
  }

  private static List<Path> paths(final FileCollection files) {
    return files.getFiles().stream().map(File::toPath).toList();
  }
}
