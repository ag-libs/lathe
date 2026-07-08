package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.google.gson.JsonObject;
import io.github.aglibs.lathe.server.run.ReplayOutcome;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheWorkspaceServiceTest {

  private static final long DEBOUNCE_MS = 50;

  private LatheTextDocumentService textDocumentService;
  private LatheWorkspaceService service;
  @TempDir private Path tmp;

  @BeforeEach
  void setUp() {
    final LanguageClient client = mock(LanguageClient.class);
    textDocumentService = new LatheTextDocumentService(DEBOUNCE_MS);
    textDocumentService.connect(client);
    service = new LatheWorkspaceService(textDocumentService);
  }

  @AfterEach
  void close() {
    textDocumentService.close();
  }

  @Test
  void executeCommand_unknownCommand_completesWithNull() throws Exception {
    final var params = new ExecuteCommandParams("lathe.unknown", List.of());

    final var result = service.executeCommand(params).get(5, TimeUnit.SECONDS);

    assertThat(result).isNull();
  }

  @Test
  void executeCommand_runTestFreshWorkspace_returnsBlockedOnMissingRunnerJar() throws Exception {
    textDocumentService.initialize(tmp);
    final var argument = new JsonObject();
    argument.addProperty("moduleRel", "app");
    argument.addProperty("selectorKind", "CLASS");
    argument.addProperty("selectorValue", "com.example.Foo");
    final var params = new ExecuteCommandParams("lathe.run.test", List.of(argument));

    final var result = service.executeCommand(params).get(5, TimeUnit.SECONDS);

    assertThat(result).isInstanceOf(ReplayOutcome.class);
    final var outcome = (ReplayOutcome) result;
    assertThat(outcome.launched()).isFalse();
    assertThat(outcome.blockedReasons()).anyMatch(reason -> reason.contains("run a build first"));
  }
}
