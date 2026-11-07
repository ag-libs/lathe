package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.aglibs.lathe.analysis.CompileMode;
import io.github.aglibs.lathe.analysis.ReferenceMatch;
import io.github.aglibs.lathe.analysis.ReferenceTarget;
import io.github.aglibs.lathe.analysis.SearchHits;
import io.github.aglibs.lathe.analysis.SourceAnalysisSession;
import io.github.aglibs.lathe.analysis.TransientSource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CompilationWorkerTest {

  private static final String DELIVERY_THREAD = "delivery-thread";

  private final SourceAnalysisSession context = mock(SourceAnalysisSession.class);
  private final CompilationWorker worker =
      new CompilationWorker("lathe-module-test", () -> context, Runnable::run);

  @AfterEach
  void close() {
    worker.close();
  }

  @Test
  void compile_request_returnsResultWithSnapshotIdentity() {
    final var diagnostic = new Diagnostic();
    final var request =
        new CompileRequest("file:///A.java", "class A {}", 1, 42L, CompileMode.OPEN);
    when(context.compile(request.uri(), request.content(), request.version(), request.mode()))
        .thenReturn(List.of(diagnostic));

    final var result = worker.compile(request).join();

    assertThat(result.uri()).isEqualTo(request.uri());
    assertThat(result.generation()).isEqualTo(request.generation());
    assertThat(result.diagnostics()).containsExactly(diagnostic);
  }

  @Test
  void compile_valueAndFailure_completeOnTheDeliveryExecutor() throws InterruptedException {
    // Deliveries are queued and run only once both callbacks are attached, as on the event loop,
    // where the task that asks attaches its callbacks before the delivery can run.
    final var deliveries = new LinkedBlockingQueue<Runnable>();
    final var delivering =
        new CompilationWorker("lathe-module-delivery", () -> context, deliveries::add);
    final var request = new CompileRequest("file:///A.java", "class A {}", 1, 1L, CompileMode.OPEN);
    final var failing = new CompileRequest("file:///B.java", "class B {}", 1, 2L, CompileMode.OPEN);
    when(context.compile(request.uri(), request.content(), request.version(), request.mode()))
        .thenReturn(List.of());
    when(context.compile(failing.uri(), failing.content(), failing.version(), failing.mode()))
        .thenThrow(new IllegalStateException("boom"));
    final var valueOn = new AtomicReference<String>();
    final var failureOn = new AtomicReference<String>();
    try {
      final CompletableFuture<CompileResponse> value =
          delivering
              .compile(request)
              .whenComplete((r, t) -> valueOn.set(Thread.currentThread().getName()));
      final CompletableFuture<CompileResponse> failure =
          delivering
              .compile(failing)
              .whenComplete((r, t) -> failureOn.set(Thread.currentThread().getName()));

      final var deliverer =
          new Thread(
              () -> {
                for (int i = 0; i < 2; i++) {
                  try {
                    deliveries.take().run();
                  } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                  }
                }
              },
              DELIVERY_THREAD);
      deliverer.start();
      deliverer.join(5_000);

      assertThat(value).isDone().isNotCompletedExceptionally();
      assertThat(failure).isCompletedExceptionally();
      assertThat(valueOn.get()).isEqualTo(DELIVERY_THREAD);
      assertThat(failureOn.get()).isEqualTo(DELIVERY_THREAD);
    } finally {
      delivering.close();
    }
  }

  // The idle wait tests rely on: not done while a request runs, nor until its result is delivered,
  // which the worker thread does before the drain's own delivery.
  @Test
  void drained_requestStillRunning_completesOnlyAfterItsResultIsDelivered() throws Exception {
    final var deliveries = new LinkedBlockingQueue<Runnable>();
    final var draining =
        new CompilationWorker("lathe-module-drain", () -> context, deliveries::add);
    final var request = new CompileRequest("file:///A.java", "class A {}", 1, 1L, CompileMode.OPEN);
    final var entered = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    when(context.compile(request.uri(), request.content(), request.version(), request.mode()))
        .thenAnswer(
            ignored -> {
              entered.countDown();
              await(release);
              return List.of();
            });
    try {
      final CompletableFuture<CompileResponse> compiled = draining.compile(request);
      await(entered);
      final CompletableFuture<Void> drained = draining.drained();
      assertThat(drained).as("the request still runs").isNotDone();

      release.countDown();
      deliveries.poll(5, TimeUnit.SECONDS).run();
      assertThat(compiled).isDone();
      assertThat(drained).as("its result delivered, the drain not yet").isNotDone();
      deliveries.poll(5, TimeUnit.SECONDS).run();

      assertThat(drained).isDone();
      assertThat(draining.submitted()).isEqualTo(1);
    } finally {
      draining.close();
    }

    assertThat(draining.drained()).as("a closed worker has nothing left").isDone();
  }

  @Test
  void dropFromCache_initializedContext_dropsOnWorkerContext() {
    final var request = new CompileRequest("file:///A.java", "class A {}", 1, 1L, CompileMode.OPEN);
    when(context.compile(request.uri(), request.content(), request.version(), request.mode()))
        .thenReturn(List.of());

    worker.compile(request).join();
    worker.dropFromCache(request.uri());

    worker.compile(request).join();
    verify(context).dropFromCache(request.uri());
  }

  @Test
  void documentSymbol_request_delegatesToContext() {
    final String uri = "file:///A.java";
    final String content = "class A {}";
    final var symbol = new DocumentSymbol();
    when(context.documentSymbol(uri, content)).thenReturn(List.of(symbol));

    final var result = worker.submit(session -> session.documentSymbol(uri, content)).join();

    assertThat(result).containsExactly(symbol);
  }

  @Test
  void foldingRange_request_delegatesToContext() {
    final String uri = "file:///A.java";
    final String content = "class A {}";
    final var range = new FoldingRange(0, 1);
    when(context.foldingRange(uri, content)).thenReturn(List.of(range));

    final var result = worker.submit(session -> session.foldingRange(uri, content)).join();

    assertThat(result).containsExactly(range);
  }

  @Test
  void close_initializedContext_closesContext() {
    final var request = new CompileRequest("file:///A.java", "class A {}", 1, 1L, CompileMode.OPEN);
    when(context.compile(request.uri(), request.content(), request.version(), request.mode()))
        .thenReturn(List.of());

    worker.compile(request).join();
    worker.close();

    verify(context).close();
  }

  // A released session goes once the queued requests are done; the next request builds a new one
  // and answers the same.
  @Test
  void releaseSession_closesItAfterQueuedRequests_nextRequestBuildsANewOne() {
    final var sessions = new AtomicInteger();
    final var first = mock(SourceAnalysisSession.class);
    final var second = mock(SourceAnalysisSession.class);
    final String uri = "file:///A.java";
    final String content = "class A {}";
    final var symbol = new DocumentSymbol();
    when(first.documentSymbol(uri, content)).thenReturn(List.of(symbol));
    when(second.documentSymbol(uri, content)).thenReturn(List.of(symbol));
    final var releasing =
        new CompilationWorker(
            "lathe-module-release",
            () -> sessions.incrementAndGet() == 1 ? first : second,
            Runnable::run,
            status -> {});
    try {
      assertThat(releasing.idleFor(0)).as("no session before a request").isFalse();

      final var queued = releasing.submit(session -> session.documentSymbol(uri, content));
      assertThat(releasing.idleFor(0)).isTrue();
      assertThat(releasing.idleFor(60_000)).as("just asked").isFalse();
      releasing.releaseSession();

      assertThat(queued.join()).containsExactly(symbol);
      assertThat(releasing.idleFor(0)).as("released").isFalse();
      assertThat(releasing.submit(session -> session.documentSymbol(uri, content)).join())
          .containsExactly(symbol);
      verify(first).close();
      verify(second, never()).close();
      assertThat(sessions).hasValue(2);
    } finally {
      releasing.close();
    }
  }

  @Test
  void compile_directError_invokesFatalTermination() {
    assertTermination(new OutOfMemoryError("expected"), 1);
  }

  @Test
  void compile_wrappedError_invokesFatalTermination() {
    assertTermination(new IllegalStateException(new AssertionError("expected")), 1);
  }

  @Test
  void compile_ordinaryFailure_doesNotInvokeFatalTermination() {
    assertTermination(new IllegalStateException("expected"), 0);
  }

  @Test
  void searchReferencesTransient_activeRequest_delegatesToContext() {
    final String uri = "file:///A.java";
    final String content = "class A {}";
    final List<TransientSource> sources = List.of(new TransientSource(uri, content));
    final ReferenceTarget target = mock(ReferenceTarget.class);
    final ReferenceMatch match = mock(ReferenceMatch.class);
    final IntConsumer progress = hits -> {};
    final CancelChecker cancelChecker = () -> {};
    when(context.searchReferencesTransient(
            sources, target, false, Set.of(), progress, cancelChecker))
        .thenReturn(new SearchHits<>(List.of(match), List.of()));

    final var result =
        worker
            .submit(
                session ->
                    session.searchReferencesTransient(
                        sources, target, false, Set.of(), progress, cancelChecker),
                cancelChecker)
            .join();

    assertThat(result.hits()).containsExactly(match);
  }

  @Test
  void searchReferencesTransient_cancelledWhileQueued_skipsContext() {
    final String uri = "file:///A.java";
    final String content = "class A {}";
    final List<TransientSource> sources = List.of(new TransientSource(uri, content));
    final IntConsumer progress = hits -> {};
    final var entered = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    final var cancelled = new AtomicBoolean();
    final ReferenceTarget target = mock(ReferenceTarget.class);
    final CancelChecker cancelChecker =
        () -> {
          if (cancelled.get()) {
            throw new CancellationException();
          }
        };
    final var request = new CompileRequest(uri, content, 1, 1L, CompileMode.OPEN);
    when(context.compile(request.uri(), request.content(), request.version(), request.mode()))
        .thenAnswer(
            ignored -> {
              entered.countDown();
              await(release);
              return List.of();
            });

    final CompletableFuture<CompileResponse> blocker = worker.compile(request);
    await(entered);
    final CompletableFuture<SearchHits<ReferenceMatch>> cancelledFuture =
        worker.submit(
            session ->
                session.searchReferencesTransient(
                    sources, target, false, Set.of(), progress, cancelChecker),
            cancelChecker);
    cancelled.set(true);
    release.countDown();

    blocker.join();
    // CompletableFuture.join() reports a cancellation either as a bare
    // CancellationException or wrapped with it as the cause, depending on the JDK
    // (bare on 21.0.12, wrapped on 26); accept both.
    assertThatThrownBy(cancelledFuture::join)
        .satisfiesAnyOf(
            thrown -> assertThat(thrown).isInstanceOf(CancellationException.class),
            thrown -> assertThat(thrown).hasCauseInstanceOf(CancellationException.class));
    verify(context, never())
        .searchReferencesTransient(sources, target, false, Set.of(), progress, cancelChecker);
  }

  private void assertTermination(final Throwable failure, final int expectedStatus) {
    final var status = new AtomicInteger();
    final SourceAnalysisSession failingContext = mock(SourceAnalysisSession.class);
    final var failingWorker =
        new CompilationWorker(
            "lathe-module-fatal-test", () -> failingContext, Runnable::run, status::set);
    final var request = new CompileRequest("file:///A.java", "class A {}", 1, 1L, CompileMode.OPEN);
    when(failingContext.compile(
            request.uri(), request.content(), request.version(), request.mode()))
        .thenThrow(failure);

    try {
      final CompletableFuture<CompileResponse> future = failingWorker.compile(request);

      assertThatThrownBy(future::join).hasCause(failure);
      failingWorker
          .submit(session -> session.documentSymbol(request.uri(), request.content()))
          .join();
      assertThat(status).hasValue(expectedStatus);
    } finally {
      failingWorker.close();
    }
  }

  private static void await(final CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
