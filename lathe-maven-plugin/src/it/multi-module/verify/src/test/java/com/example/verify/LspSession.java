package com.example.verify;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

// The real server, started from the fixture's launcher as an editor starts it, and the client end
// of its LSP connection.
final class LspSession {

  static final Path ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Path LAUNCHER =
      Path.of(System.getProperty("lathe.cache"))
          .resolve("servers")
          .resolve(System.getProperty("lathe.version"))
          .resolve(Os.script("lathe-launcher"));

  final LanguageServer server;
  final CapturingClient client;
  final InitializeResult initResult;
  private final Process process;

  private LspSession(
      final Process process,
      final LanguageServer server,
      final CapturingClient client,
      final InitializeResult initResult) {
    this.process = process;
    this.server = server;
    this.client = client;
    this.initResult = initResult;
  }

  static LspSession start(final ClientCapabilities capabilities) throws Exception {
    final var client = new CapturingClient();
    final var pb = new ProcessBuilder(LAUNCHER.toString());
    // Editors start the launcher from the workspace root, where it finds .lathe/jvm.args.
    pb.directory(ROOT.toFile());
    pb.environment().put("LATHE_DEBUG", "1");
    // Scans fast so the tests that wait for an outside change (manifest, pom.xml) need not wait
    // 2 s.
    pb.environment().put("LATHE_JVM_OPTS", "-Dlathe.scanIntervalMs=200");
    final Process process = pb.start();
    forwardStderr(process);

    final Launcher<LanguageServer> launcher =
        LSPLauncher.createClientLauncher(
            client, process.getInputStream(), process.getOutputStream());
    launcher.startListening();
    final LanguageServer server = launcher.getRemoteProxy();

    final var params = new InitializeParams();
    params.setRootUri(ROOT.toUri().toString());
    params.setCapabilities(capabilities);
    final InitializeResult initResult = server.initialize(params).get(10, SECONDS);
    server.initialized(new InitializedParams());

    final var ready = client.messages.poll(10, SECONDS);
    assertThat(ready)
        .as(
            "no showMessage received within 10s after initialized; server alive=%s",
            process.isAlive())
        .isNotNull();
    assertThat(ready.getMessage()).contains("workspace ready");
    return new LspSession(process, server, client, initResult);
  }

  void stop() {
    Os.stop(process);
  }

  void openDoc(final String uri, final String content) {
    final var item = new TextDocumentItem();
    item.setUri(uri);
    item.setLanguageId("java");
    item.setVersion(1);
    item.setText(content);
    final var params = new DidOpenTextDocumentParams();
    params.setTextDocument(item);
    server.getTextDocumentService().didOpen(params);
  }

  // lsp4j has no type to bind Lathe's own results to, so they arrive as plain maps and lists; one
  // JSON tree is easier to read in a test.
  JsonElement execute(final String command, final Object argument) throws Exception {
    final var params = new ExecuteCommandParams(command, List.of(argument));
    return new Gson()
        .toJsonTree(server.getWorkspaceService().executeCommand(params).get(120, SECONDS));
  }

  private static void forwardStderr(final Process process) {
    final var stderrThread =
        new Thread(
            () -> {
              try (var reader =
                  new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  System.out.println("[server] %s".formatted(line));
                }
              } catch (final IOException ignored) {
              }
            },
            "server-stderr");
    stderrThread.setDaemon(true);
    stderrThread.start();
  }

  static class CapturingClient implements LanguageClient {

    final BlockingQueue<MessageParams> messages = new LinkedBlockingQueue<>();
    final BlockingQueue<ShowMessageRequestParams> syncPrompts = new LinkedBlockingQueue<>();
    final List<JsonObject> testEvents = new CopyOnWriteArrayList<>();
    final List<JsonObject> testOutput = new CopyOnWriteArrayList<>();

    @Override
    public void showMessage(final MessageParams params) {
      System.out.println("[showMessage] %s: %s".formatted(params.getType(), params.getMessage()));
      messages.add(params);
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(
        final ShowMessageRequestParams params) {
      syncPrompts.add(params);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(final MessageParams params) {
      System.out.println("[logMessage] %s: %s".formatted(params.getType(), params.getMessage()));
    }

    @Override
    public void publishDiagnostics(final PublishDiagnosticsParams params) {}

    @Override
    public void telemetryEvent(final Object object) {}

    @JsonNotification("lathe/testEvent")
    public void testEvent(final JsonObject params) {
      testEvents.add(params);
    }

    @JsonNotification("lathe/testOutput")
    public void testOutput(final JsonObject params) {
      testOutput.add(params);
    }
  }
}
