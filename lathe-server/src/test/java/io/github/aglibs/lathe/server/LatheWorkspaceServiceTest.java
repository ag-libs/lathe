package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.aglibs.lathe.server.run.ReplayOutcome;
import io.github.aglibs.lathe.server.run.RunTarget;
import io.github.aglibs.lathe.server.run.RunnableKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.TextDocumentItem;
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
  void executeCommand_cancelUnknownToken_completesWithoutError() throws Exception {
    textDocumentService.initialize(tmp);
    final var argument = new JsonObject();
    argument.addProperty("token", "no-such-token");
    final var params = new ExecuteCommandParams("lathe.run.cancel", List.of(argument));

    final var result = service.executeCommand(params).get(5, TimeUnit.SECONDS);

    assertThat(result).isNull();
  }

  @Test
  void executeCommand_runTestFreshWorkspace_returnsBlockedOnMissingRunnerJar() throws Exception {
    textDocumentService.initialize(tmp);
    final var selection = new JsonObject();
    selection.addProperty("selectorKind", "CLASS");
    selection.addProperty("selectorValue", "com.example.Foo");
    final var selections = new JsonArray();
    selections.add(selection);
    final var argument = new JsonObject();
    argument.addProperty("moduleRel", "app");
    argument.add("selections", selections);
    final var params = new ExecuteCommandParams("lathe.run.test", List.of(argument));

    final var result = service.executeCommand(params).get(5, TimeUnit.SECONDS);

    assertThat(result).isInstanceOf(ReplayOutcome.class);
    final var outcome = (ReplayOutcome) result;
    assertThat(outcome.launched()).isFalse();
    assertThat(outcome.blockedReasons()).anyMatch(reason -> reason.contains("run a build first"));
  }

  @Test
  void executeCommand_listRunnables_returnsDiscoveredTargets() throws Exception {
    final Path sourceRoot = tmp.resolve("module/src/test/java");
    final Path source = sourceRoot.resolve("com/example/FooTest.java");
    Files.createDirectories(source.getParent());
    Files.writeString(
        source,
        """
        package com.example;

        import org.junit.jupiter.api.Test;

        class FooTest {
          @Test
          void bar_condition_result() {
          }
        }
        """);
    TestCompiler.writeModuleParams(tmp, "module", sourceRoot, null);
    textDocumentService.initialize(tmp);
    final String uri = source.toUri().toString();
    textDocumentService.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(uri, "java", 1, Files.readString(source))));
    final var argument = new JsonObject();
    argument.addProperty("uri", uri);
    final var params = new ExecuteCommandParams("lathe.runnables.list", List.of(argument));

    final var result = service.executeCommand(params).get(5, TimeUnit.SECONDS);

    assertThat(result).isInstanceOf(List.class);
    @SuppressWarnings("unchecked")
    final List<RunTarget> targets = (List<RunTarget>) result;
    assertThat(targets)
        .extracting(RunTarget::kind)
        .contains(RunnableKind.TEST_METHOD, RunnableKind.TEST_CLASS);
    assertThat(targets)
        .filteredOn(t -> t.kind() == RunnableKind.TEST_METHOD)
        .extracting(RunTarget::moduleRel)
        .containsExactly("module");
  }
}
