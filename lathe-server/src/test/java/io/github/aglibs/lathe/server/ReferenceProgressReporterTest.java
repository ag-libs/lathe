package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReferenceProgressReporterTest {

  @Test
  void open_unsupportedClient_sendsNoProgress() {
    final LanguageClient client = mock(LanguageClient.class);
    final var reporter = new ReferenceProgressReporter(client);
    final var response = new CompletableFuture<Void>();

    final var task = reporter.open(null, response);
    task.begin("String", 1);
    task.advance(true, 2);
    task.finish(null);

    verify(client, never()).createProgress(any());
    verify(client, never()).notifyProgress(any());
  }

  @Test
  void task_completedSearch_sendsMonotonicProtocolSequence() {
    final LanguageClient client = mock(LanguageClient.class);
    final var reporter = new ReferenceProgressReporter(client, 0);
    final var response = new CompletableFuture<Void>();
    final var token = Either.<String, Integer>forLeft("request-token");

    final var task = reporter.open(token, response);
    task.begin("String", 2);
    task.advance(false, 1);
    task.advance(true, 2);
    task.finish(null);

    final var captor = ArgumentCaptor.forClass(ProgressParams.class);
    verify(client, times(4)).notifyProgress(captor.capture());
    final var values = captor.getAllValues();
    assertThat(values).extracting(ProgressParams::getToken).containsOnly(token);
    assertThat(values.get(0).getValue().getLeft()).isInstanceOf(WorkDoneProgressBegin.class);
    assertThat(((WorkDoneProgressReport) values.get(1).getValue().getLeft()).getPercentage())
        .isEqualTo(50);
    assertThat(((WorkDoneProgressReport) values.get(2).getValue().getLeft()).getPercentage())
        .isEqualTo(100);
    assertThat(((WorkDoneProgressEnd) values.get(3).getValue().getLeft()).getMessage())
        .isEqualTo("Completed");
  }

  @Test
  void task_rapidUpdates_throttlesReports() {
    final LanguageClient client = mock(LanguageClient.class);
    final var reporter = new ReferenceProgressReporter(client, Long.MAX_VALUE);
    final var response = new CompletableFuture<Void>();
    final var task = reporter.open(Either.<String, Integer>forLeft("request-token"), response);

    task.begin("String", 2);
    task.advance(false, 1);
    task.advance(true, 1);
    task.finish(null);

    verify(client, times(2)).notifyProgress(any());
  }

  @Test
  void open_supportedClientWithoutToken_createsServerToken() {
    final LanguageClient client = mock(LanguageClient.class);
    when(client.createProgress(any())).thenReturn(CompletableFuture.completedFuture(null));
    final var reporter = new ReferenceProgressReporter(client);
    reporter.setSupported(true);
    final var task = reporter.open(null, new CompletableFuture<Void>());

    task.begin("String", 0);
    task.finish(null);

    final var createCaptor = ArgumentCaptor.forClass(WorkDoneProgressCreateParams.class);
    final var progressCaptor = ArgumentCaptor.forClass(ProgressParams.class);
    verify(client).createProgress(createCaptor.capture());
    verify(client, times(2)).notifyProgress(progressCaptor.capture());
    assertThat(progressCaptor.getAllValues())
        .extracting(ProgressParams::getToken)
        .containsOnly(createCaptor.getValue().getToken());
  }

  @Test
  void cancel_activeToken_cancelsOnlyAssociatedResponse() {
    final LanguageClient client = mock(LanguageClient.class);
    final var reporter = new ReferenceProgressReporter(client);
    final var first = new CompletableFuture<Void>();
    final var second = new CompletableFuture<Void>();
    final var firstToken = Either.<String, Integer>forLeft("first");
    final var secondToken = Either.<String, Integer>forLeft("second");
    final var firstTask = reporter.open(firstToken, first);
    final var secondTask = reporter.open(secondToken, second);
    firstTask.begin("String", 1);

    reporter.cancel(firstToken);

    assertThat(first).isCancelled();
    assertThat(second).isNotCancelled();
    firstTask.finish(new CancellationException());
    secondTask.finish(null);
    final var captor = ArgumentCaptor.forClass(ProgressParams.class);
    verify(client, times(2)).notifyProgress(captor.capture());
    assertThat(
            ((WorkDoneProgressEnd) captor.getAllValues().getLast().getValue().getLeft())
                .getMessage())
        .isEqualTo("Cancelled");
  }

  @Test
  void finish_failedTask_reportsFailedOutcome() {
    final LanguageClient client = mock(LanguageClient.class);
    final var reporter = new ReferenceProgressReporter(client);
    final var task =
        reporter.open(
            Either.<String, Integer>forLeft("request-token"), new CompletableFuture<Void>());
    task.begin("String", 1);

    task.finish(new IllegalStateException("expected"));

    final var captor = ArgumentCaptor.forClass(ProgressParams.class);
    verify(client, times(2)).notifyProgress(captor.capture());
    assertThat(
            ((WorkDoneProgressEnd) captor.getAllValues().getLast().getValue().getLeft())
                .getMessage())
        .isEqualTo("Failed");
  }

  @Test
  void finish_completedTask_removesCancellationAssociation() {
    final LanguageClient client = mock(LanguageClient.class);
    final var reporter = new ReferenceProgressReporter(client);
    final var response = new CompletableFuture<Void>();
    final var token = Either.<String, Integer>forLeft("request-token");
    final var task = reporter.open(token, response);

    task.finish(null);

    assertThatCode(() -> reporter.cancel(token)).doesNotThrowAnyException();
    assertThat(response).isNotCancelled();
  }

  @Test
  void task_notificationFailure_doesNotFailLifecycle() {
    final LanguageClient client = mock(LanguageClient.class);
    doThrow(new IllegalStateException("expected")).when(client).notifyProgress(any());
    final var reporter = new ReferenceProgressReporter(client, 0);
    final var task =
        reporter.open(
            Either.<String, Integer>forLeft("request-token"), new CompletableFuture<Void>());

    assertThatCode(
            () -> {
              task.begin("String", 1);
              task.advance(true, 1);
              task.finish(null);
            })
        .doesNotThrowAnyException();
  }
}
