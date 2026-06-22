package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.ReferenceMatch;
import io.github.aglibs.lathe.server.analysis.ReferenceTarget;
import io.github.aglibs.lathe.server.analysis.SourceAnalysisSession;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CompilationWorkerTest {

  private final SourceAnalysisSession context = mock(SourceAnalysisSession.class);
  private final CompilationWorker worker =
      new CompilationWorker("lathe-module-test", () -> context);

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

    final var result = worker.documentSymbol(uri, content).join();

    assertThat(result).containsExactly(symbol);
  }

  @Test
  void foldingRange_request_delegatesToContext() {
    final String uri = "file:///A.java";
    final String content = "class A {}";
    final var range = new FoldingRange(0, 1);
    when(context.foldingRange(uri, content)).thenReturn(List.of(range));

    final var result = worker.foldingRange(uri, content).join();

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

  @Test
  void compile_directOutOfMemory_invokesFatalTermination() {
    assertTermination(new OutOfMemoryError("expected"), 1);
  }

  @Test
  void compile_wrappedOutOfMemory_invokesFatalTermination() {
    assertTermination(new IllegalStateException(new OutOfMemoryError("expected")), 1);
  }

  @Test
  void compile_ordinaryFailure_doesNotInvokeFatalTermination() {
    assertTermination(new IllegalStateException("expected"), 0);
  }

  @Test
  void searchReferencesTransient_activeRequest_delegatesToContext() {
    final String uri = "file:///A.java";
    final String content = "class A {}";
    final ReferenceTarget target = mock(ReferenceTarget.class);
    final ReferenceMatch match = mock(ReferenceMatch.class);
    final CancelChecker cancelChecker = () -> {};
    when(context.searchReferencesTransient(uri, content, target, false, cancelChecker))
        .thenReturn(List.of(match));

    final var result =
        worker.searchReferencesTransient(uri, content, target, false, cancelChecker).join();

    assertThat(result).containsExactly(match);
  }

  @Test
  void searchReferencesTransient_cancelledWhileQueued_skipsContext() {
    final String uri = "file:///A.java";
    final String content = "class A {}";
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
    final CompletableFuture<List<ReferenceMatch>> cancelledFuture =
        worker.searchReferencesTransient(uri, content, target, false, cancelChecker);
    cancelled.set(true);
    release.countDown();

    blocker.join();
    assertThatThrownBy(cancelledFuture::join).hasCauseInstanceOf(CancellationException.class);
    verify(context, never()).searchReferencesTransient(uri, content, target, false, cancelChecker);
  }

  private void assertTermination(final Throwable failure, final int expectedStatus) {
    final var status = new AtomicInteger();
    final SourceAnalysisSession failingContext = mock(SourceAnalysisSession.class);
    final var failingWorker =
        new CompilationWorker("lathe-module-fatal-test", () -> failingContext, status::set);
    final var request = new CompileRequest("file:///A.java", "class A {}", 1, 1L, CompileMode.OPEN);
    when(failingContext.compile(
            request.uri(), request.content(), request.version(), request.mode()))
        .thenThrow(failure);

    try {
      final CompletableFuture<CompileResponse> future = failingWorker.compile(request);

      assertThatThrownBy(future::join).hasCause(failure);
      failingWorker.documentSymbol(request.uri(), request.content()).join();
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
