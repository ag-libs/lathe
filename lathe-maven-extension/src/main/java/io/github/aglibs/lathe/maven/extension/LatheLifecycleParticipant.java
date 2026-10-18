package io.github.aglibs.lathe.maven.extension;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

/**
 * Injects the Lathe build wiring into the reactor model via {@link LatheModelInjector}, and holds a
 * heartbeated {@code .lathe/lathe.lock} for the whole session so the language server suppresses its
 * sync prompt for the entire build, not just the per-module compile windows.
 */
// Registered in META-INF/plexus/components.xml, not by Sisu's class index: Maven before 3.9 ships
// an
// ASM that cannot read Java 17 class files and would skip the participant without a word.
public final class LatheLifecycleParticipant extends AbstractMavenLifecycleParticipant {

  // Refresh well inside LatheLock's staleness window so a live build never looks crashed, while a
  // real crash (heartbeat thread dies with the JVM) self-heals within that window.
  private static final long HEARTBEAT_MS = 30_000;

  // Single-session-per-JVM state: a Maven session acquires in afterProjectsRead and releases in
  // afterSessionEnd. An in-JVM forked build is the only overlap, and the staleness TTL is its
  // backstop.
  private ScheduledExecutorService heartbeat;
  private Path reactorLockDir;

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

  @Override
  public void afterSessionEnd(final MavenSession session) {
    stopReactorLock();
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

  // Skipped on a first-ever build (no .lathe/ yet): there is no mirror to protect, and creating it
  // early would flip the server's "configured" detection.
  void startReactorLock(final Path latheDir) {
    if (!Files.isDirectory(latheDir) || !touchLock(latheDir)) {
      return;
    }

    reactorLockDir = latheDir;
    heartbeat = Executors.newSingleThreadScheduledExecutor(LatheLifecycleParticipant::daemon);
    heartbeat.scheduleAtFixedRate(
        () -> touchLock(latheDir), HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
  }

  void stopReactorLock() {
    if (heartbeat != null) {
      heartbeat.shutdownNow();
      heartbeat = null;
    }

    if (reactorLockDir == null) {
      return;
    }

    releaseLock(reactorLockDir);
    reactorLockDir = null;
  }

  // Both the initial acquire and each heartbeat rewrite the lock file, bumping its mtime (and
  // recreating it if something deleted it mid-build). Best-effort: a failed lock only costs a
  // spurious prompt, never a failed build.
  private static boolean touchLock(final Path latheDir) {
    try {
      LatheLock.acquire(LatheLayout.reactorLockFile(latheDir));
      return true;
    } catch (final IOException e) {
      return false;
    }
  }

  private static void releaseLock(final Path latheDir) {
    try {
      LatheLock.release(LatheLayout.reactorLockFile(latheDir));
    } catch (final IOException e) {
      // Best-effort; the staleness TTL reclaims a leaked lock.
    }
  }

  private static Thread daemon(final Runnable r) {
    final var thread = new Thread(r, "lathe-reactor-lock");
    thread.setDaemon(true);
    return thread;
  }
}
