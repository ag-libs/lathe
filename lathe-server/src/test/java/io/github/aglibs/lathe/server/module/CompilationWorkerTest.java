package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.SourceAnalysisSession;
import java.util.List;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.FoldingRange;
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
}
