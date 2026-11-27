package io.github.aglibs.lathe.server.workspace;

import io.github.aglibs.lathe.analysis.LibrarySources;
import io.github.aglibs.lathe.core.ExceptionUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import io.github.aglibs.lathe.core.schema.BuildFileData;
import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import io.github.aglibs.lathe.core.schema.DependencyData;
import io.github.aglibs.lathe.core.schema.OtherLanguage;
import io.github.aglibs.lathe.core.schema.SourceStatus;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class WorkspaceManifest {

  private static final Logger LOG = Logger.getLogger(WorkspaceManifest.class.getName());

  private final LibrarySources libraries;
  private final List<Path> typeIndexShardPaths;
  // The shards of the dependencies without sources (none published, or an unreadable sources jar):
  // their types have no file to point at, only the dependency that declares them.
  private final Set<Path> sourcelessShardPaths;
  private final List<Path> buildFiles;
  // The content the sync saw, for the build files watched by content (POMs); OpenJDK's touch-only
  // batch markers have none.
  private final Map<Path, ContentFingerprint> buildFileFingerprints;
  private final List<Path> runnerClasspath;
  // A runner for each JUnit platform version the workspace's tests use, by that version.
  private final Map<String, List<Path>> runnerClasspaths;
  private final ResourceRootIndex resourceRoots;
  private final ReactorRewrite reactorRewrite;
  // The user-facing remediation when workspace.json is missing, has another schema version or
  // cannot be read; null when it loaded, or before any load (empty()).
  private final String loadProblem;
  // Whether workspace.json was absent, not merely unusable: a workspace with no module yet has none
  // either, and its own message says more than this one.
  private final boolean missing;
  private final Path buildTool;
  private final List<Path> builtModuleDirs;
  private final List<Path> buildDirs;
  // The built modules with sources of each language Lathe does not read, mixed ones too: an answer
  // about a symbol's uses cannot vouch for theirs, and the summary names them.
  private final Map<OtherLanguage, List<Path>> unreadModuleDirs;
  // Why go-to-definition into the JDK finds no source, when the build found no JDK sources; null
  // when they are there.
  private final String jdkSourcesNote;

  private WorkspaceManifest(
      final LibrarySources libraries,
      final List<Path> typeIndexShardPaths,
      final Set<Path> sourcelessShardPaths,
      final List<Path> buildFiles,
      final Map<Path, ContentFingerprint> buildFileFingerprints,
      final List<Path> runnerClasspath,
      final Map<String, List<Path>> runnerClasspaths,
      final ResourceRootIndex resourceRoots,
      final ReactorRewrite reactorRewrite,
      final String loadProblem,
      final boolean missing,
      final Path buildTool,
      final List<Path> builtModuleDirs,
      final List<Path> buildDirs,
      final Map<OtherLanguage, List<Path>> unreadModuleDirs,
      final String jdkSourcesNote) {
    this.libraries = libraries;
    this.typeIndexShardPaths = typeIndexShardPaths;
    this.sourcelessShardPaths = sourcelessShardPaths;
    this.buildFiles = buildFiles;
    this.buildFileFingerprints = buildFileFingerprints;
    this.runnerClasspath = runnerClasspath;
    this.runnerClasspaths = runnerClasspaths;
    this.resourceRoots = resourceRoots;
    this.reactorRewrite = reactorRewrite;
    this.loadProblem = loadProblem;
    this.missing = missing;
    this.buildTool = buildTool;
    this.builtModuleDirs = builtModuleDirs;
    this.buildDirs = buildDirs;
    this.unreadModuleDirs = Map.copyOf(unreadModuleDirs);
    this.jdkSourcesNote = jdkSourcesNote;
  }

  public static WorkspaceManifest empty() {
    return emptyWith(null, false);
  }

  private static WorkspaceManifest emptyWith(final String loadProblem, final boolean missing) {
    return new WorkspaceManifest(
        LibrarySources.none(),
        List.of(),
        Set.of(),
        List.of(),
        Map.of(),
        List.of(),
        Map.of(),
        ResourceRootIndex.empty(),
        ReactorRewrite.none(),
        loadProblem,
        missing,
        null,
        List.of(),
        List.of(),
        Map.of(),
        null);
  }

  public static WorkspaceManifest load(final Path workspaceRoot) {
    final var file =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON);
    // Without it the server has no module graph: an answer about a symbol's uses would stay in its
    // file's module, so the answers say so.
    if (!Files.exists(file)) {
      return emptyWith(LatheLayout.MANIFEST_MISSING_REMEDIATION, true);
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
                data.schemaVersion(), LatheLayout.SCHEMA_VERSION),
            false);
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
      // One jar can be listed twice; the first entry stands for it, as LibrarySources has it.
      final Set<Path> sourcelessShardPaths =
          entries.stream()
              .collect(
                  Collectors.toMap(
                      DependencyData::jar, e -> e, (first, ignored) -> first, LinkedHashMap::new))
              .values()
              .stream()
              .filter(e -> e.status() == SourceStatus.MISSING)
              .map(DependencyData::typeIndex)
              .filter(p -> p != null && !p.isBlank())
              .map(Path::of)
              .collect(Collectors.toUnmodifiableSet());
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
      final Map<String, List<Path>> runnerClasspaths =
          data.runnerClasspaths().entrySet().stream()
              .collect(
                  Collectors.toUnmodifiableMap(
                      Map.Entry::getKey,
                      entry -> entry.getValue().stream().map(Path::of).toList()));
      final ReactorRewrite reactorRewrite =
          ReactorRewrite.of(
              workspaceRoot, PackedJars.withPackedTrees(workspaceRoot, data.outputs()));
      return new WorkspaceManifest(
          LibrarySources.from(
              data.dependencySources(),
              jdk != null ? jdk.version() : null,
              jdk != null ? jdk.sourceDir() : null),
          typeIndexShardPaths,
          sourcelessShardPaths,
          resolvedBuildFiles,
          buildFileFingerprints,
          runnerClasspath,
          runnerClasspaths,
          ResourceRootIndex.build(workspaceRoot, reactorRewrite, data.resourceRoots()),
          reactorRewrite,
          null,
          false,
          data.buildTool() != null ? Path.of(data.buildTool()) : null,
          data.outputs().stream()
              .map(output -> workspaceRoot.resolve(output.module()).normalize())
              .distinct()
              .toList(),
          data.buildDirs().stream().map(dir -> workspaceRoot.resolve(dir).normalize()).toList(),
          unreadModuleDirs(workspaceRoot, data.otherLanguages()),
          jdk != null ? jdk.missingSourcesNote().orElse(null) : null);
    } catch (final Exception e) {
      LOG.log(Level.WARNING, e, () -> "[manifest] failed to load workspace manifest");
      final Throwable cause = ExceptionUtil.rootCause(e);
      return emptyWith(
          LatheLayout.MANIFEST_UNREADABLE_REMEDIATION.formatted(
              Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName())),
          false);
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

  // Each module's build directory, as its front-end recorded it: build output, not sources.
  public List<Path> buildDirs() {
    return buildDirs;
  }

  // The modules whose sources hold the language, as the build reported them: Lathe reads none of
  // it.
  public List<Path> unreadModuleDirs(final OtherLanguage language) {
    return unreadModuleDirs.getOrDefault(language, List.of());
  }

  private static Map<OtherLanguage, List<Path>> unreadModuleDirs(
      final Path workspaceRoot, final Map<String, List<String>> otherLanguages) {
    final var byLanguage = new EnumMap<OtherLanguage, List<Path>>(OtherLanguage.class);
    for (final Map.Entry<String, List<String>> module : otherLanguages.entrySet()) {
      for (final String key : module.getValue()) {
        OtherLanguage.ofKey(key)
            .ifPresent(
                language ->
                    byLanguage
                        .computeIfAbsent(language, unused -> new ArrayList<>())
                        .add(workspaceRoot.resolve(module.getKey()).normalize()));
      }
    }

    return byLanguage.entrySet().stream()
        .collect(
            Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
  }

  public Optional<String> jdkSourcesNote() {
    return Optional.ofNullable(jdkSourcesNote);
  }

  public Optional<String> loadProblem() {
    return Optional.ofNullable(loadProblem);
  }

  public boolean missing() {
    return missing;
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

  public Set<Path> sourcelessShardPaths() {
    return sourcelessShardPaths;
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

  public Map<String, List<Path>> runnerClasspaths() {
    return runnerClasspaths;
  }

  // The dependency jars and the JDK the workspace compiles against, with their sources.
  public LibrarySources libraries() {
    return libraries;
  }
}
