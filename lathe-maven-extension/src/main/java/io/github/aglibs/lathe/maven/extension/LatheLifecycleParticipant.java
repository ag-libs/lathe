package io.github.aglibs.lathe.maven.extension;

import io.github.aglibs.lathe.core.BuildLock;
import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.BuildSuccess;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Injects the Lathe build wiring into the reactor model (LatheModelInjector) and holds the build
// lock (.lathe/lathe.lock) for the whole session. Registered in META-INF/plexus/components.xml, not
// by Sisu's class index: Maven before 3.9 ships an ASM that cannot read Java 17 class files and
// would skip the participant without a word.
public final class LatheLifecycleParticipant extends AbstractMavenLifecycleParticipant {

  private static final Logger LOG = LoggerFactory.getLogger(LatheLifecycleParticipant.class);

  // Single-session-per-JVM state: a Maven session takes the lock in afterProjectsRead and releases
  // it in afterSessionEnd; null when this build holds none. An in-JVM forked build is the only
  // overlap, and the staleness TTL is its backstop.
  private BuildLock buildLock;

  @Override
  public void afterProjectsRead(final MavenSession session) throws MavenExecutionException {
    final var latheDir = reactorRoot(session).resolve(LatheLayout.LATHE_DIR);
    if (LatheFlags.isDisabled() || isPomOptOut(session.getTopLevelProject(), latheDir)) {
      return;
    }

    final List<String> unmet =
        LatheRequirements.unmet(
            session.getSystemProperties().getProperty(LatheLayout.Maven.MAVEN_VERSION_PROPERTY),
            session.getProjects());
    if (!unmet.isEmpty()) {
      throw new MavenExecutionException(
          "[lathe] This build is older than Lathe supports:%n  - %s%n  To build without Lathe, add -D%s=true"
              .formatted(String.join("%n  - ".formatted(), unmet), LatheFlags.DISABLED),
          session.getTopLevelProject().getFile());
    }

    final var injector = new LatheModelInjector(ExtensionProps.version());
    session.getProjects().forEach(injector::injectProject);
    injector.injectRootExecutions(session.getTopLevelProject());
    startReactorLock(latheDir);
  }

  // The lock is released whatever the top-up throws: in an mvnd daemon a skipped release leaves the
  // heartbeat holding it, so every server takes the workspace for one being built.
  @Override
  public void afterSessionEnd(final MavenSession session) {
    try {
      if (buildLock != null) {
        mirrorLateOutputs(buildLock.latheDir().getParent());
        recordBuilt(buildLock.latheDir().getParent(), succeeded(session));
      }
    } finally {
      stopReactorLock();
    }
  }

  // Each compile copies its output into the mirror, but a build adds files after compiling: Helidon
  // writes its annotation processor's META-INF/services entry in process-classes, and without it
  // the server's javac runs no processor. Adds what a mirror lacks; never a class, which the server
  // may have rewritten since. Best-effort: a failure costs that entry, never the build.
  static void mirrorLateOutputs(final Path workspaceRoot) {
    try {
      final WorkspaceManifestData manifest =
          Json.read(
              workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON),
              WorkspaceManifestData.class);
      ReactorRewrite.of(workspaceRoot, manifest.outputs())
          .mirrors()
          .forEach(LatheLifecycleParticipant::addLateFiles);
    } catch (final IOException | RuntimeException e) {
      // RuntimeException too: a manifest another Lathe version wrote fails to parse, and this
      // best-effort step must never fail the build.
      LOG.debug("[mirror] late build output not copied under {}", workspaceRoot, e);
    }
  }

  private static List<Path> succeeded(final MavenSession session) {
    return session.getProjects().stream()
        .filter(project -> session.getResult().getBuildSummary(project) instanceof BuildSuccess)
        .map(project -> project.getBasedir().toPath())
        .toList();
  }

  // A module Maven found up to date compiled nothing, so its stamps lack what the build compiled
  // (a fresh .lathe, or one an older Lathe wrote) and a touch or a revert there reads as a change.
  // For a module the build completed, its sources hold that content now. Best-effort, like the
  // late outputs.
  static void recordBuilt(final Path workspaceRoot, final List<Path> moduleBasedirs) {
    final Path latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    moduleBasedirs.forEach(
        basedir -> recordBuiltIn(latheDir.resolve(workspaceRoot.relativize(basedir))));
  }

  private static void recordBuiltIn(final Path moduleDir) {
    if (!Files.isDirectory(moduleDir)) {
      return;
    }

    try (final Stream<Path> files = Files.list(moduleDir)) {
      files
          .filter(LatheLayout::isParamsFile)
          .forEach(
              params ->
                  IOUtil.unchecked(
                      () ->
                          CompiledStamps.recordBuiltIfMissing(
                              moduleDir, Json.read(params, ModuleConfigData.class))));
    } catch (final IOException | RuntimeException e) {
      LOG.debug("[stamps] built content not recorded under {}", moduleDir, e);
    }
  }

  // Only into a mirror the compile created: a module never compiled has no mirror to complete.
  private static void addLateFiles(final Path output, final Path mirror) {
    if (Files.isDirectory(mirror)) {
      IOUtil.unchecked(
          () -> FileUtil.copyMissing(output, mirror, file -> !FileUtil.isClassFile(file)));
    }
  }

  private static Path reactorRoot(final MavenSession session) {
    return session.getRequest().getMultiModuleProjectDirectory().toPath();
  }

  // Team-wide opt-out committed in the reactor POM (<lathe.disabled>true</...>), read from the
  // resolved model since mvnd cannot be trusted with System.getenv. A developer opts back in by
  // creating an empty .lathe/ at the reactor root; a -Dlathe.disabled / CI kill stays absolute via
  // isDisabled().
  static boolean isPomOptOut(final MavenProject topLevelProject, final Path latheDir) {
    return "true".equals(topLevelProject.getProperties().getProperty(LatheFlags.DISABLED))
        && !Files.isDirectory(latheDir);
  }

  void startReactorLock(final Path latheDir) {
    buildLock = BuildLock.hold(latheDir).orElse(null);
  }

  void stopReactorLock() {
    if (buildLock != null) {
      buildLock.close();
      buildLock = null;
    }
  }
}
