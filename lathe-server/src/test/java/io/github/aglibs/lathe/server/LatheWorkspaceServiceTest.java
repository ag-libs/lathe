package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.aglibs.lathe.analysis.MissingImportsResult;
import io.github.aglibs.lathe.analysis.MissingImportsResult.MissingImport;
import io.github.aglibs.lathe.analysis.RunTarget;
import io.github.aglibs.lathe.analysis.RunnableKind;
import io.github.aglibs.lathe.analysis.TypeHierarchyExplorerResult;
import io.github.aglibs.lathe.analysis.testing.TestJavac;
import io.github.aglibs.lathe.server.run.LaunchOutcome;
import io.github.aglibs.lathe.server.run.RunConfigInfo;
import io.github.aglibs.lathe.server.run.RunConfigWriter;
import io.github.aglibs.lathe.server.run.TestCounterparts;
import io.github.aglibs.lathe.server.run.TestSource;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LatheWorkspaceServiceTest extends ServiceTestBase {

  private LatheWorkspaceService workspace;

  @BeforeEach
  void connectWorkspace() {
    workspace = new LatheWorkspaceService(service);
  }

  @Test
  void executeCommand_unknownCommand_completesWithNull() throws Exception {
    final var params = new ExecuteCommandParams("lathe.unknown", List.of());

    final var result = workspace.executeCommand(params).get(TIMEOUT_S, TimeUnit.SECONDS);

    assertThat(result).isNull();
  }

  @Test
  void executeCommand_createTypeInvalidArgs_returnInvalidParamsWithReason() throws Exception {
    openShop();
    // Async: a name rejected inside the server surfaces its reason, not lsp4j's "Internal error".
    assertInvalidParams(createTypeError("main", "class", "9Bad"), "not a valid Java type name");

    // Sync: an unknown scope rejected while parsing the command arguments.
    assertInvalidParams(createTypeError("bogus", "class", "Foo"), "unknown source scope");
  }

  private static void assertInvalidParams(final ResponseError error, final String reason) {
    assertThat(error.getCode()).isEqualTo(ResponseErrorCode.InvalidParams.getValue());
    assertThat(error.getMessage()).contains(reason);
  }

  private ResponseError createTypeError(final String kind, final String type, final String name) {
    return commandError(
        LatheWorkspaceService.CREATE_TYPE_COMMAND,
        json(
            "moduleRel", "module", "kind", kind, "pkg", "com.example", "type", type, "name", name));
  }

  @Test
  void executeCommand_cancelUnknownToken_completesWithoutError() throws Exception {
    service.initialize(tmp);
    final var argument = new JsonObject();
    argument.addProperty("token", "no-such-token");
    final var params = new ExecuteCommandParams("lathe.run.cancel", List.of(argument));

    final var result = workspace.executeCommand(params).get(TIMEOUT_S, TimeUnit.SECONDS);

    assertThat(result).isNull();
  }

  @Test
  void executeCommand_runTestFreshWorkspace_returnsBlockedOnMissingRunnerJar() throws Exception {
    service.initialize(tmp);
    final var selection = new JsonObject();
    selection.addProperty("selectorKind", "CLASS");
    selection.addProperty("selectorValue", "com.example.Foo");
    final var selections = new JsonArray();
    selections.add(selection);
    final var argument = new JsonObject();
    argument.addProperty("moduleRel", "app");
    argument.add("selections", selections);
    final var params = new ExecuteCommandParams("lathe.run.test", List.of(argument));

    final var result = workspace.executeCommand(params).get(TIMEOUT_S, TimeUnit.SECONDS);

    assertThat(result).isInstanceOf(LaunchOutcome.class);
    final var outcome = (LaunchOutcome) result;
    assertThat(outcome.launched()).isFalse();
    assertThat(outcome.blockedReasons()).anyMatch(reason -> reason.contains("run a build first"));
  }

  @Test
  void executeCommand_listRunnables_returnsDiscoveredTargets() throws Exception {
    final Path source = prepareFooTestWorkspace();
    final String uri = source.toUri().toString();
    openDoc(source, Files.readString(source));

    final Object result = listRunnables(uri).get(TIMEOUT_S, TimeUnit.SECONDS);

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

  @Test
  void executeCommand_listRunnables_documentNotOpen_returnsEmpty() throws Exception {
    final Path source = prepareFooTestWorkspace();
    final String uri = source.toUri().toString();

    // Not opened: only an open document has editor content to analyze. The neotest client discovers
    // against the server-confirmed open, so a miss answers empty rather than compiling from disk.
    final Object result = listRunnables(uri).get(TIMEOUT_S, TimeUnit.SECONDS);

    assertThat(result).isInstanceOf(List.class);
    assertThat((List<?>) result).isEmpty();
  }

  private Path prepareFooTestWorkspace() throws IOException {
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
    TestCompiler.writeModuleParams(tmp, "module", sourceRoot, null, TestJavac.JUNIT_CLASSPATH);
    service.initialize(tmp);
    return source;
  }

  private CompletableFuture<Object> listRunnables(final String uri) {
    final var argument = new JsonObject();
    argument.addProperty("uri", uri);
    return workspace.executeCommand(
        new ExecuteCommandParams("lathe.runnables.list", List.of(argument)));
  }

  // The commands the editor's pickers and test tree use, answered from the workspace layout.
  @Test
  void executeCommand_projectCommands_answerFromTheModulesAndTheirTrees() throws Exception {
    final ShopFixture shop = openShop();

    assertThat(command(LatheWorkspaceService.MODULES_COMMAND)).isEqualTo(List.of("module"));
    assertThat(command(LatheWorkspaceService.PACKAGES_COMMAND, json("moduleRel", "module")))
        .asInstanceOf(InstanceOfAssertFactories.list(PackageEntry.class))
        .containsExactly(new PackageEntry("com.example", "main"));
    final var cart =
        (CreateTypeResult)
            command(
                LatheWorkspaceService.CREATE_TYPE_COMMAND,
                json(
                    "moduleRel",
                    "module",
                    "kind",
                    "main",
                    "pkg",
                    "com.example",
                    "type",
                    "class",
                    "name",
                    "Cart"));
    assertThat(Path.of(cart.path())).isEqualTo(shop.shop().resolveSibling("Cart.java"));
    assertThat(cart.content()).contains("class Cart");

    assertThat(command(LatheWorkspaceService.TEST_MODULES_COMMAND))
        .as("the module has no test tree")
        .isEqualTo(List.of());
    final var classNames = new JsonArray();
    classNames.add("com.example.ShopTest");
    final var sources = json("moduleRel", "module");
    sources.add("classNames", classNames);
    assertThat(command(LatheWorkspaceService.TEST_SOURCES_COMMAND, sources))
        .isEqualTo(List.of(new TestSource("com.example.ShopTest", "")));
    assertThat(
            command(
                LatheWorkspaceService.TEST_COUNTERPARTS_COMMAND,
                json("uri", shop.shop().toUri().toString())))
        .as("a main file with no test yet")
        .isEqualTo(new TestCounterparts(false, List.of()));

    assertThat(command(LatheWorkspaceService.RESOURCES_COMMAND)).isEqualTo(List.of());
    final var manifest =
        (String)
            command(
                LatheWorkspaceService.RESOURCE_OPEN_COMMAND,
                json(
                    "jar",
                    TestJavac.jarOf(Test.class).toString(),
                    "entry",
                    "META-INF/MANIFEST.MF"));
    assertThat(Files.readString(Path.of(manifest))).startsWith("Manifest-Version");
    assertThat(
            command(
                LatheWorkspaceService.RESOURCE_REFRESH_COMMAND,
                json("uri", shop.shop().toUri().toString())))
        .as("a source is not a tracked resource")
        .isNull();
  }

  @Test
  void executeCommand_codeCommandsOnTheShop_instantiationsHierarchyAndImports() throws Exception {
    final ShopFixture shop = openShop();
    openDoc(shop.shop(), Files.readString(shop.shop()));
    final Path buyer =
        Files.writeString(
            Files.createDirectories(shop.shop().getParent().resolveSibling("other"))
                .resolve("Buyer.java"),
            "package com.other;\nclass Buyer { Shop shop; }\n");
    openDoc(buyer, Files.readString(buyer));
    awaitWorkerIdle();
    final JsonObject onShop = positionArgument(shop.shop(), "Shop implements");

    assertThat(command(LatheWorkspaceService.INSTANTIATIONS_COMMAND, onShop))
        .asInstanceOf(InstanceOfAssertFactories.list(Location.class))
        .extracting(location -> Path.of(URI.create(location.getUri())).getFileName().toString())
        .containsExactly("Use.java");
    final var hierarchy =
        (TypeHierarchyExplorerResult) command(LatheWorkspaceService.TYPE_HIERARCHY_COMMAND, onShop);
    assertThat(hierarchy.self().getName()).isEqualTo("Shop");
    assertThat(hierarchy.supertypes()).extracting(TypeHierarchyItem::getName).contains("Api");

    final JsonObject onBuyer = json("uri", buyer.toUri().toString());
    assertThat(command(LatheWorkspaceService.MISSING_IMPORTS_COMMAND, onBuyer))
        .asInstanceOf(InstanceOfAssertFactories.type(MissingImportsResult.class))
        .extracting(MissingImportsResult::items)
        .isEqualTo(List.of(new MissingImport("Shop", List.of("com.example.Shop"))));
    final var chosen = new JsonArray();
    chosen.add("com.example.Shop");
    onBuyer.add("imports", chosen);
    assertThat(command(LatheWorkspaceService.IMPORT_EDITS_COMMAND, onBuyer))
        .asInstanceOf(InstanceOfAssertFactories.list(TextEdit.class))
        .extracting(TextEdit::getNewText)
        .anyMatch(text -> text.contains("import com.example.Shop;"));
  }

  // A saved configuration is listed and runs by name; a name nothing saved is refused with the
  // reason. Nothing launches: the workspace has no runner, so a run says what blocks it.
  @Test
  void executeCommand_runConfigsAndRuns_savedListed_unknownRefused_runSaysWhatBlocksIt()
      throws Exception {
    openShop();

    final var saved =
        (RunConfigWriter.Saved)
            command(
                LatheWorkspaceService.SAVE_RUN_CONFIG_COMMAND,
                json(
                    "name",
                    "shop",
                    "moduleRel",
                    "module",
                    "kind",
                    "MAIN",
                    "mainClass",
                    "com.example.Use"));
    assertThat(saved.name()).isEqualTo("shop");
    assertThat(command(LatheWorkspaceService.LIST_RUN_CONFIGS_COMMAND))
        .asInstanceOf(InstanceOfAssertFactories.list(RunConfigInfo.class))
        .extracting(RunConfigInfo::name)
        .containsExactly("shop");

    for (final String unknown :
        List.of(
            LatheWorkspaceService.RUN_NAMED_COMMAND, LatheWorkspaceService.DEBUG_NAMED_COMMAND)) {
      assertInvalidParams(commandError(unknown, json("name", "nosuchconfig")), "nosuchconfig");
    }

    final var outcome =
        (LaunchOutcome)
            command(
                LatheWorkspaceService.RUN_MAIN_COMMAND,
                json("moduleRel", "module", "mainClass", "com.example.Use"));
    assertThat(outcome.launched()).isFalse();
    assertThat(outcome.blockedReasons()).containsExactly("no derived main-launch.json for module");
  }

  // A build-file edit prompts once; Later only dismisses it, and the next edit prompts again,
  // where the answer asks the client to sync.
  @Test
  void reconcileForVerify_buildFileEdited_promptsAndSyncsOnlyWhenAsked() throws Exception {
    final Path pom = Files.writeString(tmp.resolve("pom.xml"), "<project/>");
    TestCompiler.recordBuildFiles(tmp, List.of(pom));
    when(client.showMessageRequest(any()))
        .thenReturn(CompletableFuture.completedFuture(new MessageActionItem("Later")))
        .thenReturn(
            CompletableFuture.completedFuture(new MessageActionItem("Sync + capture tests")));
    openShop();

    Files.writeString(pom, "<project>edited</project>");
    service.reconcileForVerify().get(TIMEOUT_S, TimeUnit.SECONDS);
    awaitWorkerIdle();
    verify(client).showMessageRequest(argThat(request -> request.getMessage().startsWith("Build")));
    verify(client, never()).sync(any());

    Files.writeString(pom, "<project>edited again</project>");
    service.reconcileForVerify().get(TIMEOUT_S, TimeUnit.SECONDS);
    awaitWorkerIdle();
    verify(client).sync(new LatheSyncParams(tmp.toString(), true));
  }

  // A sync that rewrites workspace.json reloads it: a module the sync added is there at once.
  @Test
  void reconcileForVerify_workspaceJsonRewritten_reloadsWithTheNewModuleAndSaysSo()
      throws Exception {
    openShop();
    assertThat(command(LatheWorkspaceService.MODULES_COMMAND)).isEqualTo(List.of("module"));

    TestCompiler.writeMirroredModule(
        tmp,
        "lib",
        Map.of("com/lib/Lib.java", "package com.lib;\npublic class Lib {}\n"),
        List.of(),
        1_000L);
    service.reconcileForVerify().get(TIMEOUT_S, TimeUnit.SECONDS);

    assertThat(command(LatheWorkspaceService.MODULES_COMMAND)).isEqualTo(List.of("lib", "module"));
    verify(client).showMessage(new MessageParams(MessageType.Info, "Lathe: workspace reloaded."));
  }

  private Object command(final String name, final Object... arguments) throws Exception {
    return workspace
        .executeCommand(new ExecuteCommandParams(name, List.of(arguments)))
        .get(TIMEOUT_S, TimeUnit.SECONDS);
  }

  private ResponseError commandError(final String name, final Object argument) {
    Throwable cause = catchThrowable(() -> command(name, argument));
    while (cause != null && !(cause instanceof ResponseErrorException)) {
      cause = cause.getCause();
    }

    assertThat(cause).isInstanceOf(ResponseErrorException.class);
    return ((ResponseErrorException) cause).getResponseError();
  }

  // A JSON command argument from alternating keys and values.
  private static JsonObject json(final String... keysAndValues) {
    final var json = new JsonObject();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      json.addProperty(keysAndValues[i], keysAndValues[i + 1]);
    }

    return json;
  }

  // The client's make_position_params() for the first occurrence of token.
  private static JsonObject positionArgument(final Path file, final String token) {
    final Position at = positionOf(file, token);
    final var position = new JsonObject();
    position.addProperty("line", at.getLine());
    position.addProperty("character", at.getCharacter());
    final var argument = new JsonObject();
    argument.add("textDocument", json("uri", file.toUri().toString()));
    argument.add("position", position);
    return argument;
  }
}
