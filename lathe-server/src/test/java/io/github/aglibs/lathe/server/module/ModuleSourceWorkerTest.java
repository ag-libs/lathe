package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.ModuleAnalysisSession;
import java.util.List;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModuleSourceWorkerTest {

  private final ModuleAnalysisSession context = mock(ModuleAnalysisSession.class);
  private final ModuleSourceWorker worker =
      new ModuleSourceWorker("lathe-module-test", () -> context);

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
  void close_initializedContext_closesContext() {
    final var request = new CompileRequest("file:///A.java", "class A {}", 1, 1L, CompileMode.OPEN);
    when(context.compile(request.uri(), request.content(), request.version(), request.mode()))
        .thenReturn(List.of());

    worker.compile(request).join();
    worker.close();

    verify(context).close();
  }
}
