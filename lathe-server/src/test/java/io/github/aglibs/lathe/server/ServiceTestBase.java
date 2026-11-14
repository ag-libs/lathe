package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.analysis.Positions.offsetToPosition;
import static org.mockito.Mockito.mock;

import io.github.aglibs.lathe.analysis.SourceAnalysisSession;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.format.FormatEngine;
import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

// The service under test against a mocked client in a temp workspace, with the fixtures and
// helpers its test classes share.
abstract class ServiceTestBase {

  protected static final long DEBOUNCE_MS = 50;
  // How long a test waits for the server's answer: a deadline for a busy machine, not a delay. A
  // shared CI runner can stall every test for seconds at once; a passing test never waits this out.
  protected static final long TIMEOUT_S = 30;
  protected static final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(TIMEOUT_S);
  private static final long WRITE_LOCK_POLL_MS = 20;
  // How long a test watches for a call that must not come, such as a duplicate publish: a fixed
  // wait, unlike TIMEOUT_MS, so as short as still catches a late one on a busy machine.
  protected static final long QUIET_WINDOW_MS = 1_500;

  protected LatheLanguageClient client;
  protected SessionHost host;
  protected LatheTextDocumentService service;
  @TempDir protected Path tmp;

  @BeforeEach
  void setUp() {
    client = mock(LatheLanguageClient.class);
    host = new SessionHost(DEBOUNCE_MS);
    host.connect(client);
    service = new LatheTextDocumentService(host);
  }

  @AfterEach
  void close() {
    host.close();
  }

  // One write pass, complete once its recompiles finish; eager writes what is still settling.
  protected CompletableFuture<Void> reconcileNow(final boolean eager) {
    return host.onLoopFuture(session -> session.writePass().reconcileNow(eager));
  }

  // The freshen step the engine runs before each tool: what recompiled, or why it was deferred.
  protected CompletableFuture<ReconcileOutcome> reconcileForVerify() {
    return host.onLoopFuture(WorkspaceSession::reconcileForVerify);
  }

  // Writes files the test changed on disk itself, complete once the mirror has them.
  protected CompletableFuture<Void> writeReported(final Set<Path> changed) {
    return host.onLoopFuture(session -> session.writePass().writeReported(changed));
  }

  protected void useFormatter(final FormatEngine engine) {
    host.execute(session -> session.formats().use(engine, null));
  }

  protected void change(final Path file, final String content, final int version) {
    service.didChange(
        new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier(file.toUri().toString(), version),
            List.of(new TextDocumentContentChangeEvent(content))));
  }

  protected void save(final Path file) {
    service.didSave(
        new DidSaveTextDocumentParams(
            new TextDocumentIdentifier(file.toUri().toString()),
            IOUtil.unchecked(() -> Files.readString(file))));
  }

  // The javac tasks in a compile log, as the number of files each covered: a single-file compile
  // logs a URI, a batch logs "N files". Counting kinds of work, not the path that ran it, keeps
  // these tests valid however the server schedules the work.
  private static final Pattern COMPILE_TASK =
      Pattern.compile("^\\[compile:([\\w-]+)\\] (?:(\\d+) files)?");

  protected static List<Integer> writes(final CompileLog compileLog) {
    return tasks(compileLog, Set.of("full", "batch"));
  }

  protected static List<Integer> analyses(final CompileLog compileLog) {
    return tasks(compileLog, Set.of("open", "fast", "open-batch"));
  }

  protected static List<Integer> tasks(final CompileLog compileLog, final Set<String> kinds) {
    return compileLog.messages().stream()
        .map(COMPILE_TASK::matcher)
        .filter(Matcher::find)
        .filter(task -> kinds.contains(task.group(1)))
        .map(task -> task.group(2) == null ? 1 : Integer.parseInt(task.group(2)))
        .toList();
  }

  // The event loop and every module worker idle: what was queued has run, compiles included, and
  // their diagnostics are published. A loop round-trip alone left a compile still running on its
  // module's thread, whose publish then landed after the test's next step.
  protected void awaitWorkerIdle() throws Exception {
    host.onLoopFuture(WorkspaceSession::workersDrained).get(TIMEOUT_S, TimeUnit.SECONDS);
  }

  // .lathe/'s write lock, held as another server would. The server's own write pass, a save's or
  // its change scan's, may hold it a moment, so a single try raced with it: wait for it to let go.
  protected Closeable holdWriteLock() throws Exception {
    final Path lock = LatheLayout.writeLockFile(tmp.resolve(LatheLayout.LATHE_DIR));
    final var waited = Stopwatch.start();
    while (true) {
      final Optional<Closeable> held = LatheLock.tryExclusive(lock);
      if (held.isPresent()) {
        return held.get();
      }

      if (waited.elapsedMs() > TIMEOUT_MS) {
        throw new AssertionError(
            "the server still holds %s after %d ms".formatted(lock, TIMEOUT_MS));
      }

      IOUtil.sleep(WRITE_LOCK_POLL_MS);
    }
  }

  // A reconcile pass runs after initialize on the event loop, so startup reconciliation is finished
  // before the test mutates sources.
  protected void awaitStartup() throws Exception {
    reconcileNow(false).get(TIMEOUT_S, TimeUnit.SECONDS);
  }

  // Dep plus userCount callers of Dep.v(), compiled into the mirror and stamped, so the workspace
  // starts fresh and the callers are exactly what a change to Dep must refresh.
  protected DepFixture depWithUsers(final int userCount) throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put(
        "com/example/Dep.java",
        "package com.example;\npublic class Dep { public int v() { return 1; } }\n");
    for (int i = 0; i < userCount; i++) {
      sources.put(
          "com/example/User%d.java".formatted(i),
          "package com.example;\nclass User%d { int u() { return new Dep().v(); } }\n"
              .formatted(i));
    }

    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    return new DepFixture(files.get(0), files.subList(1, files.size()));
  }

  // Mirrored baseline: sealed Shape permits only Circle, both compiled and stamped; returns Shape.
  protected Path mirroredShapes() throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put("shapes/Shape.java", "package shapes; sealed interface Shape permits Circle {}");
    sources.put("shapes/Circle.java", "package shapes; final class Circle implements Shape {}");
    return TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L).get(0);
  }

  // Api.sell implemented by Shop, which Use creates and calls twice; compiled into the mirror and
  // stamped, so the workspace starts fresh. Use is open once the server is up.
  protected ShopFixture openShop() throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put(
        "com/example/Api.java",
        "package com.example;\npublic interface Api {\n  int sell(int count);\n}\n");
    sources.put(
        "com/example/Shop.java",
        """
        package com.example;
        public class Shop implements Api {
          public int sell(int count) {
            return count;
          }
        }
        """);
    sources.put(
        "com/example/Use.java",
        """
        package com.example;
        class Use {
          int run() {
            final Shop shop = new Shop();
            return shop.sell(1) + shop.sell(2);
          }
        }
        """);
    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    host.initialize(tmp);
    awaitStartup();
    final var fixture = new ShopFixture(files.get(0), files.get(1), files.get(2));
    openDoc(fixture.use(), Files.readString(fixture.use()));
    awaitWorkerIdle();
    return fixture;
  }

  protected record ShopFixture(Path api, Path shop, Path use) {}

  // The position of the first occurrence of token in the file, as the client sends it.
  protected static Position positionOf(final Path file, final String token) {
    final String content = IOUtil.unchecked(() -> Files.readString(file));
    return offsetToPosition(content, content.indexOf(token));
  }

  protected static TextDocumentIdentifier documentOf(final Path file) {
    return new TextDocumentIdentifier(file.toUri().toString());
  }

  protected record DepFixture(Path dep, List<Path> users) {
    protected DepFixture {
      users = List.copyOf(users);
    }
  }

  protected static boolean isFor(final PublishDiagnosticsParams params, final Path file) {
    return params.getUri().equals(file.toUri().toString());
  }

  protected static boolean hasMessage(final PublishDiagnosticsParams params, final String message) {
    return params.getDiagnostics().stream()
        .anyMatch(diagnostic -> diagnostic.getMessage().getLeft().contains(message));
  }

  // The compile log of one workspace while open: a test counts compile tasks here, as a stale
  // result is dropped unpublished and redundant compiles show nowhere else. The logger hears every
  // compile in the JVM; each compile line names a file it compiled, so keeping the lines under the
  // workspace lets test classes compile in parallel.
  protected static final class CompileLog extends Handler implements AutoCloseable {
    private static final Logger COMPILER = Logger.getLogger(SourceAnalysisSession.class.getName());
    private final Queue<String> messages = new ConcurrentLinkedQueue<>();
    private final String workspaceUri;

    CompileLog(final Path workspace) {
      final String uri = LatheUri.canonical(workspace.toUri().toString());
      workspaceUri = uri.endsWith("/") ? uri : uri + "/";
      COMPILER.addHandler(this);
    }

    List<String> messages() {
      return List.copyOf(messages);
    }

    void clear() {
      messages.clear();
    }

    @Override
    public void publish(final LogRecord record) {
      if (record.getMessage().contains(workspaceUri)) {
        messages.add(record.getMessage());
      }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {
      COMPILER.removeHandler(this);
    }
  }

  protected static ReferenceParams referenceParams(
      final Path source,
      final String content,
      final int offset,
      final Either<String, Integer> token) {
    final var params = new ReferenceParams();
    params.setTextDocument(new TextDocumentIdentifier(source.toUri().toString()));
    params.setPosition(offsetToPosition(content, offset));
    params.setContext(new ReferenceContext(true));
    params.setWorkDoneToken(token);
    return params;
  }

  protected void openDoc(final Path file, final String content) {
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(file.toUri().toString(), "java", 1, content)));
  }
}
