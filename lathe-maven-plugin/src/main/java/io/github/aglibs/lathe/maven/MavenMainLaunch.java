package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.install.MainLaunchWriter;
import io.github.aglibs.lathe.install.SyncException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.languages.java.jpms.LocationManager;
import org.codehaus.plexus.languages.java.jpms.ResolvePathsRequest;
import org.codehaus.plexus.languages.java.jpms.ResolvePathsResult;

// A module's main-run launch, derived here since no main run happens during a build: membership
// from the resolved runtime artifacts, placement from plexus-java (as Surefire, so it matches the
// captured tests), and reactor siblings as their compiled output, installed or not.
final class MavenMainLaunch {

  private final LocationManager locationManager;
  private final Map<String, String> reactorOutputByGa;

  MavenMainLaunch(
      final LocationManager locationManager, final Map<String, String> reactorOutputByGa) {
    this.locationManager = locationManager;
    this.reactorOutputByGa = Map.copyOf(reactorOutputByGa);
  }

  void write(final Path workspaceRoot, final MavenProject project) {
    if (LatheLayout.Maven.POM_PACKAGING.equals(project.getPackaging())) {
      return;
    }

    // The main run's working directory is the module basedir, where Maven/exec would run it.
    final String moduleRel = ReactorProjects.moduleRel(workspaceRoot, project);
    final List<String> runtimeElements = runtimeClasspath(project);
    final String javaHome = System.getProperty(LatheLayout.JAVA_HOME_PROPERTY);
    final Path moduleInfo = moduleInfoSource(project);
    if (moduleInfo == null) {
      MainLaunchWriter.writeClasspath(workspaceRoot, moduleRel, javaHome, runtimeElements);
      return;
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
    MainLaunchWriter.writeModule(
        workspaceRoot,
        moduleRel,
        javaHome,
        placement.getMainModuleDescriptor().name(),
        modulePath,
        List.copyOf(placement.getClasspathElements()));
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
            .filter(MavenMainLaunch::isRuntimeClasspathArtifact)
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
}
