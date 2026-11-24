package io.github.aglibs.lathe.maven.extension;

import io.github.aglibs.lathe.core.BuildLock;
import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import io.github.aglibs.lathe.core.schema.BuildFileData;
import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.BuildSuccess;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Injects the Lathe build wiring into the reactor model (LatheModelInjector) and holds the build
// lock (.lathe/lathe.lock) for the whole session. Registered in META-INF/plexus/components.xml, not
// by Sisu's class index: Maven before 3.9 ships an ASM that cannot read Java 17 class files and
// would skip the participant without a word.
public final class LatheLifecycleParticipant extends AbstractMavenLifecycleParticipant {

  // The system property Maven sets to its own version.
  private static final String MAVEN_VERSION_PROPERTY = "maven.version";

  // The user property m2e (Eclipse, jdtls) sets on every request of its embedded Maven. m2e cannot
  // configure the injected compilerId, so jdtls would get no Java build path and answer nothing.
  static final String M2E_VERSION_PROPERTY = "m2e.version";

  private static final Logger LOG = LoggerFactory.getLogger(LatheLifecycleParticipant.class);

  // Single-session-per-JVM state: a Maven session takes the lock in afterProjectsRead and releases
  // it in afterSessionEnd; null when this build holds none. An in-JVM forked build is the only
  // overlap, and the staleness TTL is its backstop.
  private BuildLock buildLock;
  // The reactor root and what Maven read of its POMs, for the session end; null and empty when
  // Lathe is off for this build. Set even without a lock: the first sync creates .lathe/.
  private Path workspaceRoot;
  private Map<Path, ContentFingerprint> pomsAtStart = Map.of();

  @Override
  public void afterProjectsRead(final MavenSession session) throws MavenExecutionException {
    if (insideM2e(session)) {
      LOG.info("[lathe] Off: inside m2e (Eclipse, jdtls); the build is left as it is.");
      return;
    }

    final Optional<String> off =
        LatheFlags.disabledNotice(
            System.getProperty(LatheFlags.DISABLED),
            System.getenv(LatheFlags.CI_ENV) != null,
            "-D");
    if (off.isPresent()) {
      LOG.info(off.get());
      return;
    }

    final var latheDir = reactorRoot(session).resolve(LatheLayout.LATHE_DIR);
    if (isPomOptOut(session.getTopLevelProject(), latheDir)) {
      return;
    }

    final List<String> unmet =
        LatheRequirements.unmet(
            session.getSystemProperties().getProperty(MAVEN_VERSION_PROPERTY),
            session.getProjects());
    if (!unmet.isEmpty()) {
      throw new MavenExecutionException(
          LatheFlags.unsupportedBuild(unmet, "-D"), session.getTopLevelProject().getFile());
    }

    final var injector = new LatheModelInjector(ExtensionProps.version());
    session.getProjects().forEach(injector::injectProject);
    // A -Dtest the user passed is theirs to keep.
    if (LatheFlags.isCaptureOnly()
        && !session.getUserProperties().containsKey(LatheModelInjector.SUREFIRE_TEST_PROPERTY)) {
      session.getProjects().forEach(injector::injectCaptureClass);
    }

    injector.injectRootExecutions(session.getTopLevelProject());
    readReactor(reactorRoot(session), session.getProjects());
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

      if (workspaceRoot != null) {
        refreshBuildFiles(workspaceRoot, session.getProjects(), pomsAtStart);
      }
    } finally {
      workspaceRoot = null;
      pomsAtStart = Map.of();
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
      // RuntimeException too: a copy fails unchecked inside the stream, and this best-effort step
      // must never fail the build.
      LOG.debug("[mirror] late build output not copied under {}", workspaceRoot, e);
    }
  }

  // A build can rewrite a POM after the sync fingerprinted it, as sortpom does when each module
  // builds, and the server would then ask for a sync that changes nothing. A POM whose model is
  // still the one Maven read (only its order, whitespace or comments moved) is recorded as it is
  // now; one whose model changed, by hand or by a plugin, keeps the synced fingerprint and still
  // asks for a sync. Best-effort, like the late outputs.
  static void refreshBuildFiles(
      final Path workspaceRoot,
      final List<MavenProject> projects,
      final Map<Path, ContentFingerprint> readAtStart) {
    final var t = Stopwatch.start();
    final Path latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    final Path manifestPath = latheDir.resolve(LatheLayout.WORKSPACE_JSON);
    if (!Files.isRegularFile(manifestPath)) {
      return;
    }

    try {
      final WorkspaceManifestData manifest = Json.read(manifestPath, WorkspaceManifestData.class);
      final Map<Path, Model> readModels = readModels(projects);
      final List<BuildFileData> current =
          manifest.buildFiles().stream()
              .map(file -> reformatted(workspaceRoot, file, readModels, readAtStart))
              .toList();
      if (current.equals(manifest.buildFiles())) {
        return;
      }

      FileUtil.writeIfChanged(
          latheDir, manifestPath, Json.toJson(manifest.withBuildFiles(current)), false);
      LOG.info(
          "[sync] {} build-file fingerprints updated for reformatted POMs {}ms",
          manifestPath,
          t.elapsedMs());
    } catch (final IOException | RuntimeException e) {
      LOG.debug("[sync] {} build-file fingerprints not updated", manifestPath, e);
    }
  }

  // The model each reactor POM had when Maven read it, before the build could rewrite one.
  private static Map<Path, Model> readModels(final List<MavenProject> projects) {
    return projects.stream()
        .filter(project -> project.getFile() != null && project.getOriginalModel() != null)
        .collect(
            Collectors.toUnmodifiableMap(
                project -> pomPath(project.getFile().toPath()),
                MavenProject::getOriginalModel,
                (first, second) -> first));
  }

  // Only a POM the sync recorded as Maven read it: an older sync's fingerprint says nothing about
  // what this build saw.
  private static BuildFileData reformatted(
      final Path workspaceRoot,
      final BuildFileData file,
      final Map<Path, Model> readModels,
      final Map<Path, ContentFingerprint> readAtStart) {
    final Path pom = pomPath(workspaceRoot.resolve(file.path()));
    final Model read = readModels.get(pom);
    if (file.fingerprint() == null
        || read == null
        || !file.fingerprint().equals(readAtStart.get(pom))
        || !Files.isRegularFile(pom)) {
      return file;
    }

    final ContentFingerprint now = IOUtil.unchecked(() -> FileUtil.fingerprint(pom));
    if (now.equals(file.fingerprint()) || !sameModel(read, pom)) {
      return file;
    }

    return new BuildFileData(file.path(), now);
  }

  // The writer orders the elements and drops whitespace and comments, so two POMs it writes alike
  // hold the same model. A POM that no longer parses is not the same.
  private static boolean sameModel(final Model read, final Path pom) {
    try (final var in = Files.newInputStream(pom)) {
      return written(read).equals(written(new MavenXpp3Reader().read(in, false)));
    } catch (final IOException | XmlPullParserException e) {
      LOG.debug("[sync] {} not compared with the model Maven read", pom, e);
      return false;
    }
  }

  private static String written(final Model model) throws IOException {
    final var out = new StringWriter();
    new MavenXpp3Writer().write(out, model);
    return out.toString();
  }

  // What Maven read of each POM, taken before the build runs. A POM not read here keeps the
  // fingerprint the sync gives it.
  private static Map<Path, ContentFingerprint> readFingerprints(final List<MavenProject> projects) {
    final var read = new HashMap<Path, ContentFingerprint>();
    for (final MavenProject project : projects) {
      if (project.getFile() == null) {
        continue;
      }

      final Path pom = pomPath(project.getFile().toPath());
      try {
        read.put(pom, FileUtil.fingerprint(pom));
      } catch (final IOException | RuntimeException e) {
        LOG.debug("[sync] {} not fingerprinted at the build's start", pom, e);
      }
    }

    return Map.copyOf(read);
  }

  private static Path pomPath(final Path file) {
    return file.toAbsolutePath().normalize();
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
    try {
      FileUtil.filesIn(moduleDir, LatheLayout::isParamsFile)
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

  // Where the sync writes .lathe/: the top-level project's directory, not the .mvn-anchored
  // multiModuleProjectDirectory, which can lie above it.
  private static Path reactorRoot(final MavenSession session) {
    return session.getTopLevelProject().getBasedir().toPath();
  }

  // Checked before lathe.disabled: an explicit -Dlathe.disabled=false cannot make m2e configure
  // the injected compiler, so inside m2e Lathe stays off either way.
  static boolean insideM2e(final MavenSession session) {
    return session.getUserProperties().getProperty(M2E_VERSION_PROPERTY) != null;
  }

  // Team-wide opt-out committed in the reactor POM (<lathe.disabled>true</...>), read from the
  // resolved model since mvnd cannot be trusted with System.getenv. A developer opts back in by
  // creating an empty .lathe/ at the reactor root; a -Dlathe.disabled / CI kill stays absolute,
  // checked first in afterProjectsRead.
  static boolean isPomOptOut(final MavenProject topLevelProject, final Path latheDir) {
    return "true".equals(topLevelProject.getProperties().getProperty(LatheFlags.DISABLED))
        && !Files.isDirectory(latheDir);
  }

  void readReactor(final Path root, final List<MavenProject> projects) {
    workspaceRoot = root;
    pomsAtStart = readFingerprints(projects);
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
