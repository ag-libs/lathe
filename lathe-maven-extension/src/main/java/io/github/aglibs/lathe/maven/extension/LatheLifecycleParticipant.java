package io.github.aglibs.lathe.maven.extension;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;

/**
 * Injects the Lathe build wiring into the reactor model via {@link LatheModelInjector}, and holds a
 * heartbeated {@code .lathe/lathe.lock} for the whole session so the language server suppresses its
 * sync prompt for the entire build, not just the per-module compile windows.
 */
@Named("lathe")
@Singleton
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
  public void afterProjectsRead(final MavenSession session) {
    if (LatheFlags.isDisabled()) {
      return;
    }

    final var injector = new LatheModelInjector(ExtensionProps.version());
    session.getProjects().forEach(injector::injectProject);
    injector.injectRootExecutions(session.getTopLevelProject());
    startReactorLock(reactorRoot(session).resolve(LatheLayout.LATHE_DIR));
  }

  @Override
  public void afterSessionEnd(final MavenSession session) {
    stopReactorLock();
  }

  private static Path reactorRoot(final MavenSession session) {
    return session.getRequest().getMultiModuleProjectDirectory().toPath();
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
      LatheLock.acquire(latheDir);
      return true;
    } catch (final IOException e) {
      return false;
    }
  }

  private static void releaseLock(final Path latheDir) {
    try {
      LatheLock.release(latheDir);
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
