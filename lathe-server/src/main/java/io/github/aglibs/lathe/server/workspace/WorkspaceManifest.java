package io.github.aglibs.lathe.server.workspace;

import io.github.aglibs.lathe.analysis.LibrarySources;
import io.github.aglibs.lathe.core.ExceptionUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import io.github.aglibs.lathe.core.schema.BuildFileData;
import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import io.github.aglibs.lathe.core.schema.DependencyData;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class WorkspaceManifest {

  private static final Logger LOG = Logger.getLogger(WorkspaceManifest.class.getName());

  private final LibrarySources libraries;
  private final List<Path> typeIndexShardPaths;
  private final List<Path> buildFiles;
  // The content the sync saw, for the build files watched by content (POMs); OpenJDK's touch-only
  // batch markers have none.
  private final Map<Path, ContentFingerprint> buildFileFingerprints;
  private final List<Path> runnerClasspath;
  private final ResourceRootIndex resourceRoots;
  private final ReactorRewrite reactorRewrite;
  // The user-facing remediation when workspace.json has another schema version or cannot be read;
  // null when it loaded or is absent.
  private final String loadProblem;
  private final Path buildTool;
  private final List<Path> builtModuleDirs;

  private WorkspaceManifest(
      final LibrarySources libraries,
      final List<Path> typeIndexShardPaths,
      final List<Path> buildFiles,
      final Map<Path, ContentFingerprint> buildFileFingerprints,
      final List<Path> runnerClasspath,
      final ResourceRootIndex resourceRoots,
      final ReactorRewrite reactorRewrite,
      final String loadProblem,
      final Path buildTool,
      final List<Path> builtModuleDirs) {
    this.libraries = libraries;
    this.typeIndexShardPaths = typeIndexShardPaths;
    this.buildFiles = buildFiles;
    this.buildFileFingerprints = buildFileFingerprints;
    this.runnerClasspath = runnerClasspath;
    this.resourceRoots = resourceRoots;
    this.reactorRewrite = reactorRewrite;
    this.loadProblem = loadProblem;
    this.buildTool = buildTool;
    this.builtModuleDirs = builtModuleDirs;
  }

  public static WorkspaceManifest empty() {
    return emptyWith(null);
  }

  private static WorkspaceManifest emptyWith(final String loadProblem) {
    return new WorkspaceManifest(
        LibrarySources.none(),
        List.of(),
        List.of(),
        Map.of(),
        List.of(),
        ResourceRootIndex.empty(),
        ReactorRewrite.none(),
        loadProblem,
        null,
        List.of());
  }

  public static WorkspaceManifest load(final Path workspaceRoot) {
    final var file =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON);
    if (!Files.exists(file)) {
      return empty();
    }

    try {
      final var data = Json.read(file, WorkspaceManifestData.class);
      if (!LatheLayout.SCHEMA_VERSION.equals(data.schemaVersion())) {
        LOG.warning(
            () ->
                "[manifest] unexpected schemaVersion %s — ignoring"
                    .formatted(data.schemaVersion()));
        return emptyWith(
            LatheLayout.SCHEMA_REMEDIATION.formatted(
                data.schemaVersion(), LatheLayout.SCHEMA_VERSION));
      }

      final List<DependencyData> entries =
          data.dependencySources().stream().filter(e -> e.jar() != null).toList();
      final var jdk = data.jdk();
      final var typeIndexShardPaths =
          Stream.concat(
                  entries.stream()
                      .map(DependencyData::typeIndex)
                      .filter(p -> p != null && !p.isBlank())
                      .map(Path::of),
                  jdk != null && jdk.typeIndex() != null
                      ? Stream.of(jdk.typeIndex())
                      : Stream.empty())
              .distinct()
              .toList();
      final List<Path> resolvedBuildFiles =
          data.buildFiles().stream()
              .map(buildFile -> workspaceRoot.resolve(buildFile.path()))
              .toList();
      final Map<Path, ContentFingerprint> buildFileFingerprints =
          data.buildFiles().stream()
              .filter(buildFile -> buildFile.fingerprint() != null)
              .collect(
                  Collectors.toUnmodifiableMap(
                      buildFile -> workspaceRoot.resolve(buildFile.path()),
                      BuildFileData::fingerprint,
                      (first, ignored) -> first));
      final List<Path> runnerClasspath = data.runnerClasspath().stream().map(Path::of).toList();
      final var reactorRewrite = ReactorRewrite.of(workspaceRoot, data.outputs());
      return new WorkspaceManifest(
          LibrarySources.from(
              data.dependencySources(),
              jdk != null ? jdk.version() : null,
              jdk != null ? jdk.sourceDir() : null),
          typeIndexShardPaths,
          resolvedBuildFiles,
          buildFileFingerprints,
          runnerClasspath,
          ResourceRootIndex.build(workspaceRoot, reactorRewrite, data.resourceRoots()),
          reactorRewrite,
          null,
          data.buildTool() != null ? Path.of(data.buildTool()) : null,
          data.outputs().stream()
              .map(output -> workspaceRoot.resolve(output.module()).normalize())
              .distinct()
              .toList());
    } catch (final Exception e) {
      LOG.log(Level.WARNING, e, () -> "[manifest] failed to load workspace manifest");
      final Throwable cause = ExceptionUtil.rootCause(e);
      return emptyWith(
          LatheLayout.MANIFEST_UNREADABLE_REMEDIATION.formatted(
              Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName())));
    }
  }

  public ReactorRewrite reactorRewrite() {
    return reactorRewrite;
  }

  public Optional<Path> buildTool() {
    return Optional.ofNullable(buildTool);
  }

  // The modules the last build compiled. A file outside them (a module the build's profiles or JDK
  // leave out, or no module at all) is one a sync cannot give a module.
  // The innermost module directory the last build compiled that holds the file.
  public Optional<Path> builtModuleDirOf(final Path file) {
    final Path normalized = file.normalize();
    return builtModuleDirs.stream()
        .filter(normalized::startsWith)
        .max(Comparator.comparingInt(Path::getNameCount));
  }

  public Optional<String> loadProblem() {
    return Optional.ofNullable(loadProblem);
  }

  public Optional<Path> resourceDestination(final Path file) {
    return resourceRoots.destinationFor(file);
  }

  public List<Path> resourceSourceDirs() {
    return resourceRoots.sourceDirs();
  }

  // Each reactor resource dir mapped to its owning module (the resource-finder origin).
  public Map<Path, String> resourceDirModules() {
    return resourceRoots.sourceDirModules();
  }

  public List<Path> typeIndexShardPaths() {
    return typeIndexShardPaths;
  }

  public List<Path> buildFiles() {
    return buildFiles;
  }

  public Map<Path, ContentFingerprint> buildFileFingerprints() {
    return buildFileFingerprints;
  }

  public List<Path> runnerClasspath() {
    return runnerClasspath;
  }

  // The dependency jars and the JDK the workspace compiles against, with their sources.
  public LibrarySources libraries() {
    return libraries;
  }
}
