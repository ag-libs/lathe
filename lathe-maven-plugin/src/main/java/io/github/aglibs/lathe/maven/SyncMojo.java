package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.ParamStore;
import io.github.aglibs.lathe.core.Stopwatch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

@SuppressWarnings("unused")
@Mojo(
    name = "sync",
    aggregator = true,
    defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
    threadSafe = true)
public final class SyncMojo extends AbstractMojo {

  private static final String SOURCE_MARKER = ".lathe-source.properties";

  @Inject private RepositorySystem repositorySystem;

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  @Override
  public void execute() throws MojoExecutionException {
    if (!session.getCurrentProject().equals(session.getTopLevelProject())) {
      getLog().debug("[sync] skipping non top-level project");
      return;
    }

    final Path workspaceRoot = session.getTopLevelProject().getBasedir().toPath();
    final List<MavenProject> projects = sortedProjects(workspaceRoot);
    logModules(workspaceRoot, projects);
    final SourceResolutions sourceResolutions =
        resolveSources(
            externalDependencies(projects), remoteRepositories(projects), projects.size());
    final Map<String, Path> sourceDirs = extractSources(sourceResolutions.present().values());
    writeWorkspaceProperties(workspaceRoot, sourceResolutions, sourceDirs);
  }

  private List<MavenProject> sortedProjects(final Path workspaceRoot) {
    return session.getProjects().stream()
        .sorted(Comparator.comparing(project -> moduleRel(workspaceRoot, project)))
        .toList();
  }

  private void logModule(final Path workspaceRoot, final MavenProject project) {
    getLog().info("[sync] module " + moduleRel(workspaceRoot, project) + " " + gav(project));
  }

  private void logModules(final Path workspaceRoot, final List<MavenProject> projects) {
    projects.forEach(project -> logModule(workspaceRoot, project));
  }

  private Map<String, Dependency> externalDependencies(final List<MavenProject> projects) {
    final Set<String> reactorProjects = reactorProjects(projects);
    final Map<String, Dependency> dependencies =
        projects.stream()
            .flatMap(project -> project.getDependencies().stream())
            .filter(dependency -> !reactorProjects.contains(ga(dependency)))
            .collect(
                Collectors.toMap(
                    SyncMojo::dependencyKey,
                    dependency -> dependency,
                    (first, ignored) -> first,
                    TreeMap::new));
    getLog().info("[sync] dependencies " + dependencies.size() + " unique external artifacts");
    return dependencies;
  }

  private static Set<String> reactorProjects(final List<MavenProject> projects) {
    return projects.stream()
        .map(project -> project.getGroupId() + ":" + project.getArtifactId())
        .collect(Collectors.toSet());
  }

  private List<RemoteRepository> remoteRepositories(final List<MavenProject> projects) {
    final Map<String, RemoteRepository> repositories =
        projects.stream()
            .flatMap(project -> project.getRemoteProjectRepositories().stream())
            .collect(
                Collectors.toMap(
                    SyncMojo::repositoryKey,
                    repository -> repository,
                    (first, ignored) -> first,
                    TreeMap::new));
    return List.copyOf(repositories.values());
  }

  private SourceResolutions resolveSources(
      final Map<String, Dependency> dependencies,
      final List<RemoteRepository> repositories,
      final int moduleCount)
      throws MojoExecutionException {
    final Map<String, Artifact> sourceArtifacts = new TreeMap<>();
    final Set<String> missingSources = new TreeSet<>();
    final Set<String> skippedSources = new TreeSet<>();
    int resolved = 0;
    int missing = 0;
    int skipped = 0;
    for (final Dependency dependency : dependencies.values()) {
      if (dependency.getVersion() == null || dependency.getVersion().isBlank()) {
        skippedSources.add(dependencySourceGav(dependency));
        skipped++;
        continue;
      }

      final var sourceArtifact =
          new DefaultArtifact(
              dependency.getGroupId(),
              dependency.getArtifactId(),
              "sources",
              "jar",
              dependency.getVersion());
      final var request = new ArtifactRequest(sourceArtifact, repositories, null);
      try {
        final Artifact resolvedArtifact =
            repositorySystem.resolveArtifact(session.getRepositorySession(), request).getArtifact();
        sourceArtifacts.putIfAbsent(gav(resolvedArtifact), resolvedArtifact);
        resolved++;
      } catch (final ArtifactResolutionException e) {
        getLog().debug("[sync] source missing " + gav(dependency), e);
        missingSources.add(dependencySourceGav(dependency));
        missing++;
      } catch (final RuntimeException e) {
        throw new MojoExecutionException("lathe:sync failed to resolve source artifacts", e);
      }
    }

    getLog()
        .info(
            "[sync] sources %d resolved, %d missing, %d skipped from %d modules"
                .formatted(resolved, missing, skipped, moduleCount));
    return new SourceResolutions(
        Collections.unmodifiableMap(new TreeMap<>(sourceArtifacts)),
        Collections.unmodifiableSet(new TreeSet<>(missingSources)),
        Collections.unmodifiableSet(new TreeSet<>(skippedSources)));
  }

  private Map<String, Path> extractSources(final Collection<Artifact> artifacts)
      throws MojoExecutionException {
    final Path cacheRoot = userCacheRoot().resolve("deps");
    final Stopwatch t = Stopwatch.start();
    try {
      Files.createDirectories(cacheRoot);
      final List<SourceExtraction> extractions =
          artifacts.parallelStream()
              .map(artifact -> IOUtil.unchecked(() -> extractSource(cacheRoot, artifact)))
              .toList();
      final Map<Boolean, Long> counts =
          extractions.stream()
              .collect(
                  Collectors.partitioningBy(SourceExtraction::extracted, Collectors.counting()));
      getLog()
          .info(
              "[sync] extracted %d source artifacts, %d already cached in %dms"
                  .formatted(
                      counts.getOrDefault(true, 0L),
                      counts.getOrDefault(false, 0L),
                      t.elapsedMs()));
      return extractions.stream()
          .collect(
              Collectors.toMap(
                  SourceExtraction::gav,
                  SourceExtraction::sources,
                  (first, ignored) -> first,
                  TreeMap::new));
    } catch (final IOException | UncheckedIOException e) {
      throw new MojoExecutionException("lathe:sync failed to extract source artifacts", e);
    }
  }

  private SourceExtraction extractSource(final Path cacheRoot, final Artifact artifact)
      throws IOException {
    final Path sourceJar = artifact.getFile().toPath();
    final Path targetDir = sourceCacheDir(cacheRoot, artifact);
    if (isSourceCacheCurrent(targetDir, artifact, sourceJar)) {
      return new SourceExtraction(gav(artifact), targetDir, false);
    }

    Files.createDirectories(targetDir.getParent());
    final Path tempDir =
        Files.createTempDirectory(targetDir.getParent(), targetDir.getFileName() + ".tmp-");
    try {
      FileUtil.unzip(sourceJar, tempDir);
      writeSourceMarker(tempDir, artifact, sourceJar);
      if (Files.exists(targetDir)) {
        FileUtil.deleteDir(targetDir);
      }
      FileUtil.moveReplacing(tempDir, targetDir);
      return new SourceExtraction(gav(artifact), targetDir, true);
    } finally {
      if (Files.exists(tempDir)) {
        FileUtil.deleteDir(tempDir);
      }
    }
  }

  private void writeWorkspaceProperties(
      final Path workspaceRoot,
      final SourceResolutions sourceResolutions,
      final Map<String, Path> sourceDirs)
      throws MojoExecutionException {
    final Path latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    Path tempFile = null;
    try {
      Files.createDirectories(latheDir);
      tempFile = Files.createTempFile(latheDir, LatheLayout.WORKSPACE_PROPERTIES, ".tmp");
      writeWorkspaceProperties(tempFile, workspaceRoot, sourceResolutions, sourceDirs);
      FileUtil.moveReplacing(tempFile, latheDir.resolve(LatheLayout.WORKSPACE_PROPERTIES));
    } catch (final IOException e) {
      throw new MojoExecutionException("lathe:sync failed to write workspace properties", e);
    } finally {
      if (tempFile != null) {
        try {
          Files.deleteIfExists(tempFile);
        } catch (final IOException e) {
          getLog().debug("[sync] failed to clean temporary workspace properties", e);
        }
      }
    }
  }

  private void writeWorkspaceProperties(
      final Path file,
      final Path workspaceRoot,
      final SourceResolutions sourceResolutions,
      final Map<String, Path> sourceDirs)
      throws IOException {
    final ParamStore props = new ParamStore();
    props.set("schema", "1");
    props.set("workspaceRoot", workspaceRoot.toString());
    int index = 0;
    for (final Artifact artifact : sourceResolutions.present().values()) {
      writePresentDependencySource(props, index, artifact, sourceDirs.get(gav(artifact)));
      index++;
    }
    for (final String gav : sourceResolutions.missing()) {
      writeDependencySourceStatus(props, index, gav, "missing");
      index++;
    }
    for (final String gav : sourceResolutions.skipped()) {
      writeDependencySourceStatus(props, index, gav, "skipped");
      index++;
    }
    props.store(file);
  }

  private static void writePresentDependencySource(
      final ParamStore props, final int index, final Artifact artifact, final Path sources) {
    final String prefix = "dependencySource." + index + ".";
    props.set(prefix + "gav", gav(artifact));
    props.set(prefix + "status", "present");
    props.set(prefix + "sources", sources.toString());
    props.set(prefix + "sourceJar", artifact.getFile().toPath().toString());
  }

  private static void writeDependencySourceStatus(
      final ParamStore props, final int index, final String gav, final String status) {
    final String prefix = "dependencySource." + index + ".";
    props.set(prefix + "gav", gav);
    props.set(prefix + "status", status);
  }

  private boolean isSourceCacheCurrent(
      final Path targetDir, final Artifact artifact, final Path sourceJar) throws IOException {
    final Path marker = targetDir.resolve(SOURCE_MARKER);
    if (!Files.exists(marker)) {
      return false;
    }

    final Properties props = new Properties();
    try (final var in = Files.newInputStream(marker)) {
      props.load(in);
    }

    return "1".equals(props.getProperty("schema"))
        && gav(artifact).equals(props.getProperty("gav"))
        && sourceJar.toString().equals(props.getProperty("sourceJar"))
        && Long.toString(Files.size(sourceJar)).equals(props.getProperty("sourceJar.size"))
        && Long.toString(Files.getLastModifiedTime(sourceJar).toMillis())
            .equals(props.getProperty("sourceJar.modified"));
  }

  private void writeSourceMarker(final Path tempDir, final Artifact artifact, final Path sourceJar)
      throws IOException {
    final Properties props = new Properties();
    props.setProperty("schema", "1");
    props.setProperty("gav", gav(artifact));
    props.setProperty("sourceJar", sourceJar.toString());
    props.setProperty("sourceJar.size", Long.toString(Files.size(sourceJar)));
    props.setProperty(
        "sourceJar.modified", Long.toString(Files.getLastModifiedTime(sourceJar).toMillis()));
    try (final var out = Files.newOutputStream(tempDir.resolve(SOURCE_MARKER))) {
      props.store(out, null);
    }
  }

  private static Path userCacheRoot() {
    return Path.of(System.getProperty("user.home"), ".cache", "lathe");
  }

  private static Path sourceCacheDir(final Path cacheRoot, final Artifact artifact) {
    return cacheRoot
        .resolve(artifact.getGroupId().replace('.', '/'))
        .resolve(artifact.getArtifactId())
        .resolve(artifact.getVersion());
  }

  private static String moduleRel(final Path workspaceRoot, final MavenProject project) {
    final String rel = workspaceRoot.relativize(project.getBasedir().toPath()).toString();
    if (rel.isEmpty()) {
      return ".";
    }

    return rel;
  }

  private static String gav(final MavenProject project) {
    return "%s:%s:%s"
        .formatted(project.getGroupId(), project.getArtifactId(), project.getVersion());
  }

  private static String ga(final Dependency dependency) {
    return dependency.getGroupId() + ":" + dependency.getArtifactId();
  }

  private static String gav(final Dependency dependency) {
    return "%s:%s:%s:%s"
        .formatted(
            dependency.getGroupId(),
            dependency.getArtifactId(),
            dependency.getType(),
            dependency.getVersion());
  }

  private static String dependencySourceGav(final Dependency dependency) {
    return "%s:%s:%s"
        .formatted(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
  }

  private static String gav(final Artifact artifact) {
    return "%s:%s:%s"
        .formatted(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
  }

  private static String dependencyKey(final Dependency dependency) {
    return "%s:%s:%s:%s:%s"
        .formatted(
            dependency.getGroupId(),
            dependency.getArtifactId(),
            dependency.getType(),
            dependency.getClassifier(),
            dependency.getVersion());
  }

  private static String repositoryKey(final RemoteRepository repository) {
    return repository.getId() + " " + repository.getUrl();
  }

  private record SourceResolutions(
      Map<String, Artifact> present, Set<String> missing, Set<String> skipped) {}

  private record SourceExtraction(String gav, Path sources, boolean extracted) {}
}
