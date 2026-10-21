package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.server.analysis.SourceLocator.offsetToPosition;
import static org.mockito.Mockito.mock;

import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.server.analysis.SourceAnalysisSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;
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
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

// The service under test against a mocked client in a temp workspace, with the fixtures and
// helpers its test classes share.
abstract class ServiceTestBase {

  protected static final long DEBOUNCE_MS = 50;

  protected LanguageClient client;
  protected LatheTextDocumentService service;
  @TempDir protected Path tmp;

  @BeforeEach
  void setUp() {
    client = mock(LanguageClient.class);
    service = new LatheTextDocumentService(DEBOUNCE_MS);
    service.connect(client);
  }

  @AfterEach
  void close() {
    service.close();
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

  // Force a round-trip through the single worker thread: a request submitted after initialize's
  // load
  // task completes only once that task (and its startup reconciliation) has run.
  protected void awaitWorkerIdle() throws Exception {
    final var params = new FoldingRangeRequestParams();
    params.setTextDocument(new TextDocumentIdentifier("file:///nonexistent.java"));
    service.foldingRange(params).get(5, TimeUnit.SECONDS);
  }

  // A reconcile pass runs after initialize on the FIFO worker, so startup reconciliation is
  // finished
  // before the test mutates sources.
  protected void awaitStartup() throws Exception {
    service.reconcileNow(false).get(5, TimeUnit.SECONDS);
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

  // Collects log messages, so a test can count operations that leave no other observable trace.
  // The compile log while open: a test counts compile tasks here, as a stale result is dropped
  // unpublished and redundant compiles show nowhere else.
  // Hears every compile in the JVM: a test class using it must be @Isolated.
  protected static final class CompileLog extends Handler implements AutoCloseable {
    private static final Logger COMPILER = Logger.getLogger(SourceAnalysisSession.class.getName());
    private final Queue<String> messages = new ConcurrentLinkedQueue<>();

    CompileLog() {
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
      messages.add(record.getMessage());
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
