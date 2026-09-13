package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.languages.java.jpms.LocationManager;
import org.codehaus.plexus.languages.java.jpms.ResolvePathsRequest;
import org.codehaus.plexus.languages.java.jpms.ResolvePathsResult;

/**
 * Derives the runtime launch shape for a {@code main} run and writes it to {@code
 * .lathe/<moduleRel>/main-launch.json}. Unlike {@code test-launch.json}, no {@code main} launch
 * happens during a build to ride, so the template is derived Maven-side: runtime-scope membership
 * from the project's resolved artifacts, and module-path/class-path placement from {@code
 * plexus-java} — the same library Surefire uses, so the derived placement matches the captured test
 * placement by construction. A dependency on a reactor sibling is placed by that sibling's compiled
 * output rather than a repository jar, so the run uses fresh classes and works even when the
 * sibling is neither installed nor packaged. The template is main-class-agnostic; the concrete
 * class is appended by the server at launch time.
 */
final class MainLaunchWriter {

  private static final String POM_PACKAGING = "pom";

  private final LocationManager locationManager;
  private final Map<String, String> reactorOutputByGa;
  private final Log log;

  MainLaunchWriter(
      final LocationManager locationManager,
      final Map<String, String> reactorOutputByGa,
      final Log log) {
    this.locationManager = locationManager;
    this.reactorOutputByGa = Map.copyOf(reactorOutputByGa);
    this.log = log;
  }

  void write(final Path workspaceRoot, final MavenProject project) {
    if (POM_PACKAGING.equals(project.getPackaging())) {
      return;
    }

    final String moduleRel = ReactorProjects.moduleRel(workspaceRoot, project);
    // The main run's working directory is the module basedir (relative to the workspace root),
    // matching where Maven/exec would run it — moduleRel is exactly that.
    persist(workspaceRoot, moduleRel, deriveLaunch(project, moduleRel));
  }

  private MainLaunchData deriveLaunch(final MavenProject project, final String workingDir) {
    final List<String> runtimeElements = runtimeClasspath(project);
    final String javaHome = System.getProperty("java.home");
    final Path moduleInfo = moduleInfoSource(project);
    if (moduleInfo == null) {
      return new MainLaunchData(
          LatheLayout.MAIN_LAUNCH_SCHEMA_VERSION,
          LaunchMode.CLASSPATH,
          javaHome,
          null,
          List.of(),
          runtimeElements,
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          workingDir);
    }

    // plexus-java partitions dependencies only; the launched module's own output is never in that
    // input, so it must lead the module path or `-m <module>/<Main>` cannot resolve the main class.
    final String outputDir = project.getBuild().getOutputDirectory();
    final List<String> dependencies =
        runtimeElements.stream().filter(entry -> !samePath(entry, outputDir)).toList();
    final ResolvePathsResult<String> placement = resolvePlacement(dependencies, moduleInfo);
    final List<String> modulePath =
        Stream.concat(Stream.of(outputDir), placement.getModulepathElements().keySet().stream())
            .toList();
    return new MainLaunchData(
        LatheLayout.MAIN_LAUNCH_SCHEMA_VERSION,
        LaunchMode.MODULE,
        javaHome,
        placement.getMainModuleDescriptor().name(),
        modulePath,
        List.copyOf(placement.getClasspathElements()),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        workingDir);
  }

  private static boolean samePath(final String entry, final String other) {
    return Path.of(entry).normalize().equals(Path.of(other).normalize());
  }

  private ResolvePathsResult<String> resolvePlacement(
      final List<String> pathElements, final Path moduleInfo) {
    final ResolvePathsRequest<String> request =
        ResolvePathsRequest.ofStrings(pathElements).setMainModuleDescriptor(moduleInfo.toString());
    try {
      return locationManager.resolvePaths(request);
    } catch (final IOException e) {
      throw new SyncException(
          "lathe:sync failed to resolve module placement for %s".formatted(moduleInfo), e);
    }
  }

  private List<String> runtimeClasspath(final MavenProject project) {
    final Stream<String> output =
        Optional.ofNullable(project.getBuild().getOutputDirectory()).stream();
    final Stream<String> dependencies =
        project.getArtifacts().stream()
            .filter(MainLaunchWriter::isRuntimeClasspathArtifact)
            .map(this::runtimeElement)
            .filter(Objects::nonNull);
    return Stream.concat(output, dependencies).distinct().toList();
  }

  private static boolean isRuntimeClasspathArtifact(final Artifact artifact) {
    return artifact.getArtifactHandler().isAddedToClasspath()
        && (Artifact.SCOPE_COMPILE.equals(artifact.getScope())
            || Artifact.SCOPE_RUNTIME.equals(artifact.getScope()));
  }

  // A reactor sibling resolves to its fresh compiled output; the project's resolved artifacts drop
  // it whenever it is not installed or packaged (no artifact file), which is the norm for a plain
  // `mvn test` inner loop. External deps keep their resolved artifact file.
  private String runtimeElement(final Artifact artifact) {
    final String reactorOutput = reactorOutputByGa.get(ReactorProjects.ga(artifact));
    if (reactorOutput != null) {
      return reactorOutput;
    }

    return artifact.getFile() != null ? artifact.getFile().getPath() : null;
  }

  private static Path moduleInfoSource(final MavenProject project) {
    return project.getCompileSourceRoots().stream()
        .map(root -> Path.of(root, LatheLayout.MODULE_INFO_JAVA))
        .filter(Files::exists)
        .findFirst()
        .orElse(null);
  }

  private void persist(
      final Path workspaceRoot, final String moduleRel, final MainLaunchData data) {
    final Path moduleDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleRel);
    final Path launchFile = moduleDir.resolve(LatheLayout.MAIN_LAUNCH_FILE);
    final String newContent = Json.toJson(data);
    try {
      Files.createDirectories(moduleDir);
      if (Files.exists(launchFile)
          && newContent.equals(Files.readString(launchFile, StandardCharsets.UTF_8))) {
        return;
      }

      FileUtil.writeAtomically(moduleDir, launchFile, newContent, false);
      log.debug("[sync] main-launch %s written".formatted(moduleRel));
    } catch (final IOException e) {
      throw new SyncException(
          "lathe:sync failed to write main-launch template for %s".formatted(moduleRel), e);
    }
  }
}
