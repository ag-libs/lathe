package io.github.aglibs.lathe.server;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.lsp4j.WorkDoneProgressCancelParams;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// The way onto the event loop for everything outside it: the LSP services and the in-process
// LatheEngine (MCP). It owns the loop, the workspace session the loop runs, and the progress
// reporter; every call reaches the session on the loop.
final class SessionHost {

  private static final long DEFAULT_DEBOUNCE_MS = 500;

  private final ServerEventLoop eventLoop = new ServerEventLoop();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final long debounceMs;
  private ProgressReporter progressReporter;
  // Created, read and written only on the event loop: reach it through a lambda the loop runs
  // (eventLoop.submit(() -> session.x())), never a method reference (session::x), which reads the
  // field on the caller's thread, where it may still be null.
  private WorkspaceSession session;

  SessionHost() {
    this(DEFAULT_DEBOUNCE_MS);
  }

  SessionHost(final long debounceMs) {
    this.debounceMs = debounceMs;
  }

  void connect(final LatheLanguageClient client) {
    progressReporter = new ProgressReporter(client);
    eventLoop.execute(
        () -> session = new WorkspaceSession(client, progressReporter, eventLoop, debounceMs));
  }

  // Queued after connect, so the session exists by then.
  void initialize(final Path workspaceRoot) {
    execute(session -> session.initialize(workspaceRoot));
  }

  void setWorkDoneProgressSupported(final boolean supported) {
    progressReporter.setSupported(supported);
  }

  void cancelProgress(final WorkDoneProgressCancelParams params) {
    progressReporter.cancel(params.getToken());
  }

  // Set before initialize, on the loop like all session state.
  void setDocumentsFollowDisk(final boolean follow) {
    execute(session -> session.setDocumentsFollowDisk(follow));
  }

  // Whether a user answers prompts; without one, a due sync is only reported.
  void setInteractive(final boolean interactive) {
    execute(session -> session.setInteractive(interactive));
  }

  // A server whose every query scans first (MCP) stops scanning after this long without one; zero
  // never stops.
  void setIdleScanPause(final long ms) {
    execute(session -> session.setIdleScanPause(ms));
  }

  void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }

    // On the loop, as the last thing it runs: connect() assigns the session there. The format
    // service comes back out, so its thread can be waited for off the loop.
    final FormatService formats =
        eventLoop.close(
            () -> {
              if (session == null) {
                return null;
              }

              session.close();
              return session.formats();
            });
    if (formats != null) {
      formats.awaitStopped();
    }
  }

  // Session work with no answer, in order with the rest: a document's lifecycle event, a setting.
  void execute(final Consumer<WorkspaceSession> action) {
    eventLoop.execute(() -> action.accept(session));
  }

  // Reads session state on the event loop.
  <T> CompletableFuture<T> onLoop(final SessionRead<T> read) {
    return eventLoop.submit(() -> read.apply(session));
  }

  // A read that may fail, as a Callable may: the failure completes the future.
  interface SessionRead<T> {
    T apply(WorkspaceSession session) throws Exception;
  }

  // Starts session work on the event loop that completes later; what the work chains on worker
  // results runs on the loop too.
  <T> CompletableFuture<T> onLoopFuture(
      final Function<WorkspaceSession, CompletableFuture<T>> start) {
    return eventLoop.submitCompose(() -> start.apply(session));
  }

  // Session work that reports progress (under the client's token, if it sent one) and stops at its
  // next cancel check once the returned future is cancelled. The progress always ends, however the
  // work completes, so no request can leak a $/progress.
  @FunctionalInterface
  interface LoopWork<T> {
    CompletableFuture<? extends T> start(
        WorkspaceSession session, CancelChecker cancelChecker, ProgressReporter.Task progress);
  }

  <T> CompletableFuture<T> onLoopCompose(
      final Either<String, Integer> token, final LoopWork<T> work) {
    final var response = new CompletableFuture<T>();
    final CancelChecker cancelChecker = new CompletableFutures.FutureCancelChecker(response);
    final ProgressReporter.Task progress = progressReporter.open(token, response);
    eventLoop
        .submitCompose(
            () -> work.start(session, cancelChecker, progress).<T>thenApply(result -> result))
        .whenComplete(
            (result, failure) -> {
              progress.finish(failure);
              if (failure == null) {
                response.complete(result);
              } else {
                response.completeExceptionally(failure);
              }
            });
    return response;
  }
}
