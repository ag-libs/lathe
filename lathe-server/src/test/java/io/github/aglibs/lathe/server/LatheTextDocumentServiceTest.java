package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.server.analysis.SourceLocator.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.google.googlejavaformat.java.JavaFormatterOptions.Style;
import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import io.github.aglibs.lathe.core.schema.ResourceRootData;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
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
import org.eclipse.lsp4j.DiagnosticSeverity;
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
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
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
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchySubtypesParams;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
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
    service.setFormatEngine(new GoogleFormatEngine(Style.GOOGLE));
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
  void formatting_engineFails_notifiesClientAndReturnsEmpty() throws Exception {
    service.initialize(tmp);
    service.setFormatEngine(new GoogleFormatEngine(Style.GOOGLE));
    service.didOpen(
        new DidOpenTextDocumentParams(new TextDocumentItem(URI, "java", 1, "class { broken")));

    final List<? extends TextEdit> edits =
        service.formatting(formattingParams()).get(5, TimeUnit.SECONDS);

    assertThat(edits).isEmpty();
    verify(client, timeout(2000))
        .showMessage(
            argThat(
                m ->
                    m.getType() == MessageType.Warning
                        && m.getMessage().contains("formatting failed")));
  }

  @Test
  void rangeFormatting_anyProfile_returnsEmptyEdits() throws Exception {
    service.setFormatEngine(new GoogleFormatEngine(Style.GOOGLE));
    final var params = new DocumentRangeFormattingParams();
    params.setTextDocument(new TextDocumentIdentifier(URI));
    params.setRange(new Range(new Position(0, 0), new Position(0, 0)));
    params.setOptions(new FormattingOptions(2, true));

    assertThat(service.rangeFormatting(params).get(5, TimeUnit.SECONDS)).isEmpty();
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
  void manyExternalChangesAboveThreshold_showsBulkNoticeOnceNotModalNotRepeated() throws Exception {
    final Path sourceRoot = tmp.resolve("module/src/main/java");
    for (int i = 0; i <= 50; i++) { // 51 unstamped sources → all stale, over the bulk threshold
      TestCompiler.writeAt(
          sourceRoot.resolve("com/example/T" + i + ".java"),
          "package com.example; class T" + i + " {}",
          5_000L);
    }

    TestCompiler.writeModuleParams(tmp, "module", sourceRoot, null);

    service.initialize(tmp);
    service.reconcileNow().get(5, TimeUnit.SECONDS); // still over threshold → must not re-notify

    verify(client, timeout(5_000).times(1))
        .showMessage(
            argThat((MessageParams p) -> p.getMessage().contains("files changed at once")));
    verify(client, never()).showMessageRequest(any()); // non-blocking notice, never a modal
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
    final Path sourceRoot = tmp.resolve("module/src/main/java");
    final Path shape = sourceRoot.resolve("shapes/Shape.java");
    final Path circle = sourceRoot.resolve("shapes/Circle.java");
    final Path square = sourceRoot.resolve("shapes/Square.java");

    // Consistent, already-mirrored baseline: Shape permits Circle only, both compiled and stamped.
    TestCompiler.writeAt(shape, "package shapes; sealed interface Shape permits Circle {}", 1_000L);
    TestCompiler.writeAt(circle, "package shapes; final class Circle implements Shape {}", 1_000L);
    final Path classesDir = tmp.resolve(".lathe/module/classes");
    TestCompiler.compileToDir(classesDir, shape, circle);
    CompiledStamps.writeAll(
        tmp.resolve(".lathe/module"),
        "classes",
        Map.of("shapes/Shape.java", 1_000L, "shapes/Circle.java", 1_000L));
    TestCompiler.writeModuleParams(tmp, "module", sourceRoot, null);

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
  void reconcileNow_openClusterUnderUnrelatedBacklogOverThreshold_stillRecompilesCluster()
      throws Exception {
    // Reproduces the cross-module rename failure. A legitimate small change-set -- the rename's
    // mutually-referencing files, every one of which the editor's WorkspaceEdit opens -- never
    // reaches the mirror because an unrelated backlog of mtime-stale files keeps the WHOLE
    // workspace over the bulk threshold, so reconcileChangedSources bails to the sync notice and
    // never batches the cluster. The cluster is a sealed root and a new permitted subtype, which
    // deadlock on a piecemeal compile (each needs the other) and resolve only when batched.
    final Path sourceRoot = tmp.resolve("module/src/main/java");
    final Path shape = sourceRoot.resolve("shapes/Shape.java");
    final Path circle = sourceRoot.resolve("shapes/Circle.java");
    final Path square = sourceRoot.resolve("shapes/Square.java");

    // Mirrored baseline: Shape permits Circle only, both compiled and stamped.
    TestCompiler.writeAt(shape, "package shapes; sealed interface Shape permits Circle {}", 1_000L);
    TestCompiler.writeAt(circle, "package shapes; final class Circle implements Shape {}", 1_000L);
    final Path classesDir = tmp.resolve(".lathe/module/classes");
    TestCompiler.compileToDir(classesDir, shape, circle);
    CompiledStamps.writeAll(
        tmp.resolve(".lathe/module"),
        "classes",
        Map.of("shapes/Shape.java", 1_000L, "shapes/Circle.java", 1_000L));

    // An unrelated backlog of 51 unstamped sources -- stale only by mtime, enough on its own to
    // trip the workspace-wide bulk threshold (> 50) and disable in-process reconcile for
    // everything.
    for (int i = 0; i <= 50; i++) {
      TestCompiler.writeAt(
          sourceRoot.resolve("backlog/T" + i + ".java"),
          "package backlog; class T" + i + " {}",
          5_000L);
    }

    TestCompiler.writeModuleParams(tmp, "module", sourceRoot, null);
    service.initialize(tmp);
    awaitStartup();

    // The rename lands on disk and the editor opens every touched file: Shape now permits a new
    // Square, and Square is created.
    final String shapeSrc = "package shapes; sealed interface Shape permits Circle, Square {}";
    final String squareSrc = "package shapes; final class Square implements Shape {}";
    TestCompiler.writeAt(shape, shapeSrc, 5_000L);
    TestCompiler.writeAt(square, squareSrc, 5_000L);
    openDoc(shape, shapeSrc);
    openDoc(square, squareSrc);

    service.reconcileNow(true).get(5, TimeUnit.SECONDS);
    service.reconcileNow(true).get(5, TimeUnit.SECONDS);

    // The cluster must reach the mirror despite the backlog; the backlog alone stays deferred.
    assertThat(classesDir.resolve("shapes/Square.class")).exists();
    assertThat(CompiledStamps.load(tmp.resolve(".lathe/module"), "classes"))
        .containsEntry("shapes/Shape.java", 5_000L)
        .containsEntry("shapes/Square.java", 5_000L);
  }

  @Test
  void openSealedSubtype_liveDiagnostics_widenClearsErrorAgainstOpenSibling() throws Exception {
    final Path sourceRoot = tmp.resolve("module/src/main/java");
    final Path shape = sourceRoot.resolve("shapes/Shape.java");
    final Path circle = sourceRoot.resolve("shapes/Circle.java");
    final Path square = sourceRoot.resolve("shapes/Square.java");

    // Mirror baseline: Shape permits Circle only, both compiled and stamped fresh.
    TestCompiler.writeAt(shape, "package shapes; sealed interface Shape permits Circle {}", 1_000L);
    TestCompiler.writeAt(circle, "package shapes; final class Circle implements Shape {}", 1_000L);
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), shape, circle);
    CompiledStamps.writeAll(
        tmp.resolve(".lathe/module"),
        "classes",
        Map.of("shapes/Shape.java", 1_000L, "shapes/Circle.java", 1_000L));
    TestCompiler.writeModuleParams(tmp, "module", sourceRoot, null);
    service.initialize(tmp);

    final String shapeUri = shape.toUri().toString();
    final String squareUri = square.toUri().toString();
    // Open the edited pair as unsaved buffers: Shape now permits Square (opened first, so it is a
    // live sibling), then Square implements Shape (its single-file compile hits the stale mirror's
    // sealed clause). The widen recompiles Square with the open Shape buffer and clears the error.
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(
                shapeUri,
                "java",
                1,
                "package shapes; sealed interface Shape permits Circle, Square {}")));
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(
                squareUri, "java", 1, "package shapes; final class Square implements Shape {}")));

    // The widen's clean republish for Square eventually arrives.
    verify(client, timeout(5_000).atLeastOnce())
        .publishDiagnostics(
            argThat(p -> p.getUri().equals(squareUri) && p.getDiagnostics().isEmpty()));

    // Square's final published state is clean, though the sealed error was reported first.
    final var captor = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
    verify(client, atLeastOnce()).publishDiagnostics(captor.capture());
    final List<PublishDiagnosticsParams> squarePublishes =
        captor.getAllValues().stream().filter(p -> p.getUri().equals(squareUri)).toList();
    assertThat(squarePublishes)
        .anyMatch(
            p ->
                p.getDiagnostics().stream()
                    .anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error));
    assertThat(squarePublishes.getLast().getDiagnostics()).isEmpty();
  }

  @Test
  void reconcileNow_deletedDependency_republishesOpenDependentWithError() throws Exception {
    final Path sourceRoot = tmp.resolve("module/src/main/java");
    final Path dep = sourceRoot.resolve("com/example/Dep.java");
    final Path user = sourceRoot.resolve("com/example/User.java");
    Files.createDirectories(dep.getParent());
    Files.writeString(
        dep, "package com.example;\npublic class Dep { public int v() { return 1; } }\n");
    Files.writeString(
        user, "package com.example;\npublic class User { int u() { return new Dep().v(); } }\n");
    TestCompiler.compileToDir(tmp.resolve(".lathe/module/classes"), dep, user);
    CompiledStamps.writeAll(
        tmp.resolve(".lathe/module"),
        "classes",
        Map.of(
            "com/example/Dep.java", Files.getLastModifiedTime(dep).toMillis(),
            "com/example/User.java", Files.getLastModifiedTime(user).toMillis()));
    TestCompiler.writeModuleParams(tmp, "module", sourceRoot, null);
    service.initialize(tmp);

    final String userUri = user.toUri().toString();
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(userUri, "java", 1, Files.readString(user))));
    verify(client, timeout(5_000))
        .publishDiagnostics(
            argThat(p -> p.getUri().equals(userUri) && p.getDiagnostics().isEmpty()));

    Files.delete(dep);
    service.reconcileNow().get(5, TimeUnit.SECONDS);

    verify(client, timeout(5_000))
        .publishDiagnostics(
            argThat(
                p ->
                    p.getUri().equals(userUri)
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
            List.of(new ResourceRootData(resourceDir, outputDir, "", false, "app"))),
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
