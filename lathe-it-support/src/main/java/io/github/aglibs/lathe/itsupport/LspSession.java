package io.github.aglibs.lathe.itsupport;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

// The real server, started from the launcher the build under test installed (in the lathe.cache
// directory, at lathe.version), as an editor starts it, and the client end of its LSP connection.
public final class LspSession {

  // A Maven invoker fixture's workspace: its checks run in a module of it.
  public static final Path ROOT = Path.of(System.getProperty("user.dir")).getParent();

  public final LanguageServer server;
  public final CapturingClient client;
  public final InitializeResult initResult;
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

  public static LspSession start(final Path root, final ClientCapabilities capabilities)
      throws Exception {
    final var client = new CapturingClient();
    final var pb = new ProcessBuilder(Os.launcher("lathe-launcher").toString());
    // Editors start the launcher from the workspace root, where it finds .lathe/jvm.args.
    pb.directory(root.toFile());
    pb.environment().put("LATHE_DEBUG", "1");
    // Scans fast so the tests that wait for an outside change (manifest, pom.xml) need not wait
    // 2 s.
    pb.environment().put("LATHE_JVM_OPTS", "-Dlathe.scanIntervalMs=200");
    final Process process = pb.start();
    forwardStderr(process, "server");

    final Launcher<LanguageServer> launcher =
        LSPLauncher.createClientLauncher(
            client, process.getInputStream(), process.getOutputStream());
    launcher.startListening();
    final LanguageServer server = launcher.getRemoteProxy();

    final var params = new InitializeParams();
    params.setRootUri(root.toUri().toString());
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

  public void stop() {
    Os.stop(process);
  }

  public void openDoc(final String uri, final String content) {
    final var item = new TextDocumentItem();
    item.setUri(uri);
    item.setLanguageId("java");
    item.setVersion(1);
    item.setText(content);
    final var params = new DidOpenTextDocumentParams();
    params.setTextDocument(item);
    server.getTextDocumentService().didOpen(params);
  }

  // As an editor saves: the file is written to disk, then the server is told.
  public void saveDoc(final String uri, final String content) throws IOException {
    Files.writeString(Path.of(URI.create(uri)), content);
    final var params = new DidSaveTextDocumentParams();
    params.setTextDocument(new TextDocumentIdentifier(uri));
    params.setText(content);
    server.getTextDocumentService().didSave(params);
  }

  // The next diagnostics published for the file. The server canonicalizes a URI (macOS resolves
  // /var to /private/var), so the file is compared, not the URI the test sent.
  public List<Diagnostic> nextDiagnostics(final String uri) throws Exception {
    while (true) {
      final PublishDiagnosticsParams published = client.diagnostics.poll(60, SECONDS);
      assertThat(published).as("diagnostics for %s within 60 s", uri).isNotNull();
      if (sameFile(published.getUri(), uri)) {
        return published.getDiagnostics();
      }
    }
  }

  // The file's diagnostics once they satisfy done: a file may be published more than once as its
  // module compiles, and a test waits for the answer it expects, not the first.
  public List<Diagnostic> awaitDiagnostics(final String uri, final Predicate<List<Diagnostic>> done)
      throws Exception {
    final Instant deadline = Instant.now().plusSeconds(60);
    List<Diagnostic> last = nextDiagnostics(uri);
    while (!done.test(last)) {
      assertThat(Instant.now())
          .as("diagnostics for %s as expected within 60 s; last: %s", uri, last)
          .isBefore(deadline);
      last = nextDiagnostics(uri);
    }

    return last;
  }

  // A diagnostic's text, whether the server sent it plain or as markup.
  public static String message(final Diagnostic diagnostic) {
    return diagnostic.getMessage().isLeft()
        ? diagnostic.getMessage().getLeft()
        : diagnostic.getMessage().getRight().getValue();
  }

  private static boolean sameFile(final String uri, final String other) throws IOException {
    final Path path = Path.of(URI.create(uri));
    final Path otherPath = Path.of(URI.create(other));
    return Files.exists(path) && Files.exists(otherPath) && Files.isSameFile(path, otherPath);
  }

  // The position of token on the first line containing linePattern, at or after the pattern.
  public static Position findToken(
      final String content, final String linePattern, final String token) {
    final String[] lines = content.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      final int patternIdx = lines[i].indexOf(linePattern);
      if (patternIdx >= 0) {
        return new Position(i, lines[i].indexOf(token, patternIdx));
      }
    }
    throw new AssertionError("pattern not found: " + linePattern);
  }

  // lsp4j has no type to bind Lathe's own results to, so they arrive as plain maps and lists; one
  // JSON tree is easier to read in a test.
  public JsonElement execute(final String command, final Object argument) throws Exception {
    final var params = new ExecuteCommandParams(command, List.of(argument));
    return new Gson()
        .toJsonTree(server.getWorkspaceService().executeCommand(params).get(120, SECONDS));
  }

  // A started server's stderr, line by line onto the test's output, tagged with the server's kind.
  static void forwardStderr(final Process process, final String tag) {
    final var stderrThread =
        new Thread(
            () -> {
              try (var reader =
                  new BufferedReader(
                      new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  System.out.println("[%s] %s".formatted(tag, line));
                }
              } catch (final IOException ignored) {
              }
            },
            "%s-stderr".formatted(tag));
    stderrThread.setDaemon(true);
    stderrThread.start();
  }

  public static class CapturingClient implements LanguageClient {

    public final BlockingQueue<MessageParams> messages = new LinkedBlockingQueue<>();
    public final BlockingQueue<ShowMessageRequestParams> syncPrompts = new LinkedBlockingQueue<>();
    public final BlockingQueue<PublishDiagnosticsParams> diagnostics = new LinkedBlockingQueue<>();
    public final List<JsonObject> testEvents = new CopyOnWriteArrayList<>();
    public final List<JsonObject> testOutput = new CopyOnWriteArrayList<>();

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
    public void publishDiagnostics(final PublishDiagnosticsParams params) {
      diagnostics.add(params);
    }

    @Override
    public void telemetryEvent(final Object object) {}

    // The server asks for this after a compile changes a file's highlighting; lsp4j's default
    // throws, which logged a SEVERE error each time in the fixture builds.
    @Override
    public CompletableFuture<Void> refreshSemanticTokens() {
      return CompletableFuture.completedFuture(null);
    }

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
