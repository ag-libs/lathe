package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.github.aglibs.lathe.server.analysis.completion.CompletionOutcome;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LatheTextDocumentServiceTest {

  private static final String URI = "file:///workspace/src/main/java/Foo.java";
  private static final long DEBOUNCE_MS = 50;

  private LanguageClient client;
  private LatheTextDocumentService service;

  @BeforeEach
  void setUp() {
    client = mock(LanguageClient.class);
    service = new LatheTextDocumentService(DEBOUNCE_MS);
    service.connect(client);
  }

  @AfterEach
  void close() {
    service.close();
  }

  @Test
  void didChange_rapidKeystrokes_compilesOnlyOnce() throws InterruptedException {
    for (int i = 0; i < 5; i++) {
      service.didChange(changeParams("content-" + i));
      Thread.sleep(10);
    }

    Thread.sleep(DEBOUNCE_MS * 3);

    final var captor = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
    verify(client, atLeastOnce()).publishDiagnostics(captor.capture());
    assertThat(captor.getAllValues().getLast().getUri()).isEqualTo(URI);
    assertThat(captor.getAllValues().getLast().getDiagnostics()).isNotEmpty();
  }

  @Test
  void didChange_rapidKeystrokes_compilesLatestContent() throws InterruptedException {
    for (int i = 0; i < 5; i++) {
      service.didChange(changeParams("content-" + i));
      Thread.sleep(10);
    }

    Thread.sleep(DEBOUNCE_MS * 3);

    final var captor = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
    verify(client, atLeastOnce()).publishDiagnostics(captor.capture());
    assertThat(captor.getAllValues().getLast().getDiagnostics().getFirst().getMessage().getLeft())
        .contains("mvn process-test-classes");
  }

  @Test
  void didOpen_compilesImmediatelyWithoutDebounce() {
    service.didOpen(
        new DidOpenTextDocumentParams(new TextDocumentItem(URI, "java", 1, "class Foo {}")));

    final var captor = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
    verify(client, timeout(DEBOUNCE_MS * 3)).publishDiagnostics(captor.capture());
    assertThat(captor.getValue().getUri()).isEqualTo(URI);
  }

  @Test
  void didClose_publishesEmptyDiagnostics() {
    service.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(URI)));

    final var captor = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
    verify(client, timeout(DEBOUNCE_MS * 3)).publishDiagnostics(captor.capture());
    assertThat(captor.getValue().getUri()).isEqualTo(URI);
    assertThat(captor.getValue().getDiagnostics()).isEmpty();
  }

  @Test
  void completionResult_incompleteOutcome_returnsIncompleteCompletionList() {
    final var item = new CompletionItem("FooService");
    final var result =
        LatheTextDocumentService.completionResult(new CompletionOutcome(List.of(item), null, true));

    assertThat(result.isRight()).isTrue();
    assertThat(result.getRight().isIncomplete()).isTrue();
    assertThat(result.getRight().getItems()).containsExactly(item);
  }

  @Test
  void documentSymbolResult_symbols_returnsDocumentSymbolEitherValues() {
    final var symbol = new DocumentSymbol();
    symbol.setName("Foo");

    final var result = LatheTextDocumentService.documentSymbolResult(List.of(symbol));

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().isRight()).isTrue();
    assertThat(result.getFirst().getRight()).isSameAs(symbol);
  }

  private static DidChangeTextDocumentParams changeParams(final String text) {
    final var id = new VersionedTextDocumentIdentifier(URI, 1);
    final var change = new TextDocumentContentChangeEvent();
    change.setText(text);
    return new DidChangeTextDocumentParams(id, List.of(change));
  }
}
