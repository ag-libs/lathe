package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.module.CompileResponse;
import java.util.List;
import java.util.Set;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DiagnosticPublisherTest {

  private LanguageClient client;
  private DocumentRegistry registry;
  private DiagnosticPublisher publisher;

  @BeforeEach
  void setUp() {
    client = mock(LanguageClient.class);
    registry = new DocumentRegistry();
    publisher = new DiagnosticPublisher(client, registry);
  }

  @Test
  void publishIfCurrent_currentSnapshot_publishesDiagnosticsAndRefreshesTokens() {
    final var snapshot = registry.put("file:///A.java", "class A {}", 1);
    final var result =
        new CompileResponse("file:///A.java", snapshot.generation(), List.of(), Set.of());

    final boolean published = publisher.publishIfCurrent(snapshot, result);
    publisher.refreshTokensIfCurrent(snapshot, result);

    assertThat(published).isTrue();
    verify(client).publishDiagnostics(new PublishDiagnosticsParams("file:///A.java", List.of()));
    verify(client, times(2)).refreshSemanticTokens();
  }

  @Test
  void publishIfCurrent_staleSnapshot_doesNotPublish() {
    final var old = registry.put("file:///A.java", "v1", 1);
    registry.put("file:///A.java", "v2", 2);
    final var result = new CompileResponse("file:///A.java", old.generation(), List.of(), Set.of());

    final boolean published = publisher.publishIfCurrent(old, result);
    publisher.refreshTokensIfCurrent(old, result);

    assertThat(published).isFalse();
    verify(client, never()).publishDiagnostics(any());
    verify(client, never()).refreshSemanticTokens();
  }

  @Test
  void publishEmpty_sendsEmptyDiagnostics() {
    publisher.publishEmpty("file:///A.java");

    verify(client).publishDiagnostics(new PublishDiagnosticsParams("file:///A.java", List.of()));
  }

  @Test
  void publishMissing_sendsWarningDiagnostic() {
    publisher.publishMissing("file:///A.java", "no module");

    final var captor = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
    verify(client).publishDiagnostics(captor.capture());
    assertThat(captor.getValue().getUri()).isEqualTo("file:///A.java");
    assertThat(captor.getValue().getDiagnostics()).hasSize(1);
    assertThat(captor.getValue().getDiagnostics().getFirst().getSeverity())
        .isEqualTo(DiagnosticSeverity.Warning);
  }

  @Test
  void publishError_currentSnapshot_sendsErrorDiagnostic() {
    final var snapshot = registry.put("file:///A.java", "class A {}", 1);

    publisher.publishError(snapshot, CompileMode.FULL, new RuntimeException("boom"));

    final var captor = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
    verify(client).publishDiagnostics(captor.capture());
    assertThat(captor.getValue().getUri()).isEqualTo("file:///A.java");
    assertThat(captor.getValue().getDiagnostics()).hasSize(1);
    assertThat(captor.getValue().getDiagnostics().getFirst().getSeverity())
        .isEqualTo(DiagnosticSeverity.Error);
  }

  @Test
  void publishError_staleSnapshot_doesNotPublish() {
    final var old = registry.put("file:///A.java", "v1", 1);
    registry.put("file:///A.java", "v2", 2);

    publisher.publishError(old, CompileMode.FULL, new RuntimeException("boom"));

    verify(client, never()).publishDiagnostics(any());
  }
}
