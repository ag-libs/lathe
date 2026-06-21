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
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchyPrepareParams;
import org.eclipse.lsp4j.TypeHierarchySubtypesParams;
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
    assertThat(caps.getImplementationProvider()).isNotNull();
    assertThat(caps.getTypeHierarchyProvider()).isNotNull();
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
  void references_fromReactorSource_findsUsageAcrossModules() throws Exception {
    final Path stringUtilsJava =
        ROOT.resolve("core/src/main/java/com/example/core/StringUtils.java");
    final String stringUtilsUri = stringUtilsJava.toUri().toString();
    final String stringUtilsContent = Files.readString(stringUtilsJava);
    openDoc(stringUtilsUri, stringUtilsContent);

    final var params = new ReferenceParams();
    params.setTextDocument(new TextDocumentIdentifier(stringUtilsUri));
    params.setPosition(findToken(stringUtilsContent, "public static String upper", "upper"));
    params.setContext(new ReferenceContext(false));

    final List<? extends Location> refs =
        server.getTextDocumentService().references(params).get(30, SECONDS);

    assertThat(refs).anyMatch(loc -> loc.getUri().contains("Main.java"));
  }

  @Test
  void references_fromCachedJdkSource_findsReactorUsages() throws Exception {
    // FR-001: cursorConfig is empty for external sources → configs = List.of() → no search
    final Path stringJava =
        Files.find(
                Path.of(System.getProperty("lathe.cache")).resolve("jdks"),
                6,
                (p, a) ->
                    p.getParent().endsWith(Path.of("java.base", "java", "lang"))
                        && p.getFileName().toString().equals("String.java"))
            .findFirst()
            .orElseThrow();

    final String stringUri = stringJava.toUri().toString();
    final String stringContent = Files.readString(stringJava);
    openDoc(stringUri, stringContent);

    final var params = new ReferenceParams();
    params.setTextDocument(new TextDocumentIdentifier(stringUri));
    params.setPosition(findToken(stringContent, "public final class String", "String"));
    params.setContext(new ReferenceContext(false));

    final List<? extends Location> refs =
        server.getTextDocumentService().references(params).get(30, SECONDS);

    assertThat(refs).anyMatch(loc -> loc.getUri().contains("StringUtils.java"));
  }

  @Test
  void implementation_typeCursor_findsImplementationsAcrossModules() throws Exception {
    final Path greeterJava =
        ROOT.resolve("core/src/main/java/com/example/core/Greeter.java");
    final String greeterUri = greeterJava.toUri().toString();
    final String greeterContent = Files.readString(greeterJava);
    openDoc(greeterUri, greeterContent);

    final var params = new ImplementationParams();
    params.setTextDocument(new TextDocumentIdentifier(greeterUri));
    params.setPosition(findToken(greeterContent, "public interface Greeter", "Greeter"));

    final List<? extends Location> impls =
        server.getTextDocumentService().implementation(params).get(30, SECONDS).getLeft();

    assertThat(impls)
        .anyMatch(loc -> loc.getUri().contains("FormalGreeter.java"))
        .anyMatch(loc -> loc.getUri().contains("CasualGreeter.java"));
  }

  @Test
  void typeHierarchy_subtypes_returnsImplementorsAcrossModules() throws Exception {
    final Path greeterJava =
        ROOT.resolve("core/src/main/java/com/example/core/Greeter.java");
    final String greeterUri = greeterJava.toUri().toString();
    final String greeterContent = Files.readString(greeterJava);
    openDoc(greeterUri, greeterContent);

    final var prepareParams = new TypeHierarchyPrepareParams();
    prepareParams.setTextDocument(new TextDocumentIdentifier(greeterUri));
    prepareParams.setPosition(findToken(greeterContent, "public interface Greeter", "Greeter"));

    final List<TypeHierarchyItem> items =
        server.getTextDocumentService().prepareTypeHierarchy(prepareParams).get(30, SECONDS);

    assertThat(items).hasSize(1);
    assertThat(items.getFirst().getName()).isEqualTo("Greeter");

    final var subtypesParams = new TypeHierarchySubtypesParams();
    subtypesParams.setItem(items.getFirst());

    final List<TypeHierarchyItem> subtypes =
        server.getTextDocumentService().typeHierarchySubtypes(subtypesParams).get(30, SECONDS);

    assertThat(subtypes)
        .extracting(TypeHierarchyItem::getName)
        .containsExactlyInAnyOrder("FormalGreeter", "CasualGreeter");
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

  private static void openDoc(final String uri, final String content) {
    final var item = new TextDocumentItem();
    item.setUri(uri);
    item.setLanguageId("java");
    item.setVersion(1);
    item.setText(content);
    final var params = new DidOpenTextDocumentParams();
    params.setTextDocument(item);
    server.getTextDocumentService().didOpen(params);
  }

  private static Position findToken(
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
