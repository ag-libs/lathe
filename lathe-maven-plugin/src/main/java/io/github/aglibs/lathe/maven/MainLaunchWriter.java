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
import java.util.stream.Stream;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.languages.java.jpms.LocationManager;
import org.codehaus.plexus.languages.java.jpms.ResolvePathsRequest;
import org.codehaus.plexus.languages.java.jpms.ResolvePathsResult;

/**
 * Derives the runtime launch shape for a {@code main} run and writes it to {@code
 * .lathe/<moduleRel>/main-launch.json}. Unlike {@code test-launch.json}, no {@code main} launch
 * happens during a build to ride, so the template is derived Maven-side: runtime-scope membership
 * from {@link MavenProject#getRuntimeClasspathElements()}, and module-path/class-path placement
 * from {@code plexus-java} — the same library Surefire uses, so the derived placement matches the
 * captured test placement by construction. The template is main-class-agnostic; the concrete class
 * is appended by the server at launch time.
 */
final class MainLaunchWriter {

  private static final String POM_PACKAGING = "pom";

  private final LocationManager locationManager;
  private final Log log;

  MainLaunchWriter(final LocationManager locationManager, final Log log) {
    this.locationManager = locationManager;
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

  private static List<String> runtimeClasspath(final MavenProject project) {
    try {
      return List.copyOf(project.getRuntimeClasspathElements());
    } catch (final DependencyResolutionRequiredException e) {
      throw new SyncException(
          "lathe:sync failed to resolve runtime classpath for %s"
              .formatted(project.getArtifactId()),
          e);
    }
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
