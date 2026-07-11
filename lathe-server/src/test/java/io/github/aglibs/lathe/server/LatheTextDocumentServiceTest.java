package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.server.analysis.SourceLocator.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import io.github.aglibs.lathe.server.analysis.TypeHierarchyItemData;
import io.github.aglibs.lathe.server.analysis.TypeHierarchyItemDataCodec;
import io.github.aglibs.lathe.server.analysis.completion.CompletionOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchySubtypesParams;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkDoneProgressCancelParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class LatheTextDocumentServiceTest {

  private static final String URI = "file:///workspace/src/main/java/Foo.java";
  private static final long DEBOUNCE_MS = 50;

  private LanguageClient client;
  private LatheTextDocumentService service;
  @TempDir private Path tmp;

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

  @Test
  void didChange_rapidKeystrokes_compilesOnlyOnce() {
    for (int i = 0; i < 5; i++) {
      service.didChange(changeParams("content-" + i));
    }

    verify(client, timeout(DEBOUNCE_MS * 5).atLeastOnce())
        .publishDiagnostics(argThat(p -> p.getUri().equals(URI) && !p.getDiagnostics().isEmpty()));
  }

  @Test
  void didChange_rapidKeystrokes_compilesLatestContent() {
    for (int i = 0; i < 5; i++) {
      service.didChange(changeParams("content-" + i));
    }

    verify(client, timeout(DEBOUNCE_MS * 5).atLeastOnce())
        .publishDiagnostics(
            argThat(
                p ->
                    !p.getDiagnostics().isEmpty()
                        && p.getDiagnostics()
                            .getFirst()
                            .getMessage()
                            .getLeft()
                            .contains("mvn process-test-classes")));
  }

  @Test
  void didOpen_compilesImmediatelyWithoutDebounce() {
    service.didOpen(
        new DidOpenTextDocumentParams(new TextDocumentItem(URI, "java", 1, "class Foo {}")));

    final var captor = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
    verify(client, timeout(DEBOUNCE_MS * 3)).publishDiagnostics(captor.capture());
    assertThat(captor.getValue().getUri()).isEqualTo(URI);
  }

  @Test
  void didClose_publishesEmptyDiagnostics() {
    service.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(URI)));

    final var captor = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
    verify(client, timeout(DEBOUNCE_MS * 3)).publishDiagnostics(captor.capture());
    assertThat(captor.getValue().getUri()).isEqualTo(URI);
    assertThat(captor.getValue().getDiagnostics()).isEmpty();
  }

  @Test
  void references_workspaceFile_reportsProgressAndLocations() throws Exception {
    final Path source = writeWorkspaceSource();
    final var content = Files.readString(source);
    final var token = Either.<String, Integer>forLeft("refs-token");
    service.setWorkDoneProgressSupported(true);
    service.initialize(tmp);
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(source.toUri().toString(), "java", 1, content)));

    final List<? extends Location> locations =
        service
            .references(referenceParams(source, content, content.indexOf("target"), token))
            .get(5, TimeUnit.SECONDS);

    final var progressCaptor = ArgumentCaptor.forClass(ProgressParams.class);
    verify(client, timeout(5_000).atLeast(2)).notifyProgress(progressCaptor.capture());
    assertThat(locations).hasSize(2);
    assertThat(progressCaptor.getAllValues()).extracting(ProgressParams::getToken).contains(token);
    assertThat(progressCaptor.getAllValues().getLast().getValue().getLeft())
        .isInstanceOf(WorkDoneProgressEnd.class);
  }

  @Test
  void references_progressCancelled_cancelsResponseAndKeepsServiceUsable() throws Exception {
    final Path source = writeWorkspaceSource();
    final var content = Files.readString(source);
    final var token = Either.<String, Integer>forLeft("refs-token");
    service.setWorkDoneProgressSupported(true);
    service.initialize(tmp);
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(source.toUri().toString(), "java", 1, content)));

    final var response =
        service.references(referenceParams(source, content, content.indexOf("target"), token));
    service.cancelProgress(new WorkDoneProgressCancelParams(token));

    assertThat(response).isCancelled();
    assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
        .isInstanceOf(CancellationException.class);
    assertThatCode(
            () ->
                service
                    .hover(
                        new HoverParams(
                            new TextDocumentIdentifier(source.toUri().toString()),
                            new Position(0, 6)))
                    .get(5, TimeUnit.SECONDS))
        .doesNotThrowAnyException();
  }

  @Test
  void completionResult_incompleteOutcome_returnsIncompleteCompletionList() {
    final var item = new CompletionItem("FooService");
    final var result =
        LatheTextDocumentService.completionResult(new CompletionOutcome(List.of(item), null, true));

    assertThat(result.isRight()).isTrue();
    assertThat(result.getRight().isIncomplete()).isTrue();
    assertThat(result.getRight().getItems()).containsExactly(item);
  }

  @Test
  void documentSymbolResult_symbols_returnsDocumentSymbolEitherValues() {
    final var symbol = new DocumentSymbol();
    symbol.setName("Foo");

    final var result = LatheTextDocumentService.documentSymbolResult(List.of(symbol));

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().isRight()).isTrue();
    assertThat(result.getFirst().getRight()).isSameAs(symbol);
  }

  @Test
  void prepareCallHierarchy_onMethodDeclaration_returnsItem() throws Exception {
    final Path source = writeWorkspaceSource();
    final var content = Files.readString(source);
    service.initialize(tmp);
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(source.toUri().toString(), "java", 1, content)));

    final var params = new CallHierarchyPrepareParams();
    params.setTextDocument(new TextDocumentIdentifier(source.toUri().toString()));
    params.setPosition(offsetToPosition(content, content.indexOf("target")));

    final List<CallHierarchyItem> items =
        service.prepareCallHierarchy(params).get(5, TimeUnit.SECONDS);

    assertThat(items).hasSize(1);
    assertThat(items.getFirst().getName()).isEqualTo("target");
  }

  @Test
  void prepareCallHierarchy_notOnMethod_returnsEmpty() throws Exception {
    final Path source = writeWorkspaceSource();
    final var content = Files.readString(source);
    service.initialize(tmp);
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(source.toUri().toString(), "java", 1, content)));

    final var params = new CallHierarchyPrepareParams();
    params.setTextDocument(new TextDocumentIdentifier(source.toUri().toString()));
    params.setPosition(offsetToPosition(content, content.indexOf("Foo")));

    final List<CallHierarchyItem> items =
        service.prepareCallHierarchy(params).get(5, TimeUnit.SECONDS);

    assertThat(items).isEmpty();
  }

  @Test
  void outgoingCalls_fromPreparedItem_returnsCallees() throws Exception {
    final Path sourceRoot = tmp.resolve("module/src/main/java");
    Files.createDirectories(sourceRoot);
    Files.writeString(sourceRoot.resolve("Callee.java"), "class Callee { void run() {} }");
    final Path callerFile = sourceRoot.resolve("Caller.java");
    final String callerContent =
        """
        class Caller { void invoke(Callee c) { c.run(); } }
        class Callee { void run() {} }
        """;
    Files.writeString(callerFile, callerContent);
    TestCompiler.writeModuleParams(tmp, "module", sourceRoot, null);
    service.initialize(tmp);
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(callerFile.toUri().toString(), "java", 1, callerContent)));

    final var prepParams = new CallHierarchyPrepareParams();
    prepParams.setTextDocument(new TextDocumentIdentifier(callerFile.toUri().toString()));
    prepParams.setPosition(offsetToPosition(callerContent, callerContent.indexOf("invoke")));
    final List<CallHierarchyItem> items =
        service.prepareCallHierarchy(prepParams).get(5, TimeUnit.SECONDS);
    assertThat(items).hasSize(1);

    final List<CallHierarchyOutgoingCall> calls =
        service
            .callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(items.getFirst()))
            .get(5, TimeUnit.SECONDS);

    assertThat(calls).hasSize(1);
    assertThat(calls.getFirst().getTo().getName()).isEqualTo("run");
  }

  @Test
  void incomingCalls_fromPreparedItem_returnsCallerAndRanges() throws Exception {
    final Path sourceRoot = tmp.resolve("module/src/main/java");
    final Path source = sourceRoot.resolve("com/example/Foo.java");
    Files.createDirectories(source.getParent());
    final String content =
        "package com.example; class Foo { private void target() {} void caller() { target(); } }";
    Files.writeString(source, content);
    TestCompiler.writeModuleParams(tmp, "module", sourceRoot, null);
    service.initialize(tmp);
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(source.toUri().toString(), "java", 1, content)));

    final var prepParams = new CallHierarchyPrepareParams();
    prepParams.setTextDocument(new TextDocumentIdentifier(source.toUri().toString()));
    prepParams.setPosition(offsetToPosition(content, content.indexOf("target")));
    final List<CallHierarchyItem> items =
        service.prepareCallHierarchy(prepParams).get(5, TimeUnit.SECONDS);
    assertThat(items).hasSize(1);

    final List<CallHierarchyIncomingCall> calls =
        service
            .callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(items.getFirst()))
            .get(5, TimeUnit.SECONDS);

    assertThat(calls).hasSize(1);
    assertThat(calls.getFirst().getFrom().getName()).isEqualTo("caller");
  }

  @Test
  void runTestFuture_freshWorkspace_returnsBlockedOnMissingRunnerJar() throws Exception {
    service.initialize(tmp);

    final var outcome =
        service
            .runTestFuture("app", new TestSelection(TestSelectionKind.CLASS, "com.example.Foo"))
            .get(5, TimeUnit.SECONDS);

    assertThat(outcome.launched()).isFalse();
    assertThat(outcome.blockedReasons()).anyMatch(reason -> reason.contains("run a build first"));
  }

  private static DidChangeTextDocumentParams changeParams(final String text) {
    final var id = new VersionedTextDocumentIdentifier(URI, 1);
    final var change = new TextDocumentContentChangeEvent();
    change.setText(text);
    return new DidChangeTextDocumentParams(id, List.of(change));
  }

  @Test
  void typeHierarchySubtypes_declarationFileNotOpen_stillResolvesSubtypes() throws Exception {
    // EG-043: type-hierarchy relations must not require the type's declaration file to be open.
    final Path sourceRoot = tmp.resolve("module/src/main/java");
    final Path pkg = sourceRoot.resolve("com/example");
    Files.createDirectories(pkg);
    final Path api = pkg.resolve("Api.java");
    final Path impl = pkg.resolve("Impl.java");
    Files.writeString(api, "package com.example; public interface Api {}\n");
    Files.writeString(impl, "package com.example; public class Impl implements Api {}\n");
    // Compile both into the module's reactor classes dir so the reactor scan indexes Impl <: Api.
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), api, impl);
    TestCompiler.writeModuleParams(tmp, "module", sourceRoot, null);
    service.initialize(tmp);

    // Item for Api whose routingUri is Api.java — which is never opened (only usages would be).
    final var data = new TypeHierarchyItemData("com.example.Api", api.toUri().toString());
    final var range = new Range(new Position(0, 0), new Position(0, 0));
    final var item =
        new TypeHierarchyItem("Api", SymbolKind.Interface, api.toUri().toString(), range, range);
    item.setData(TypeHierarchyItemDataCodec.encode(data));

    final List<TypeHierarchyItem> subtypes =
        service
            .typeHierarchySubtypes(new TypeHierarchySubtypesParams(item))
            .get(5, TimeUnit.SECONDS);

    assertThat(subtypes).extracting(TypeHierarchyItem::getName).contains("Impl");
  }

  private Path writeWorkspaceSource() throws Exception {
    final Path sourceRoot = tmp.resolve("module/src/main/java");
    final Path source = sourceRoot.resolve("com/example/Foo.java");
    Files.createDirectories(source.getParent());
    Files.writeString(
        source, "package com.example; class Foo { private void target() { target(); } }");
    TestCompiler.writeModuleParams(tmp, "module", sourceRoot, null);
    return source;
  }

  private static ReferenceParams referenceParams(
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
}
