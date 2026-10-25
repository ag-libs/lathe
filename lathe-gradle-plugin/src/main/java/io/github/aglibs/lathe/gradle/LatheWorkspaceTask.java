package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.ResourceRootData;
import io.github.aglibs.lathe.install.CapturePruner;
import io.github.aglibs.lathe.install.JvmArgsWriter;
import io.github.aglibs.lathe.install.LauncherCopier;
import io.github.aglibs.lathe.install.PluginProps;
import io.github.aglibs.lathe.install.ResolvedJar;
import io.github.aglibs.lathe.install.ServerInstaller;
import io.github.aglibs.lathe.install.SyncScript;
import io.github.aglibs.lathe.install.WorkspaceManifestWriter;
import io.github.aglibs.lathe.install.dependency.DependencySource;
import io.github.aglibs.lathe.install.dependency.DependencySourceSync;
import io.github.aglibs.lathe.install.jdk.JdkSource;
import io.github.aglibs.lathe.install.jdk.JdkSourceResolver;
import io.github.aglibs.lathe.install.jdk.JdkSourceSync;
import io.github.aglibs.lathe.install.typeindex.JdkTypeIndexSync;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

  private static final String SOURCES_CLASSIFIER = "sources";

  // Every project's directory, relative to the root ("" for the root project), from the settings.
  @Input
  public abstract ListProperty<String> getModuleDirs();

  @Classpath
  public abstract ConfigurableFileCollection getServerJars();

  @Classpath
  public abstract ConfigurableFileCollection getMcpServerJars();

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
    JdkSourceSync.extract(jdk);
    final List<ResourceRootData> resourceRoots =
        facts.stream().flatMap(fact -> fact.resourceRoots().stream()).toList();
    final List<OutputData> outputs =
        facts.stream().flatMap(fact -> fact.outputs().stream()).toList();
    final SyncScript script =
        GradleSyncScript.of(
            Path.of(getGradleHome().get()),
            Path.of(getDaemonJavaHome().get()),
            getInitScripts().get());
    final List<Map.Entry<DependencyFact, List<String>>> libraries = libraries(facts);
    final List<DependencySource> librarySources =
        DependencySourceSync.prepare(
            libraries.stream().map(LatheWorkspaceTask::source).toList(),
            libraries.stream().map(library -> jar(library.getKey())).toList());
    WorkspaceManifestWriter.write(
        root,
        librarySources,
        JdkTypeIndexSync.index(jdk),
        PluginProps.version(),
        facts.stream()
            .map(ProjectFacts::runnerClasspath)
            .filter(runner -> !runner.isEmpty())
            .findFirst()
            .orElse(List.of()),
        buildFiles(root),
        resourceRoots,
        outputs,
        script.tool());
    JvmArgsWriter.write(
        root,
        Stream.concat(
                facts.stream().flatMap(fact -> fact.accessFlags().stream()),
                Stream.of(getDaemonJvmArgs().getOrElse("").split("\\s+")))
            .toList());
    script.write(root);
    CapturePruner.prune(root, currentTrees(facts));
  }

  // What the build has: each project's trees from its facts, and both trees for a project not built
  // since it joined the build, so its captures stay until its facts say otherwise. A project gone
  // from the settings is pruned.
  private Map<Path, Set<String>> currentTrees(final List<ProjectFacts> facts) {
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
                    known.getOrDefault(
                        module, Set.of(LatheLayout.CLASSES_DIR, LatheLayout.TEST_CLASSES_DIR))));
  }

  static Path factsFile(final Path root, final String module) {
    return root.resolve(LatheLayout.LATHE_DIR)
        .resolve(module)
        .resolve(LatheLayout.Gradle.FACTS_FILE);
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
    final Stream<Path> rootFiles = LatheLayout.Gradle.ROOT_BUILD_FILES.stream().map(root::resolve);
    final Stream<Path> catalogs =
        IOUtil.unchecked(
            () ->
                FileUtil.walkFiles(
                    root.resolve(LatheLayout.Gradle.CATALOG_DIR),
                    path -> path.toString().endsWith(LatheLayout.Gradle.CATALOG_SUFFIX)))
            .stream();
    final Stream<Path> projectFiles =
        getModuleDirs().get().stream()
            .flatMap(
                module ->
                    LatheLayout.Gradle.PROJECT_BUILD_FILES.stream()
                        .map(name -> root.resolve(module).resolve(name)));
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
    return LatheLayout.Gradle.BUILD_LOGIC_SUFFIXES.stream().anyMatch(name::endsWith)
        && StreamSupport.stream(dir.relativize(file).spliterator(), false)
            .noneMatch(
                part -> LatheLayout.Gradle.BUILD_LOGIC_SKIPPED_DIRS.contains(part.toString()));
  }

  private static List<Path> paths(final FileCollection files) {
    return files.getFiles().stream().map(File::toPath).toList();
  }
}
