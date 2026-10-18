package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.server.analysis.SourceLocator.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.github.aglibs.lathe.core.CollectionUtil;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import io.github.aglibs.lathe.server.analysis.TypeHierarchyItemData;
import io.github.aglibs.lathe.server.analysis.TypeHierarchyItemDataCodec;
import io.github.aglibs.lathe.server.analysis.completion.CompletionOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchySubtypesParams;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkDoneProgressCancelParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// Editor features through the service: analysis on open and change, formatting, navigation,
// symbols.
class LatheTextDocumentServiceTest extends ServiceTestBase {

  private static final String URI = "file:///workspace/src/main/java/Foo.java";

  @Test
  void didChange_rapidKeystrokes_compilesOnlyOnce() {
    for (int i = 0; i < 5; i++) {
      service.didChange(changeParams("content-" + i));
    }

    verify(client, timeout(5_000).atLeastOnce())
        .publishDiagnostics(argThat(p -> p.getUri().equals(URI) && !p.getDiagnostics().isEmpty()));
  }

  @Test
  void didChange_rapidKeystrokes_compilesLatestContent() {
    for (int i = 0; i < 5; i++) {
      service.didChange(changeParams("content-" + i));
    }

    verify(client, timeout(5_000).atLeastOnce())
        .publishDiagnostics(
            argThat(
                p ->
                    !p.getDiagnostics().isEmpty()
                        && p.getDiagnostics()
                            .get(0)
                            .getMessage()
                            .getLeft()
                            .contains("not under any Lathe module source root")));
  }

  @Test
  void didOpen_compilesImmediatelyWithoutDebounce() {
    service.didOpen(
        new DidOpenTextDocumentParams(new TextDocumentItem(URI, "java", 1, "class Foo {}")));

    final var captor = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
    verify(client, timeout(5_000)).publishDiagnostics(captor.capture());
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
    verify(client, timeout(5_000)).publishDiagnostics(captor.capture());
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
    assertThat(edits.get(0).getRange().getStart().getLine()).isEqualTo(1);
    assertThat(edits.get(0).getRange().getEnd().getLine()).isEqualTo(1);
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
    assertThat(CollectionUtil.last(progressCaptor.getAllValues()).getValue().getLeft())
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
    assertThat(CollectionUtil.last(progressCaptor.getAllValues()).getValue().getLeft())
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
    assertThat(result.get(0).isRight()).isTrue();
    assertThat(result.get(0).getRight()).isSameAs(symbol);
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
    assertThat(items.get(0).getName()).isEqualTo("target");
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
            .callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(items.get(0)))
            .get(5, TimeUnit.SECONDS);

    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).getTo().getName()).isEqualTo("run");
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
            .callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(items.get(0)))
            .get(5, TimeUnit.SECONDS);

    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).getFrom().getName()).isEqualTo("caller");
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
  void moduleRelOf_sourceInsideOrOutsideAModule_namesTheRegistryModuleOrNone() throws Exception {
    final Path api =
        TestCompiler.writeMirroredModule(
                tmp,
                "platform/api",
                Map.of("com/example/api/Api.java", "package com.example.api; public class Api {}"),
                List.of(),
                1_000L)
            .get(0);

    service.initialize(tmp);
    awaitStartup();

    assertThat(service.moduleRelOf(api)).contains("platform/api");
    assertThat(service.moduleRelOf(tmp.resolve("scripts/Tool.java"))).isEmpty();
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
