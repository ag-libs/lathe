package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.server.analysis.SourceLocator.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.ResourceRootData;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.server.analysis.RenameProvider;
import io.github.aglibs.lathe.server.analysis.SourceAnalysisSession;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import io.github.aglibs.lathe.server.analysis.TypeHierarchyItemData;
import io.github.aglibs.lathe.server.analysis.TypeHierarchyItemDataCodec;
import io.github.aglibs.lathe.server.analysis.completion.CompletionOutcome;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchySubtypesParams;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCancelParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkspaceEdit;
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
                            .contains("not under any Lathe module source root")));
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
  void didOpen_nonFileUri_ignoredWithoutCrashOrPublish() {
    // Neovim sends file:// for an unnamed [No Name] buffer (e.g. :LatheStart before a file is
    // open);
    // it has no source to analyze, so the event is ignored rather than crashing in LatheUri.toPath.
    assertThatCode(
            () ->
                service.didOpen(
                    new DidOpenTextDocumentParams(
                        new TextDocumentItem("file://", "java", 1, "class Foo {}"))))
        .doesNotThrowAnyException();

    verify(client, after(DEBOUNCE_MS * 4).never()).publishDiagnostics(any());
  }

  @Test
  void foldingRange_nonFileUri_returnsEmptyWithoutThrowing() throws Exception {
    // A virtual-scheme buffer (e.g. Neovim diffview) whose didOpen we drop can still receive
    // requests; the request handler must short-circuit before routing into LatheUri.toPath, which
    // throws FileSystemNotFoundException for a non-file scheme (EG-050).
    service.initialize(tmp);
    final var params = new FoldingRangeRequestParams();
    params.setTextDocument(new TextDocumentIdentifier("diffview:///workspace/.git/:0:/Foo.java"));

    final List<FoldingRange> ranges = service.foldingRange(params).get(5, TimeUnit.SECONDS);

    assertThat(ranges).isEmpty();
  }

  @Test
  void hover_nonFileUri_returnsNullWithoutThrowing() throws Exception {
    service.initialize(tmp);

    final Hover hover =
        service
            .hover(
                new HoverParams(
                    new TextDocumentIdentifier("diffview:///workspace/.git/:0:/Foo.java"),
                    new Position(0, 0)))
            .get(5, TimeUnit.SECONDS);

    assertThat(hover).isNull();
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
  void formatting_disabled_returnsEmptyEdits() throws Exception {
    final List<? extends TextEdit> edits =
        service.formatting(formattingParams()).get(5, TimeUnit.SECONDS);

    assertThat(edits).isEmpty();
  }

  @Test
  void formatting_enabled_delegatesToFormatter() throws Exception {
    service.initialize(tmp);
    service.setFormatEngine(FormatterFixtures.googleJavaFormat());
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(URI, "java", 1, "class Foo {\nint x;\n}\n")));

    final List<? extends TextEdit> edits =
        service.formatting(formattingParams()).get(5, TimeUnit.SECONDS);

    assertThat(edits).isNotEmpty();
    verify(client, timeout(2000))
        .showMessage(
            argThat(
                m -> m.getType() == MessageType.Info && m.getMessage().contains("formatted in")));
  }

  @Test
  void formatting_engineFailsRepeatedly_notifiesClientOnceWithReason() throws Exception {
    service.initialize(tmp);
    service.setFormatEngine(FormatterFixtures.googleJavaFormat());
    service.didOpen(
        new DidOpenTextDocumentParams(new TextDocumentItem(URI, "java", 1, "class { broken")));

    final List<? extends TextEdit> first =
        service.formatting(formattingParams()).get(5, TimeUnit.SECONDS);
    final List<? extends TextEdit> second =
        service.formatting(formattingParams()).get(5, TimeUnit.SECONDS);

    assertThat(first).isEmpty();
    assertThat(second).isEmpty();
    verify(client, timeout(2000).times(1))
        .showMessage(
            argThat(
                m ->
                    m.getType() == MessageType.Warning
                        && m.getMessage().startsWith("Lathe: formatting failed: ")
                        && m.getMessage().endsWith("(further failures are only logged)")));
  }

  @Test
  void rangeFormatting_inProcessEngine_formatsOnlyTheRange() throws Exception {
    service.initialize(tmp);
    service.setFormatEngine(FormatterFixtures.googleJavaFormat());
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(URI, "java", 1, "class Foo {\nint x;\nint   y;\n}\n")));

    final List<? extends TextEdit> edits =
        service.rangeFormatting(rangeFormattingParams(1)).get(5, TimeUnit.SECONDS);

    assertThat(edits).hasSize(1);
    assertThat(edits.getFirst().getRange().getStart().getLine()).isEqualTo(1);
    assertThat(edits.getFirst().getRange().getEnd().getLine()).isEqualTo(1);
  }

  @Test
  void rangeFormatting_wholeFileEngine_returnsEmptyEdits() throws Exception {
    service.setFormatEngine(
        new ExternalCommandFormatEngine(
            List.of("cat"), ExternalCommandFormatEngine.DEFAULT_TIMEOUT, tmp));

    assertThat(service.rangeFormatting(rangeFormattingParams(0)).get(5, TimeUnit.SECONDS))
        .isEmpty();
  }

  private static DocumentRangeFormattingParams rangeFormattingParams(final int line) {
    final var params = new DocumentRangeFormattingParams();
    params.setTextDocument(new TextDocumentIdentifier(URI));
    params.setRange(new Range(new Position(line, 0), new Position(line + 1, 0)));
    params.setOptions(new FormattingOptions(2, true));
    return params;
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
  void instantiations_multiConstructorType_progressAlwaysEnds() throws Exception {
    final Path sourceRoot = tmp.resolve("module/src/main/java");
    final Path source = sourceRoot.resolve("com/example/Widget.java");
    Files.createDirectories(source.getParent());
    final var content =
        """
        package com.example;
        class Widget {
          Widget() {}
          Widget(int n) {}
          static Widget make() { return new Widget(1); }
        }
        """;
    Files.writeString(source, content);
    TestCompiler.writeModuleParams(tmp, "module", sourceRoot, null);
    service.setWorkDoneProgressSupported(true);
    service.initialize(tmp);
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(source.toUri().toString(), "java", 1, content)));

    // Two constructors => two overload searches sharing one progress; the leak was that it never
    // ended. Regression: whatever it reports, its final value must be an End.
    final List<Location> sites =
        service
            .instantiationsFuture(
                source.toUri().toString(), offsetToPosition(content, content.indexOf("Widget {")))
            .get(5, TimeUnit.SECONDS);

    final var progressCaptor = ArgumentCaptor.forClass(ProgressParams.class);
    verify(client, timeout(5_000).atLeast(2)).notifyProgress(progressCaptor.capture());
    assertThat(sites).isNotEmpty();
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
            .runTestFuture(
                "app", List.of(new TestSelection(TestSelectionKind.CLASS, "com.example.Foo")), "")
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

  private static DocumentFormattingParams formattingParams() {
    final var params = new DocumentFormattingParams();
    params.setTextDocument(new TextDocumentIdentifier(URI));
    params.setOptions(new FormattingOptions(2, true));
    return params;
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

  @Test
  void initialize_sourceUpToDateWithItsClass_doesNotPrompt() throws Exception {
    writeStaleModule(1_000L, 5_000L); // compiled after the source's last edit → fresh
    service.setWorkDoneProgressSupported(true);

    service.initialize(tmp);

    verify(client, after(500).never()).showMessageRequest(any());
    // Nothing is stale, so no in-process recompile progress is reported (only workspace indexing).
    verify(client, never()).notifyProgress(argThat(LatheTextDocumentServiceTest::isRecompileBegin));
  }

  @Test
  void initialize_moreExternalChangesThanTheOldBulkThreshold_compiledInProcessWithoutNotice()
      throws Exception {
    final Path sourceRoot = tmp.resolve("module/src/main/java");
    IntStream.range(0, 61)
        .forEach(
            i ->
                IOUtil.unchecked(
                    () ->
                        TestCompiler.writeAt(
                            sourceRoot.resolve("com/example/T%d.java".formatted(i)),
                            "package com.example; class T%d {}".formatted(i),
                            5_000L)));
    TestCompiler.writeModuleParams(tmp, "module", sourceRoot, null);

    service.initialize(tmp);
    service.reconcileNow(true).get(30, TimeUnit.SECONDS); // runs after the startup pass

    assertThat(CompiledStamps.load(tmp.resolve(".lathe/module"), "classes"))
        .as("every changed source compiled and stamped")
        .hasSize(61)
        .allSatisfy((source, mtime) -> assertThat(mtime).isEqualTo(5_000L));
    verify(client, never())
        .showMessage(argThat((MessageParams p) -> p.getType() == MessageType.Warning));
    verify(client, never()).showMessageRequest(any());
  }

  @Test
  void reconcileNow_brokenFileInABatch_restOfTheTreeIsStillWritten() throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put(
        "com/example/Good.java", "package com.example; class Good { int g() { return 1; } }");
    sources.put("com/example/Broken.java", "package com.example; class Broken {}");
    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    service.initialize(tmp);
    awaitStartup();

    // Both change on disk; Broken no longer compiles. javac writes nothing for a task with an
    // error, so the batch is written again without Broken.
    TestCompiler.writeAt(
        files.get(0), "package com.example; class Good { int g() { return 2; } }", 5_000L);
    TestCompiler.writeAt(
        files.get(1),
        "package com.example; class Broken { int b() { return missing(); } }",
        5_000L);
    service.reconcileNow(true).get(10, TimeUnit.SECONDS);

    assertThat(CompiledStamps.load(tmp.resolve(".lathe/module"), "classes"))
        .as("stamps: the good file written, the broken one left stale")
        .containsEntry("com/example/Good.java", 5_000L)
        .containsEntry("com/example/Broken.java", 1_000L);
  }

  @Test
  void reconcileNow_mutuallyDependentChangesLandingApart_failedOneRidesAlongAndBothCompile()
      throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put("com/example/A.java", "package com.example; class A { int a() { return 1; } }");
    sources.put("com/example/B.java", "package com.example; class B { int b() { return 2; } }");
    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    service.initialize(tmp);
    awaitStartup();

    // A changes first and needs B's new method, so it fails alone.
    TestCompiler.writeAt(
        files.get(0),
        "package com.example; class A { int a() { return new B().newB(); } int newA() { return 3; } }",
        5_000L);
    service.reconcileNow(true).get(10, TimeUnit.SECONDS);
    // B changes next and needs A's new method: alone it would fail too, leaving both stuck.
    TestCompiler.writeAt(
        files.get(1),
        "package com.example; class B { int b() { return 2; } int newB() { return new A().newA(); } }",
        6_000L);
    service.reconcileNow(true).get(10, TimeUnit.SECONDS);

    assertThat(CompiledStamps.load(tmp.resolve(".lathe/module"), "classes"))
        .as("both written together once B's write takes the failed A along")
        .containsEntry("com/example/A.java", 5_000L)
        .containsEntry("com/example/B.java", 6_000L);
  }

  @Test
  void didSave_processorReportsOnTheWrite_mergedIntoThePublishUntilTheBufferChanges()
      throws Exception {
    // Analysis runs without annotation processors, so only the write sees this one's warning.
    final Path processorSource =
        TestCompiler.writeAt(
            tmp.resolve("processor-src/proc/NoteProcessor.java"),
            """
            package proc;
            import java.util.Set;
            import javax.annotation.processing.*;
            import javax.lang.model.SourceVersion;
            import javax.lang.model.element.TypeElement;
            import javax.tools.Diagnostic;
            @SupportedAnnotationTypes("*")
            public class NoteProcessor extends AbstractProcessor {
              @Override public SourceVersion getSupportedSourceVersion() {
                return SourceVersion.latestSupported();
              }
              @Override public boolean process(Set<? extends TypeElement> types, RoundEnvironment round) {
                round.getRootElements().forEach(element -> processingEnv.getMessager()
                    .printMessage(Diagnostic.Kind.WARNING, "noted by processor", element));
                return false;
              }
            }
            """,
            1_000L);
    final Path processors = tmp.resolve("processors");
    TestCompiler.compileToDir(processors, processorSource);
    final Path note =
        TestCompiler.writeMirroredModule(
                tmp,
                "module",
                Map.of("com/example/Note.java", "package com.example; class Note {}"),
                List.of(),
                1_000L)
            .get(0);
    TestCompiler.writeModuleParams(
        tmp,
        "module",
        tmp.resolve("module/src/main/java"),
        null,
        List.of(),
        List.of(processors),
        List.of("-processor", "proc.NoteProcessor"));
    service.initialize(tmp);
    awaitStartup();
    openDoc(note, Files.readString(note));
    awaitWorkerIdle();

    TestCompiler.writeAt(note, Files.readString(note), 5_000L);
    save(note);
    verify(client, timeout(5_000))
        .publishDiagnostics(argThat(p -> isFor(p, note) && hasMessage(p, "noted by processor")));

    // Edited, the buffer no longer holds what was written, so the write's warning no longer
    // applies.
    change(note, "package com.example; class Note { private int marker; }", 2);
    verify(client, timeout(5_000))
        .publishDiagnostics(
            argThat(
                p ->
                    isFor(p, note)
                        && hasMessage(p, "'marker'")
                        && !hasMessage(p, "noted by processor")));
  }

  @Test
  void writeReported_whileABuildHoldsTheLock_writesReportedFilesEvenAtTheirStamp_externalWaits()
      throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put("com/example/A.java", "package com.example; class A {}");
    sources.put("com/example/B.java", "package com.example; class B {}");
    sources.put("com/example/C.java", "package com.example; class C {}");
    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    service.initialize(tmp);
    awaitStartup();
    final Path lock = LatheLayout.reactorLockFile(tmp.resolve(LatheLayout.LATHE_DIR));
    LatheLock.acquire(lock);
    clearInvocations(client);

    // A and B are reported -- B with its mtime still equal to its stamp -- and C changed
    // externally.
    TestCompiler.writeAt(files.get(0), "package com.example; class A { int addedA; }", 5_000L);
    TestCompiler.writeAt(files.get(1), "package com.example; class B { int addedB; }", 1_000L);
    TestCompiler.writeAt(files.get(2), "package com.example; class C { int addedC; }", 5_000L);
    service.writeReported(Set.of(files.get(0), files.get(1))).get(10, TimeUnit.SECONDS);

    final Path classes = tmp.resolve(".lathe/module/classes/com/example");
    assertThat(Files.readString(classes.resolve("B.class"), StandardCharsets.ISO_8859_1))
        .as("B written though its mtime equals its stamp")
        .contains("addedB");
    assertThat(CompiledStamps.load(tmp.resolve(".lathe/module"), "classes"))
        .as("stamps during the build: the reported A written, the external C left for later")
        .containsEntry("com/example/A.java", 5_000L)
        .containsEntry("com/example/C.java", 1_000L);
    verify(client, never()).notifyProgress(argThat(LatheTextDocumentServiceTest::isRecompileBegin));

    LatheLock.release(lock);
    service.reconcileNow(true).get(10, TimeUnit.SECONDS);
    assertThat(CompiledStamps.load(tmp.resolve(".lathe/module"), "classes"))
        .as("after the build, the external change catches up")
        .containsEntry("com/example/C.java", 5_000L);
  }

  @Test
  void reconcileNow_eager_recompilesStaleSourceInOnePass() throws Exception {
    writeStaleModule(1_000L, 1_000L); // fresh at init (mtime == stamp) → startup eager is a no-op
    service.initialize(tmp);
    awaitStartup(); // let startup reconciliation finish before mutating the source
    touchFoo(5_000L); // now newer than its stamp → stale

    // A single eager pass compiles it (no two-tick wait), advancing the stamp to the source mtime.
    service.reconcileNow(true).get(5, TimeUnit.SECONDS);

    assertThat(CompiledStamps.load(tmp.resolve(".lathe/module"), "classes"))
        .containsEntry("com/example/Foo.java", 5_000L);
  }

  @Test
  void reconcileNow_sealedRootAndNewPermittedSubtypeChangedExternally_recompilesBothInOneBatch()
      throws Exception {
    final Path shape = mirroredShapes();
    final Path square = shape.resolveSibling("Square.java");
    final Path classesDir = tmp.resolve(".lathe/module/classes");

    service.initialize(tmp);
    awaitStartup();

    // The edit lands on disk: Shape permits a new Square, and Square is created -- both stale. A
    // per-file recompile would deadlock (each needs the other); the batch resolves them together.
    TestCompiler.writeAt(
        shape, "package shapes; sealed interface Shape permits Circle, Square {}", 5_000L);
    TestCompiler.writeAt(square, "package shapes; final class Square implements Shape {}", 5_000L);

    service.reconcileNow(true).get(5, TimeUnit.SECONDS);

    assertThat(classesDir.resolve("shapes/Square.class")).exists();
    assertThat(CompiledStamps.load(tmp.resolve(".lathe/module"), "classes"))
        .containsEntry("shapes/Shape.java", 5_000L)
        .containsEntry("shapes/Square.java", 5_000L);
  }

  @Test
  void reconcileNow_upstreamAndDependentChangedTogether_compilesDependentAfterUpstreamInOnePass()
      throws Exception {
    // Mirrored baseline: app calls Api.a().
    final Path api =
        TestCompiler.writeMirroredModule(
                tmp,
                "api",
                Map.of(
                    "com/example/api/Api.java",
                    "package com.example.api; public class Api { public int a() { return 1; } }"),
                List.of(),
                1_000L)
            .getFirst();
    final Path app =
        TestCompiler.writeMirroredModule(
                tmp,
                "app",
                Map.of(
                    "com/example/app/App.java",
                    "package com.example.app;"
                        + " class App { int u() { return new com.example.api.Api().a(); } }"),
                List.of("api"),
                1_000L)
            .getFirst();

    service.initialize(tmp);
    awaitStartup();

    // A rename lands on disk: a() becomes b() and its caller follows. App compiles only against
    // the new Api class, so its batch must run after api's has written the mirror.
    TestCompiler.writeAt(
        api, "package com.example.api; public class Api { public int b() { return 1; } }", 5_000L);
    TestCompiler.writeAt(
        app,
        "package com.example.app; class App { int u() { return new com.example.api.Api().b(); } }",
        5_000L);

    service.reconcileNow(true).get(10, TimeUnit.SECONDS);

    assertThat(CompiledStamps.load(tmp.resolve(".lathe/api"), "classes"))
        .containsEntry("com/example/api/Api.java", 5_000L);
    assertThat(CompiledStamps.load(tmp.resolve(".lathe/app"), "classes"))
        .containsEntry("com/example/app/App.java", 5_000L);
  }

  @Test
  void didOpen_subtypeOfAnUnsavedSealedRoot_neverReportsTheStaleSealedClause() throws Exception {
    final Path shape = mirroredShapes();
    final Path square = shape.resolveSibling("Square.java");
    service.initialize(tmp);

    // Shape's buffer permits Square, unsaved (opened restored with changes, as after an editor
    // restart); Square is analyzed with it, not against the written Shape that does not permit it.
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(
                shape.toUri().toString(),
                "java",
                1,
                "package shapes; sealed interface Shape permits Circle, Square {}")));
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(
                square.toUri().toString(),
                "java",
                1,
                "package shapes; final class Square implements Shape {}")));

    verify(client, timeout(5_000)).publishDiagnostics(argThat(p -> isFor(p, square)));
    awaitWorkerIdle();
    final var captor = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
    verify(client, atLeastOnce()).publishDiagnostics(captor.capture());
    assertThat(captor.getAllValues())
        .filteredOn(p -> isFor(p, square))
        .as("every publish for Square")
        .allMatch(p -> p.getDiagnostics().isEmpty());
  }

  @Test
  void didChange_unsavedSiblingChangedSignature_analyzedAgainstIt_unlessItNoLongerParses()
      throws Exception {
    // An unused field is reported only by an analysis without errors, so a publish carrying it is
    // a clean analysis -- not the empty publish that clears a file on each change.
    final var sources = new LinkedHashMap<String, String>();
    sources.put(
        "com/example/Greeter.java",
        "package com.example; class Greeter { static String greet(String name) { return name; } }");
    sources.put(
        "com/example/User.java",
        "package com.example; class User { private int marker; String u() { return Greeter.greet(\"a\"); } }");
    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    final Path greeter = files.get(0);
    final Path user = files.get(1);
    service.initialize(tmp);
    awaitStartup();
    openDoc(greeter, Files.readString(greeter));
    openDoc(user, Files.readString(user));
    awaitWorkerIdle();

    // Greeter gains a parameter, unsaved; User calls it the new way. Against the written Greeter
    // that call is an error -- a kind the old error-code widening never looked at.
    clearInvocations(client);
    change(
        greeter,
        "package com.example; class Greeter { private static int g; static String greet(String name, int times) { return name; } }",
        2);
    verify(client, timeout(5_000))
        .publishDiagnostics(argThat(p -> isFor(p, greeter) && hasMessage(p, "'g'")));
    clearInvocations(client);
    change(
        user,
        "package com.example; class User { private int marker; String u() { return Greeter.greet(\"a\", 2); } }",
        2);
    verify(client, timeout(5_000))
        .publishDiagnostics(
            argThat(p -> isFor(p, user) && hasMessage(p, "'marker'") && !hasError(p)));

    // Greeter is mid-edit and no longer parses: User falls back to the written Greeter.
    clearInvocations(client);
    change(
        greeter,
        "package com.example; class Greeter { static String greet(String name, int times) { return name; }",
        3);
    verify(client, timeout(5_000))
        .publishDiagnostics(argThat(p -> isFor(p, greeter) && hasError(p)));
    clearInvocations(client);
    change(
        user,
        "package com.example; class User { private int marker; String u() { return Greeter.greet(\"a\", 2);  } }",
        3);
    verify(client, timeout(5_000)).publishDiagnostics(argThat(p -> isFor(p, user) && hasError(p)));
  }

  @Test
  void didSave_singleSaveThenBurstAcrossFiles_recompilesEachOpenDependentOnce() throws Exception {
    final DepFixture fx = depWithUsers(3);
    service.initialize(tmp);
    awaitStartup();
    openDoc(fx.dep(), Files.readString(fx.dep()));
    for (final Path user : fx.users()) {
      openDoc(user, Files.readString(user));
      verify(client, timeout(5_000)).publishDiagnostics(argThat(p -> isFor(p, user)));
    }

    awaitWorkerIdle();
    clearInvocations(client);
    // Count compiles, not publishes: a stale result is dropped unpublished, so a pile-up of
    // redundant compiles only shows in the compile log.
    final var compiles = new ConcurrentLinkedQueue<String>();
    final var handler = new MessageCollector(compiles);
    final var logger = Logger.getLogger(SourceAnalysisSession.class.getName());
    logger.addHandler(handler);
    try {
      // A single save refreshes its open dependents as soon as its own compile is done, in one
      // analysis task for their tree.
      save(fx.dep());
      for (final Path user : fx.users()) {
        verify(client, timeout(5_000)).publishDiagnostics(argThat(p -> isFor(p, user)));
      }

      awaitWorkerIdle();
      assertRefreshedOnce(compiles, fx.users());

      awaitWorkerIdle();
      compiles.clear();
      clearInvocations(client);
      // `:wa` after a rename saves every touched file back to back; the refresh waits for all of
      // their compiles, instead of every completing save refreshing every other open file.
      save(fx.dep());
      fx.users().forEach(this::save);
      for (final Path user : fx.users()) {
        verify(client, after(1_500).atLeastOnce()).publishDiagnostics(argThat(p -> isFor(p, user)));
      }

      awaitWorkerIdle();
      assertRefreshedOnce(compiles, fx.users());
    } finally {
      logger.removeHandler(handler);
    }
  }

  @Test
  void rename_editorAppliesMoveAndSavesEveryFile_compilesEachOnceAfterOneBatchAtTheLastSave()
      throws Exception {
    final DepFixture fx = depWithUsers(3);
    service.setFileRenameSupported(true);
    service.initialize(tmp);
    awaitStartup();
    final String depSource = Files.readString(fx.dep());
    openDoc(fx.dep(), depSource);
    awaitWorkerIdle();

    // Renaming the public type moves its file, Dep.java -> Dep2.java.
    final var pos = offsetToPosition(depSource, depSource.indexOf("Dep {"));
    final WorkspaceEdit edit =
        service
            .rename(
                new RenameParams(
                    new TextDocumentIdentifier(fx.dep().toUri().toString()), pos, "Dep2"))
            .get(10, TimeUnit.SECONDS);
    final Map<String, List<TextEdit>> edits = RenameProvider.textEdits(edit);
    final Map<String, String> moves = RenameProvider.fileMoves(edit);
    assertThat(edits).hasSize(4);
    assertThat(moves).hasSize(1);

    final var compiles = new ConcurrentLinkedQueue<String>();
    final var handler = new MessageCollector(compiles);
    final var logger = Logger.getLogger(SourceAnalysisSession.class.getName());
    logger.addHandler(handler);
    try {
      // Apply it as Neovim does: open each touched file as it is on disk and change it to the
      // edited text; move the renamed file on disk (unsaved), close it and reopen it under its
      // new name. Then `:wa` writes and saves them one by one.
      final var edited = new LinkedHashMap<Path, String>();
      // The renamed-from buffer is edited first, as an editor applies it, so the transaction is
      // applied before any other touched file opens.
      final var ordered = new LinkedHashMap<String, List<TextEdit>>();
      ordered.put(fx.dep().toUri().toString(), edits.get(fx.dep().toUri().toString()));
      ordered.putAll(edits);
      ordered.forEach(
          (uri, fileEdits) -> {
            final Path file = LatheUri.toPath(uri);
            final String content = IOUtil.unchecked(() -> Files.readString(file));
            if (!file.equals(fx.dep())) {
              openDoc(file, content);
            }

            final String next = SourceLocator.applyEdits(content, fileEdits);
            service.didChange(
                new DidChangeTextDocumentParams(
                    new VersionedTextDocumentIdentifier(uri, 2),
                    List.of(new TextDocumentContentChangeEvent(next))));
            edited.put(LatheUri.toPath(moves.getOrDefault(uri, uri)), next);
          });
      moves.forEach(
          (oldUri, newUri) -> {
            IOUtil.unchecked(() -> Files.move(LatheUri.toPath(oldUri), LatheUri.toPath(newUri)));
            service.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(oldUri)));
            openDoc(LatheUri.toPath(newUri), edited.get(LatheUri.toPath(newUri)));
          });
      service.reconcileNow(true).get(10, TimeUnit.SECONDS); // the move is seen before saving
      edited.forEach(
          (file, next) -> {
            IOUtil.unchecked(() -> Files.writeString(file, next));
            save(file);
          });

      // Each file republishes clean once the commit has compiled them together.
      for (final Path file : edited.keySet()) {
        verify(client, timeout(10_000))
            .publishDiagnostics(argThat(p -> isFor(p, file) && p.getDiagnostics().isEmpty()));
      }

      awaitWorkerIdle();
      // Nothing compiles while the edit is applied and saved; then one write and one analysis.
      assertThat(writes(compiles)).as("write tasks, by files written").containsExactly(4);
      assertThat(analyses(compiles)).as("analysis tasks, by files analyzed").containsExactly(4);

      final Path classes = tmp.resolve(".lathe/module/classes/com/example");
      assertThat(classes.resolve("Dep2.class")).exists();
      assertThat(classes.resolve("Dep.class")).doesNotExist();
    } finally {
      logger.removeHandler(handler);
    }
  }

  @Test
  void didSave_dependentEditedDuringRefreshBatch_lastPublishReflectsTheEdit() throws Exception {
    final DepFixture fx = depWithUsers(3);
    service.initialize(tmp);
    awaitStartup();
    openDoc(fx.dep(), Files.readString(fx.dep()));
    for (final Path user : fx.users()) {
      openDoc(user, Files.readString(user));
    }

    awaitWorkerIdle();
    clearInvocations(client);
    final Path edited = fx.users().getFirst();

    // The save starts a refresh batch over the users; one of them is edited while it runs. A batch
    // result for the old content is stale and dropped, so the edit's own compile has the last word.
    save(fx.dep());
    service.didChange(
        new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier(edited.toUri().toString(), 2),
            List.of(
                new TextDocumentContentChangeEvent(
                    "package com.example;\nclass User0 { int u() { return missing(); } }\n"))));

    verify(client, timeout(5_000))
        .publishDiagnostics(
            argThat(
                p ->
                    isFor(p, edited)
                        && p.getDiagnostics().stream()
                            .anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error)));
    awaitWorkerIdle();
    final var publishes = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
    verify(client, atLeastOnce()).publishDiagnostics(publishes.capture());
    assertThat(
            publishes.getAllValues().stream()
                .filter(p -> isFor(p, edited))
                .toList()
                .getLast()
                .getDiagnostics())
        .anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);
  }

  @Test
  void didSave_savesArrivingDuringASaveCompile_compileAsOneBatch_eachRepublished()
      throws Exception {
    final DepFixture fx = depWithUsers(3);
    service.initialize(tmp);
    awaitStartup();
    final var files = new ArrayList<Path>(fx.users());
    files.add(fx.dep());
    for (final Path file : files) {
      openDoc(file, Files.readString(file));
    }

    awaitWorkerIdle();
    clearInvocations(client);
    final var compiles = new ConcurrentLinkedQueue<String>();
    final var handler = new MessageCollector(compiles);
    final var logger = Logger.getLogger(SourceAnalysisSession.class.getName());
    logger.addHandler(handler);
    try {
      // A Save All of hand-edited files: the first save compiles at once, the rest arrive while it
      // runs and compile together when it is done.
      for (final Path file : files) {
        Files.writeString(file, Files.readString(file) + "\n// edited\n");
        save(file);
      }

      for (final Path file : files) {
        verify(client, timeout(10_000))
            .publishDiagnostics(argThat(p -> isFor(p, file) && p.getDiagnostics().isEmpty()));
      }

      awaitWorkerIdle();
      assertThat(writes(compiles))
          .as("write tasks, by files written")
          .hasSizeLessThanOrEqualTo(2)
          .satisfies(
              tasks ->
                  assertThat(tasks.stream().mapToInt(Integer::intValue).sum())
                      .as("files written")
                      .isEqualTo(files.size()));
      final Map<String, Long> stamps = CompiledStamps.load(tmp.resolve(".lathe/module"), "classes");
      for (final Path file : files) {
        assertThat(stamps)
            .containsEntry(
                tmp.resolve("module/src/main/java").relativize(file).toString(),
                Files.getLastModifiedTime(file).toMillis());
      }
    } finally {
      logger.removeHandler(handler);
    }
  }

  private void change(final Path file, final String content, final int version) {
    service.didChange(
        new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier(file.toUri().toString(), version),
            List.of(new TextDocumentContentChangeEvent(content))));
  }

  private void save(final Path file) {
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

  // The open dependents were refreshed once: each republished exactly once, all by one multi-file
  // analysis task. A single-file analysis of the saved file itself is allowed.
  private void assertRefreshedOnce(
      final Collection<String> compileLog, final List<Path> dependents) {
    assertThat(analyses(compileLog))
        .as("multi-file analysis tasks (the dependents refresh)")
        .filteredOn(files -> files > 1)
        .hasSize(1);
    dependents.forEach(
        dependent ->
            verify(client, times(1)).publishDiagnostics(argThat(p -> isFor(p, dependent))));
  }

  private static List<Integer> writes(final Collection<String> compileLog) {
    return tasks(compileLog, Set.of("full", "batch"));
  }

  private static List<Integer> analyses(final Collection<String> compileLog) {
    return tasks(compileLog, Set.of("open", "fast", "open-batch"));
  }

  private static List<Integer> tasks(final Collection<String> compileLog, final Set<String> kinds) {
    return compileLog.stream()
        .map(COMPILE_TASK::matcher)
        .filter(Matcher::find)
        .filter(task -> kinds.contains(task.group(1)))
        .map(task -> task.group(2) == null ? 1 : Integer.parseInt(task.group(2)))
        .toList();
  }

  @Test
  void workspaceSymbol_sourceWithoutCompiledClass_foundFromSource_compiledTypeStillFound()
      throws Exception {
    final DepFixture fx = depWithUsers(1);
    // On disk but never compiled into the mirror: it does not compile, as a type restored by a
    // bulk external change the server defers to a sync has no class either.
    TestCompiler.writeModuleSource(
        tmp,
        "com/example/Restored.java",
        "package com.example;\npublic class Restored { Missing m; }\n");
    service.initialize(tmp);
    awaitStartup();

    assertThat(symbolNames("Restored")).contains("Restored");
    assertThat(symbolNames("Dep")).contains("Dep");
  }

  private List<String> symbolNames(final String query) throws Exception {
    return service.workspaceSymbolFuture(query).get(5, TimeUnit.SECONDS).stream()
        .map(SymbolInformation::getName)
        .toList();
  }

  @Test
  void reconcileNow_deletedDependency_republishesOpenDependentWithError() throws Exception {
    final DepFixture fx = depWithUsers(1);
    service.initialize(tmp);
    final Path user = fx.users().getFirst();
    openDoc(user, Files.readString(user));
    verify(client, timeout(5_000))
        .publishDiagnostics(argThat(p -> isFor(p, user) && p.getDiagnostics().isEmpty()));

    Files.delete(fx.dep());
    service.reconcileNow().get(5, TimeUnit.SECONDS);

    verify(client, timeout(5_000))
        .publishDiagnostics(
            argThat(
                p ->
                    isFor(p, user)
                        && p.getDiagnostics().stream()
                            .anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error)));
  }

  @Test
  void reconcileNow_closedSourceChangedExternally_recompilesInProcessWithoutPrompting()
      throws Exception {
    writeStaleModule(1_000L, 1_000L); // fresh at init → startup eager is a no-op
    service.setWorkDoneProgressSupported(true);
    service.initialize(tmp);
    awaitStartup();
    touchFoo(5_000L); // now stale

    // First pass records the change as pending (two-tick stability); the second recompiles it.
    service.reconcileNow().get(5, TimeUnit.SECONDS);
    service.reconcileNow().get(5, TimeUnit.SECONDS);

    // Recompiled in-process (its stamp advanced to the source's mtime) and not prompted for Maven.
    assertThat(CompiledStamps.load(tmp.resolve(".lathe/module"), "classes"))
        .containsEntry("com/example/Foo.java", 5_000L);
    verify(client, never()).showMessageRequest(any());
    // The in-process recompile reports a self-clearing progress task (begin "recompiling" + end).
    verify(client, timeout(5_000).atLeastOnce())
        .notifyProgress(argThat(LatheTextDocumentServiceTest::isRecompileBegin));
  }

  @Test
  void reconcileNow_compileFails_doesNotAdvanceStamp() throws Exception {
    writeStaleModule(1_000L, 1_000L);
    service.initialize(tmp);
    awaitStartup();
    // A reference to a missing type: the compile emits no class for Foo.
    TestCompiler.writeAt(
        tmp.resolve("module/src/main/java/com/example/Foo.java"),
        "package com.example; class Foo { Missing field; }",
        5_000L);

    service.reconcileNow().get(5, TimeUnit.SECONDS);
    service.reconcileNow().get(5, TimeUnit.SECONDS);

    // A failed compile must not stamp the source fresh: it stays stale so a later batch retries it
    // once its dependency lands, instead of leaving a stale mirror class marked up to date.
    assertThat(CompiledStamps.load(tmp.resolve(".lathe/module"), "classes"))
        .containsEntry("com/example/Foo.java", 1_000L);
  }

  @Test
  void initialize_copiesOnlyStaleResourcesIntoLathe() throws Exception {
    final Path resDir = tmp.resolve("app/src/main/resources");
    TestCompiler.writeAt(resDir.resolve("stale.conf"), "new", 9_000L); // newer than dest → copied
    TestCompiler.writeAt(
        resDir.resolve("fresh.conf"), "src", 1_000L); // older than dest → untouched
    final Path staleDest = writeResourceDest("stale.conf", "old", 1_000L);
    final Path freshDest = writeResourceDest("fresh.conf", "keep", 5_000L);
    writeResourceWorkspace("app/src/main/resources", "app/target/classes");

    service.initialize(tmp);
    awaitWorkerIdle();

    assertThat(Files.readString(staleDest)).isEqualTo("new"); // stale → copied
    assertThat(Files.readString(freshDest)).isEqualTo("keep"); // up to date → untouched
  }

  @Test
  void moduleRelOf_sourceInsideOrOutsideAModule_namesTheRegistryModuleOrNone() throws Exception {
    final Path api =
        TestCompiler.writeMirroredModule(
                tmp,
                "platform/api",
                Map.of("com/example/api/Api.java", "package com.example.api; public class Api {}"),
                List.of(),
                1_000L)
            .getFirst();

    service.initialize(tmp);
    awaitStartup();

    assertThat(service.moduleRelOf(api)).contains("platform/api");
    assertThat(service.moduleRelOf(tmp.resolve("scripts/Tool.java"))).isEmpty();
  }

  private Path writeResourceDest(final String name, final String content, final long mtime)
      throws Exception {
    return TestCompiler.writeAt(tmp.resolve(".lathe/app/classes").resolve(name), content, mtime);
  }

  private void writeResourceWorkspace(final String resourceDir, final String outputDir)
      throws Exception {
    final Path latheDir = tmp.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    Json.write(
        new WorkspaceManifestData(
            LatheLayout.SCHEMA_VERSION,
            tmp.toString(),
            null,
            List.of(),
            null,
            List.of(),
            List.of(),
            List.of(new ResourceRootData(resourceDir, outputDir, "", false, "app")),
            List.of(new OutputData(outputDir, "app", LatheLayout.CLASSES_DIR))),
        latheDir.resolve(LatheLayout.WORKSPACE_JSON));
  }

  // Force a round-trip through the single worker thread: a request submitted after initialize's
  // load
  // task completes only once that task (and its startup reconciliation) has run.
  private void awaitWorkerIdle() throws Exception {
    final var params = new FoldingRangeRequestParams();
    params.setTextDocument(new TextDocumentIdentifier("file:///nonexistent.java"));
    service.foldingRange(params).get(5, TimeUnit.SECONDS);
  }

  // A configured single-module workspace whose one source and its .class are stamped at the given
  // mtimes, so the startup source-staleness scan sees the source as stale (source > class) or
  // fresh.
  private void writeStaleModule(final long sourceMtime, final long stampMtime) throws Exception {
    final Path sourceRoot = tmp.resolve("module/src/main/java");
    TestCompiler.writeAt(
        sourceRoot.resolve("com/example/Foo.java"),
        "package com.example; class Foo {}",
        sourceMtime);
    CompiledStamps.writeAll(
        tmp.resolve(".lathe/module"), "classes", Map.of("com/example/Foo.java", stampMtime));
    TestCompiler.writeModuleParams(tmp, "module", sourceRoot, null);
  }

  // A reconcile pass runs after initialize on the FIFO worker, so startup reconciliation is
  // finished
  // before the test mutates sources.
  private void awaitStartup() throws Exception {
    service.reconcileNow(false).get(5, TimeUnit.SECONDS);
  }

  private void touchFoo(final long mtime) throws Exception {
    TestCompiler.writeAt(
        tmp.resolve("module/src/main/java/com/example/Foo.java"),
        "package com.example; class Foo {}",
        mtime);
  }

  // Dep plus userCount callers of Dep.v(), compiled into the mirror and stamped, so the workspace
  // starts fresh and the callers are exactly what a change to Dep must refresh.
  private DepFixture depWithUsers(final int userCount) throws Exception {
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
    return new DepFixture(files.getFirst(), files.subList(1, files.size()));
  }

  // Mirrored baseline: sealed Shape permits only Circle, both compiled and stamped; returns Shape.
  private Path mirroredShapes() throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put("shapes/Shape.java", "package shapes; sealed interface Shape permits Circle {}");
    sources.put("shapes/Circle.java", "package shapes; final class Circle implements Shape {}");
    return TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L).getFirst();
  }

  private record DepFixture(Path dep, List<Path> users) {
    private DepFixture {
      users = List.copyOf(users);
    }
  }

  private static boolean isFor(final PublishDiagnosticsParams params, final Path file) {
    return params.getUri().equals(file.toUri().toString());
  }

  private static boolean hasError(final PublishDiagnosticsParams params) {
    return params.getDiagnostics().stream()
        .anyMatch(diagnostic -> diagnostic.getSeverity() == DiagnosticSeverity.Error);
  }

  private static boolean hasMessage(final PublishDiagnosticsParams params, final String message) {
    return params.getDiagnostics().stream()
        .anyMatch(diagnostic -> diagnostic.getMessage().getLeft().contains(message));
  }

  // Collects log messages, so a test can count operations that leave no other observable trace.
  private static final class MessageCollector extends Handler {
    private final Queue<String> messages;

    MessageCollector(final Queue<String> messages) {
      this.messages = messages;
    }

    @Override
    public void publish(final LogRecord record) {
      messages.add(record.getMessage());
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  private void openDoc(final Path file, final String content) {
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(file.toUri().toString(), "java", 1, content)));
  }

  // A $/progress "begin" for the in-process recompile (its title carries "recompiling"), as opposed
  // to the workspace-indexing progress that also fires on initialize.
  private static boolean isRecompileBegin(final ProgressParams params) {
    return params.getValue().getLeft() instanceof WorkDoneProgressBegin begin
        && begin.getTitle().contains("recompiling");
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
