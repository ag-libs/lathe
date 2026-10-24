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
import io.github.aglibs.lathe.install.ServerInstaller;
import io.github.aglibs.lathe.install.SyncScript;
import io.github.aglibs.lathe.install.WorkspaceManifestWriter;
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
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.tasks.options.Option;

// The root project's sync, the counterpart of Maven's lathe:sync: it merges every project's facts
// into workspace.json, writes the workspace files beside it, prunes the captures of source trees
// the build no longer has, and installs the server. With --module it builds only those modules'
// captures and leaves the workspace files as the last full sync wrote them, as Maven does under
// -pl.
@UntrackedTask(because = "It refreshes the workspace files and the server install every run.")
public abstract class LatheSyncTask extends DefaultTask {

  static final String MODULE_OPTION = "module";

  @InputFiles
  @PathSensitive(PathSensitivity.NONE)
  public abstract ConfigurableFileCollection getFacts();

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

  @Internal
  @Option(
      option = MODULE_OPTION,
      description = "Syncs only this module (its directory); repeatable.")
  public abstract ListProperty<String> getModules();

  // Asked for as the task starts, so the build holds .lathe/lathe.lock from here until it ends.
  @Internal
  public abstract Property<LatheBuildLockService> getBuildLock();

  @TaskAction
  public void sync() throws IOException {
    getBuildLock().get();
    final Path root = getWorkspaceRoot().get().getAsFile().toPath();
    final var installer =
        new ServerInstaller(
            new GradleArtifactResolver(
                Map.of(
                    ServerInstaller.SERVER_COORDINATES, paths(getServerJars()),
                    ServerInstaller.MCP_SERVER_COORDINATES, paths(getMcpServerJars()))));
    LauncherCopier.copyToWorkspace(root, installer.install());
    if (!getModules().get().isEmpty()) {
      return;
    }

    final List<ProjectFacts> facts =
        getFacts().getFiles().stream()
            .map(file -> IOUtil.unchecked(() -> Json.read(file.toPath(), ProjectFacts.class)))
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
    WorkspaceManifestWriter.write(
        root,
        List.of(),
        JdkTypeIndexSync.index(jdk),
        PluginProps.version(),
        List.of(),
        buildFiles(root, facts),
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
    CapturePruner.prune(
        root,
        facts.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    fact -> Path.of(fact.module()), fact -> Set.copyOf(fact.trees()))));
  }

  // The newest JDK any project compiles with, as the server's: the server then parses what every
  // module's compile accepts. A build without Java runs the server on the JDK Gradle runs on.
  private static JdkSource compilingJdk(final List<ProjectFacts> facts) {
    return facts.stream()
        .filter(fact -> fact.javaHome() != null)
        .max(Comparator.comparingInt(ProjectFacts::javaVersion))
        .map(fact -> JdkSourceResolver.resolveAt(Path.of(fact.javaHome())))
        .orElseGet(JdkSourceResolver::resolve);
  }

  private List<String> buildFiles(final Path root, final List<ProjectFacts> facts) {
    final Stream<Path> rootFiles = LatheLayout.Gradle.ROOT_BUILD_FILES.stream().map(root::resolve);
    final Stream<Path> catalogs =
        IOUtil.unchecked(
            () ->
                FileUtil.walkFiles(
                    root.resolve(LatheLayout.Gradle.CATALOG_DIR),
                    path -> path.toString().endsWith(LatheLayout.Gradle.CATALOG_SUFFIX)))
            .stream();
    final Stream<Path> projectFiles = facts.stream().map(fact -> root.resolve(fact.buildFile()));
    final Stream<Path> logic =
        getBuildLogicDirs().get().stream().map(Path::of).flatMap(LatheSyncTask::buildLogicFiles);
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
