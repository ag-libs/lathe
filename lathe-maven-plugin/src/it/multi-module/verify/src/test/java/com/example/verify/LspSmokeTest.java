package com.example.verify;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LspSmokeTest {

  private static final Path ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Path LAUNCHER =
      Path.of(System.getProperty("lathe.cache"))
          .resolve("servers")
          .resolve(System.getProperty("lathe.version"))
          .resolve("lathe-launcher.sh");

  private static Process serverProcess;
  private static LanguageServer server;
  private static CapturingClient client;
  private static InitializeResult initResult;
  private static FileTime originalPomMtime;

  @BeforeAll
  static void startServer() throws Exception {
    originalPomMtime = Files.getLastModifiedTime(ROOT.resolve("pom.xml"));
    client = new CapturingClient();
    final var pb = new ProcessBuilder(LAUNCHER.toString());
    pb.environment().put("LATHE_DEBUG", "1");
    serverProcess = pb.start();

    final var stderrThread =
        new Thread(
            () -> {
              try (var reader =
                  new BufferedReader(new InputStreamReader(serverProcess.getErrorStream()))) {
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

    final Launcher<LanguageServer> launcher =
        LSPLauncher.createClientLauncher(
            client, serverProcess.getInputStream(), serverProcess.getOutputStream());
    launcher.startListening();
    server = launcher.getRemoteProxy();

    final var params = new InitializeParams();
    params.setRootUri(ROOT.toUri().toString());
    params.setCapabilities(new ClientCapabilities());
    initResult = server.initialize(params).get(10, SECONDS);
    server.initialized(new InitializedParams());

    final var reload = client.messages.poll(10, SECONDS);
    assertThat(reload)
        .as(
            "no showMessage received within 10s after initialized; server alive=%s",
            serverProcess.isAlive())
        .isNotNull();
    assertThat(reload.getMessage()).contains("workspace ready");
  }

  @AfterAll
  static void stopServer() {
    serverProcess.destroyForcibly();
  }

  @AfterEach
  void resetState() throws IOException {
    Files.setLastModifiedTime(ROOT.resolve("pom.xml"), originalPomMtime);
    client.messages.clear();
    client.syncPrompts.clear();
  }

  @Test
  void initialize_returnsExpectedCapabilities() {
    final var caps = initResult.getCapabilities();
    assertThat(caps.getCompletionProvider()).isNotNull();
    assertThat(caps.getHoverProvider()).isNotNull();
    assertThat(caps.getDefinitionProvider()).isNotNull();
    assertThat(caps.getReferencesProvider()).isNotNull();
    assertThat(caps.getDocumentFormattingProvider()).isNotNull();
    assertThat(caps.getSemanticTokensProvider()).isNotNull();
  }

  @Test
  void workspaceReload_notifiesUser() throws Exception {
    Files.setLastModifiedTime(
        ROOT.resolve(".lathe/workspace.json"), FileTime.from(Instant.now().plusSeconds(2)));

    final var msg = client.messages.poll(10, SECONDS);
    assertThat(msg).isNotNull();
    assertThat(msg.getMessage()).isEqualTo("Lathe: workspace reloaded.");
  }

  @Test
  void pomChange_triggersResyncPrompt() throws Exception {
    Files.setLastModifiedTime(
        ROOT.resolve("pom.xml"), FileTime.from(Instant.now().plusSeconds(5)));

    final var prompt = client.syncPrompts.poll(10, SECONDS);
    assertThat(prompt).isNotNull();
    assertThat(prompt.getMessage()).contains("process-test-classes");
    assertThat(prompt.getActions())
        .extracting(MessageActionItem::getTitle)
        .containsExactlyInAnyOrder("Sync", "Later");
  }

  static class CapturingClient implements LanguageClient {

    final BlockingQueue<MessageParams> messages = new LinkedBlockingQueue<>();
    final BlockingQueue<ShowMessageRequestParams> syncPrompts = new LinkedBlockingQueue<>();

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
  }
}
