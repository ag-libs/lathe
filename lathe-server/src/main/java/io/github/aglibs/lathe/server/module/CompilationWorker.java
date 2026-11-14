package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.analysis.JavaSourceCompiler;
import io.github.aglibs.lathe.analysis.SourceAnalysisSession;
import io.github.aglibs.lathe.analysis.TransientSource;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

/** Called from the server event loop; executes all compilation work on its own module thread. */
public final class CompilationWorker {

  private static final Logger LOG = Logger.getLogger(CompilationWorker.class.getName());
  // At most this many open documents share one analysis task: a refresh chunk, or a file plus the
  // unsaved siblings that join it.
  public static final int ANALYSIS_BATCH_MAX = 50;
  private static final int FATAL_EXIT_STATUS = 1;

  private final ExecutorService executor;
  // Where results are completed: the server's event loop, so what a caller chains on a result runs
  // there and may touch session state. Two exceptions: a request the closed worker refuses fails
  // on the caller's thread, and closeAsync completes on the worker thread, as the session's close
  // waits for it on the loop and a completion queued there would never run.
  private final Executor delivery;
  private final Supplier<SourceAnalysisSession> contextFactory;
  private final IntConsumer processTerminator;
  private SourceAnalysisSession context;
  private boolean closed;
  private CompletableFuture<Void> closeFuture;
  // Event-loop side: whether a session exists, or will once the queued requests run, and when the
  // latest request was submitted.
  private boolean sessionLive;
  private Stopwatch lastRequest = Stopwatch.start();
  // Requests ever submitted, counted on the event loop: an idle wait compares it before and after
  // draining, so work a delivered result started is waited for too.
  private long submitted;

  static CompilationWorker module(
      final ModuleSourceConfig config,
      final CompilationAdmission compilationAdmission,
      final Executor delivery) {
    return new CompilationWorker(
        "lathe-module-%s-%s".formatted(config.moduleDir().getFileName(), config.sourceTree()),
        () -> new SourceAnalysisSession(new ModuleSourceCompiler(config, compilationAdmission)),
        delivery);
  }

  static CompilationWorker external(
      final WorkspaceManifest manifest,
      final CompilationAdmission compilationAdmission,
      final Executor delivery) {
    return new CompilationWorker(
        "lathe-external",
        () ->
            new SourceAnalysisSession(
                new ExternalCompiler(manifest.libraries(), compilationAdmission)),
        delivery);
  }

  CompilationWorker(
      final String name,
      final Supplier<SourceAnalysisSession> contextFactory,
      final Executor delivery) {
    this(name, contextFactory, delivery, status -> Runtime.getRuntime().halt(status));
  }

  CompilationWorker(
      final String name,
      final Supplier<SourceAnalysisSession> contextFactory,
      final Executor delivery,
      final IntConsumer processTerminator) {
    this.contextFactory = contextFactory;
    this.delivery = delivery;
    this.processTerminator = processTerminator;
    this.executor =
        Executors.newSingleThreadExecutor(
            r -> {
              final var t = new Thread(r, name);
              t.setDaemon(true);
              return t;
            });
  }

  CompletableFuture<Void> closeAsync() {
    if (closeFuture != null) {
      return closeFuture;
    }

    closed = true;
    closeFuture = new CompletableFuture<>();
    executor.execute(
        () -> {
          try {
            closeSession();
            closeFuture.complete(null);
          } catch (final Throwable t) {
            LOG.log(Level.SEVERE, t, () -> "[module] failed to close");
            closeFuture.completeExceptionally(t);
            IOUtil.rethrowIfError(t);
          } finally {
            executor.shutdown();
          }
        });
    return closeFuture;
  }

  void close() {
    closeAsync().join();
  }

  // A session holds only what its next request rebuilds: the open files' analyses and jar indexes
  // that a large reactor-wide request leaves behind in every module it touched. It is closed after
  // the requests already queued, so a busy module's session goes once its work is done.
  void releaseSession() {
    if (closed || !sessionLive) {
      return;
    }

    sessionLive = false;
    executor.execute(this::closeSession);
  }

  // On the worker thread. Forgotten first, so a session whose close fails is not used again.
  private void closeSession() {
    if (context != null) {
      final SourceAnalysisSession closing = context;
      context = null;
      closing.close();
    }
  }

  // Has a session, and no request for at least ms.
  boolean idleFor(final long ms) {
    return sessionLive && lastRequest.elapsedMs() >= ms;
  }

  long submitted() {
    return submitted;
  }

  // Completes through the delivery executor once every request queued before it has run and its
  // result has been delivered: the worker thread delivers in order, so this comes after them.
  CompletableFuture<Void> drained() {
    final var drained = new CompletableFuture<Void>();
    if (closed) {
      drained.complete(null);
      return drained;
    }

    try {
      executor.execute(() -> delivery.execute(() -> drained.complete(null)));
    } catch (final RejectedExecutionException e) {
      drained.complete(null);
    }

    return drained;
  }

  public CompletableFuture<CompileResponse> compile(final CompileRequest request) {
    return compile(request, List.of());
  }

  // Analyzes the file together with the given unsaved siblings of its tree (most recently edited
  // first), so it sees their unsaved declarations: the first that parsed cleanly in their last
  // analysis of that same content join, up to the cap. Answers for the file alone.
  public CompletableFuture<CompileResponse> compile(
      final CompileRequest request, final List<CompileRequest> siblings) {
    return submit(
        ctx -> {
          final List<CompileRequest> joining =
              siblings.stream()
                  .filter(sibling -> ctx.parsedCleanly(sibling.uri(), sibling.content()))
                  .limit(ANALYSIS_BATCH_MAX - 1)
                  .toList();
          if (joining.isEmpty()) {
            return response(
                request,
                ctx.compile(request.uri(), request.content(), request.version(), request.mode()));
          }

          final Map<String, List<Diagnostic>> diagnostics =
              analyzeTogether(ctx, Stream.concat(Stream.of(request), joining.stream()).toList());
          return response(request, diagnostics.get(request.uri()));
        });
  }

  // Several open documents of this tree analyzed in one task, each answered as its own compile.
  public CompletableFuture<List<CompileResponse>> compileOpenBatch(
      final List<CompileRequest> requests) {
    return submit(
        ctx -> {
          final Map<String, List<Diagnostic>> diagnostics = analyzeTogether(ctx, requests);
          return requests.stream()
              .map(request -> response(request, diagnostics.get(request.uri())))
              .toList();
        });
  }

  private static CompileResponse response(
      final CompileRequest request, final List<Diagnostic> diagnostics) {
    return new CompileResponse(request.uri(), request.generation(), diagnostics);
  }

  private static Map<String, List<Diagnostic>> analyzeTogether(
      final SourceAnalysisSession ctx, final List<CompileRequest> requests) {
    return ctx.compileOpenBatch(
        requests.stream()
            .map(request -> new TransientSource(request.uri(), request.content()))
            .toList(),
        requests.stream()
            .collect(Collectors.toUnmodifiableMap(CompileRequest::uri, CompileRequest::version)),
        () -> {});
  }

  public void dropFromCache(final String uri) {
    if (closed) {
      return;
    }

    executor.execute(
        () -> {
          if (context != null) {
            context.dropFromCache(uri);
          }
        });
  }

  // Runs fn on this module's thread against its analysis session; the result is delivered on the
  // event loop.
  public <T> CompletableFuture<T> submit(final Function<SourceAnalysisSession, T> fn) {
    return submit(fn, () -> {});
  }

  // As submit(fn), skipped once cancelChecker reports the request cancelled.
  public <T> CompletableFuture<T> submit(
      final Function<SourceAnalysisSession, T> fn, final CancelChecker cancelChecker) {
    final var future = new CompletableFuture<T>();
    if (closed) {
      future.completeExceptionally(new IllegalStateException("module worker is closed"));
      return future;
    }

    sessionLive = true;
    lastRequest = Stopwatch.start();
    submitted++;
    try {
      executor.execute(
          () -> {
            final var timer = Stopwatch.start();
            try {
              cancelChecker.checkCanceled();
              if (context == null) {
                context = contextFactory.get();
              }

              final T result = fn.apply(context);
              delivery.execute(() -> future.complete(result));
            } catch (final Throwable t) {
              final Error fatal = JavaSourceCompiler.fatalErrorCause(t);
              if (fatal != null) {
                try {
                  LOG.log(
                      Level.SEVERE,
                      fatal,
                      () ->
                          "[compiler] process fatal-error fatal %dms".formatted(timer.elapsedMs()));
                } finally {
                  processTerminator.accept(FATAL_EXIT_STATUS);
                }
              }
              delivery.execute(() -> future.completeExceptionally(t));
              IOUtil.rethrowIfError(t);
            }
          });
    } catch (final RejectedExecutionException e) {
      future.completeExceptionally(e);
    }
    return future;
  }
}
