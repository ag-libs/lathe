package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.schema.RunKind;
import io.github.aglibs.lathe.server.analysis.MissingImportsResult;
import io.github.aglibs.lathe.server.analysis.TypeHierarchyExplorerResult;
import io.github.aglibs.lathe.server.analysis.completion.CompletionOutcome;
import io.github.aglibs.lathe.server.debug.DebugStartResult;
import io.github.aglibs.lathe.server.run.LaunchOutcome;
import io.github.aglibs.lathe.server.run.RunConfigInfo;
import io.github.aglibs.lathe.server.run.RunConfigWriter;
import io.github.aglibs.lathe.server.run.RunTarget;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.logging.Logger;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

/**
 * The LSP document service and the shared analysis seam. Two front-ends drive it: {@link
 * LatheServer} over JSON-RPC and the in-process {@code LatheEngine} (MCP). The lifecycle methods
 * and {@link #diagnosticsFuture} are {@code public} so the engine in the {@code engine} subpackage
 * can consume the same session without exposing the rest of the LSP surface.
 */
public final class LatheTextDocumentService implements TextDocumentService {

  private static final Logger LOG = Logger.getLogger(LatheTextDocumentService.class.getName());
  private static final long DEFAULT_DEBOUNCE_MS = 500;

  private final ServerEventLoop worker = new ServerEventLoop();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final long debounceMs;
  private ProgressReporter progressReporter;
  private WorkspaceSession session;
  private volatile boolean formattingEnabled;

  public LatheTextDocumentService() {
    this(DEFAULT_DEBOUNCE_MS);
  }

  LatheTextDocumentService(final long debounceMs) {
    this.debounceMs = debounceMs;
  }

  public void connect(final LanguageClient client) {
    progressReporter = new ProgressReporter(client);
    worker.execute(
        () -> session = new WorkspaceSession(client, progressReporter, worker, debounceMs));
  }

  void setWorkDoneProgressSupported(final boolean supported) {
    progressReporter.setSupported(supported);
  }

  void setFormattingEnabled(final boolean enabled) {
    formattingEnabled = enabled;
  }

  void cancelProgress(final WorkDoneProgressCancelParams params) {
    progressReporter.cancel(params.getToken());
  }

  public void initialize(final Path workspaceRoot) {
    worker.execute(() -> session.initialize(workspaceRoot));
  }

  // Test/tool seam: run one reconcile pass and complete once its in-process recompiles finish.
  CompletableFuture<Void> reconcileNow() {
    return reconcileNow(false);
  }

  CompletableFuture<Void> reconcileNow(final boolean eager) {
    return worker.submit(() -> session.reconcileNow(eager)).thenCompose(done -> done);
  }

  /**
   * Freshen the mirror for a verify_change call, reporting what recompiled or why it was refused.
   */
  public CompletableFuture<ReconcileOutcome> reconcileForVerify() {
    return worker.submit(() -> session.reconcileForVerify()).thenCompose(done -> done);
  }

  /** The changed files plus their same-module candidate callers, grouped by module rel. */
  public CompletableFuture<Map<String, List<Path>>> verifyTargetsByModule(
      final List<Path> changed) {
    return worker.submit(() -> session.verifyTargetsByModule(changed));
  }

  /** Module rels that transitively depend on the changed files' modules (excluding them). */
  public CompletableFuture<List<String>> downstreamModuleRels(final List<Path> changed) {
    return worker.submit(() -> session.downstreamModuleRels(changed));
  }

  /** Reactor placement (module rel + test/production) for each path that maps to a module. */
  public CompletableFuture<Map<Path, ModulePlacement>> classifyPaths(final List<Path> paths) {
    return worker.submit(() -> session.classifyPaths(paths));
  }

  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }

    if (session != null) {
      worker
          .submit(
              () -> {
                session.close();
                return null;
              })
          .join();
    }

    worker.close();
  }

  @Override
  public void didOpen(final DidOpenTextDocumentParams params) {
    final var doc = params.getTextDocument();
    final var uri = doc.getUri();
    if (ignoreNonFile(uri, "open")) {
      return;
    }

    final var content = doc.getText();
    final var version = doc.getVersion();
    worker.execute(() -> session.onOpen(uri, content, version));
  }

  @Override
  public void didChange(final DidChangeTextDocumentParams params) {
    final var doc = params.getTextDocument();
    final var uri = doc.getUri();
    if (ignoreNonFile(uri, "change")) {
      return;
    }

    final var content = params.getContentChanges().getFirst().getText();
    final var version = doc.getVersion();
    worker.execute(() -> session.onChange(uri, content, version));
  }

  @Override
  public void didClose(final DidCloseTextDocumentParams params) {
    final var uri = params.getTextDocument().getUri();
    if (ignoreNonFile(uri, "close")) {
      return;
    }

    worker.execute(() -> session.onClose(uri));
  }

  @Override
  public void didSave(final DidSaveTextDocumentParams params) {
    final var uri = params.getTextDocument().getUri();
    if (ignoreNonFile(uri, "save")) {
      return;
    }

    final var content = params.getText();
    worker.execute(() -> session.onSave(uri, content));
  }

  // A non-file document -- an unnamed editor buffer (file://) or a non-file scheme -- has no source
  // Lathe can analyze; ignore its lifecycle events so nothing reaches LatheUri.toPath and throws.
  private boolean ignoreNonFile(final String uri, final String op) {
    if (LatheUri.isFileUri(uri)) {
      return false;
    }

    LOG.fine(() -> "[%s] ignored non-file uri %s".formatted(op, uri));
    return true;
  }

  private static CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      emptyLocationResult() {
    return CompletableFuture.completedFuture(Either.forLeft(List.of()));
  }

  @Override
  public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
      final CompletionParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    final var ctx = params.getContext();
    final var context = ctx != null ? ctx : new CompletionContext(CompletionTriggerKind.Invoked);
    if (ignoreNonFile(uri, "completion")) {
      return CompletableFuture.completedFuture(Either.forLeft(List.of()));
    }

    return worker
        .submit(() -> session.completionFuture(uri, pos, context))
        .thenCompose(f -> f)
        .thenApply(
            outcome -> {
              LOG.fine(
                  () ->
                      "[completion:lsp] %s line=%d character=%d items=%d incomplete=%s"
                          .formatted(
                              uri,
                              pos.getLine(),
                              pos.getCharacter(),
                              outcome.items().size(),
                              outcome.incomplete()));
              return completionResult(outcome);
            });
  }

  static Either<List<CompletionItem>, CompletionList> completionResult(
      final CompletionOutcome outcome) {
    if (outcome.incomplete()) {
      return Either.forRight(new CompletionList(true, outcome.items()));
    }

    return Either.forLeft(outcome.items());
  }

  @Override
  public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(
      final CodeActionParams params) {
    final var uri = params.getTextDocument().getUri();
    final var context = params.getContext();
    if (ignoreNonFile(uri, "codeAction")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return worker
        .submit(() -> session.codeActionFuture(uri, params.getRange(), context))
        .thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<SemanticTokens> semanticTokensFull(final SemanticTokensParams params) {
    final var uri = params.getTextDocument().getUri();
    if (ignoreNonFile(uri, "semanticTokens")) {
      return CompletableFuture.completedFuture(new SemanticTokens(List.of()));
    }

    return worker.submit(() -> session.semanticTokensFuture(uri)).thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
      final DocumentSymbolParams params) {
    final var uri = params.getTextDocument().getUri();
    if (ignoreNonFile(uri, "documentSymbol")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return worker
        .submit(() -> session.documentSymbolFuture(uri))
        .thenCompose(f -> f)
        .thenApply(LatheTextDocumentService::documentSymbolResult);
  }

  static List<Either<SymbolInformation, DocumentSymbol>> documentSymbolResult(
      final List<DocumentSymbol> symbols) {
    return symbols.stream().map(Either::<SymbolInformation, DocumentSymbol>forRight).toList();
  }

  @Override
  public CompletableFuture<List<FoldingRange>> foldingRange(
      final FoldingRangeRequestParams params) {
    final var uri = params.getTextDocument().getUri();
    if (ignoreNonFile(uri, "foldingRange")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return worker.submit(() -> session.foldingRangeFuture(uri)).thenCompose(f -> f);
  }

  // Runs a progress-reporting request and always cleans up: opens a progress bound to the response,
  // runs `work` with the cancel checker + task, then ends the progress and settles the response
  // however work completes. The one place the lifecycle lives, so no handler can leak a $/progress.
  private <T> CompletableFuture<T> withProgress(
      final Either<String, Integer> token,
      final BiFunction<CancelChecker, ProgressReporter.Task, CompletableFuture<? extends T>> work) {
    final var response = new CompletableFuture<T>();
    final CancelChecker cancelChecker = new CompletableFutures.FutureCancelChecker(response);
    final ProgressReporter.Task progress = progressReporter.open(token, response);
    work.apply(cancelChecker, progress)
        .whenComplete(
            (result, failure) -> {
              progress.finish(failure);
              if (failure == null) {
                response.complete(result);
              } else {
                response.completeExceptionally(failure);
              }
            });
    return response;
  }

  @Override
  public CompletableFuture<List<? extends Location>> references(final ReferenceParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    final var incl = params.getContext().isIncludeDeclaration();
    if (ignoreNonFile(uri, "references")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return withProgress(
        params.getWorkDoneToken(),
        (cancelChecker, progress) ->
            worker
                .submit(() -> session.referencesFuture(uri, pos, incl, cancelChecker, progress))
                .thenCompose(f -> f));
  }

  @Override
  public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>>
      prepareRename(final PrepareRenameParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "prepareRename")) {
      return CompletableFuture.completedFuture(null);
    }

    return worker.submit(() -> session.prepareRenameFuture(uri, pos)).thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<WorkspaceEdit> rename(final RenameParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    final var newName = params.getNewName();
    if (ignoreNonFile(uri, "rename")) {
      return CompletableFuture.completedFuture(null);
    }

    return withProgress(
        null,
        (cancelChecker, progress) ->
            worker
                .submit(() -> session.renameFuture(uri, pos, newName, cancelChecker, progress))
                .thenCompose(f -> f));
  }

  @Override
  public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(
      final DocumentHighlightParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "documentHighlight")) {
      return CompletableFuture.completedFuture(List.of());
    }

    final CompletableFuture<List<DocumentHighlight>> work =
        worker.submit(() -> session.documentHighlightFuture(uri, pos)).thenCompose(f -> f);
    return work.thenApply(highlights -> highlights);
  }

  @Override
  public CompletableFuture<SignatureHelp> signatureHelp(final SignatureHelpParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "signatureHelp")) {
      return CompletableFuture.completedFuture(null);
    }

    return worker.submit(() -> session.signatureHelpFuture(uri, pos)).thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<Hover> hover(final HoverParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "hover")) {
      return CompletableFuture.completedFuture(null);
    }

    return worker.submit(() -> session.hoverFuture(uri, pos)).thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      definition(final DefinitionParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "definition")) {
      return emptyLocationResult();
    }

    return worker.submit(() -> session.definitionFuture(uri, pos)).thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      declaration(final DeclarationParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "declaration")) {
      return emptyLocationResult();
    }

    return worker.submit(() -> session.declarationFuture(uri, pos)).thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      implementation(final ImplementationParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "implementation")) {
      return emptyLocationResult();
    }

    return worker.submit(() -> session.implementationFuture(uri, pos)).thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(
      final CallHierarchyPrepareParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "prepareCallHierarchy")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return worker.submit(() -> session.prepareCallHierarchyFuture(uri, pos)).thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(
      final CallHierarchyIncomingCallsParams params) {
    return withProgress(
        params.getWorkDoneToken(),
        (cancelChecker, progress) ->
            worker
                .submit(
                    () -> session.incomingCallsFuture(params.getItem(), cancelChecker, progress))
                .thenCompose(f -> f));
  }

  @Override
  public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(
      final CallHierarchyOutgoingCallsParams params) {
    return worker.submit(() -> session.outgoingCallsFuture(params.getItem())).thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<List<TypeHierarchyItem>> prepareTypeHierarchy(
      final TypeHierarchyPrepareParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "prepareTypeHierarchy")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return worker.submit(() -> session.prepareTypeHierarchyFuture(uri, pos)).thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySupertypes(
      final TypeHierarchySupertypesParams params) {
    return worker
        .submit(() -> session.typeHierarchySupertypesFuture(params.getItem()))
        .thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySubtypes(
      final TypeHierarchySubtypesParams params) {
    return worker
        .submit(() -> session.typeHierarchySubtypesFuture(params.getItem()))
        .thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<List<? extends TextEdit>> formatting(
      final DocumentFormattingParams params) {
    if (!formattingEnabled) {
      return CompletableFuture.completedFuture(List.of());
    }

    final var uri = params.getTextDocument().getUri();
    if (ignoreNonFile(uri, "formatting")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return worker.submit(() -> session.format(uri));
  }

  @Override
  public CompletableFuture<List<? extends TextEdit>> rangeFormatting(
      final DocumentRangeFormattingParams params) {
    // Capability is never advertised; delegating to the whole-document formatter would ignore the
    // requested range and reformat — and reorder/remove imports across — the entire file, so a
    // client that calls this anyway gets no edits regardless of profile.
    return CompletableFuture.completedFuture(List.of());
  }

  @Override
  public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(
      final DocumentOnTypeFormattingParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    final String ch = params.getCh();
    final String chCode = ch == null || ch.isEmpty() ? "null" : "0x%X".formatted(ch.codePointAt(0));
    LOG.info(
        () ->
            "[onTypeFormatting] %s line=%d character=%d ch=%s"
                .formatted(uri, pos.getLine(), pos.getCharacter(), chCode));

    // TODO Implement conservative indentation edits for supported on-type triggers.
    return CompletableFuture.completedFuture(List.of());
  }

  public CompletableFuture<List<Diagnostic>> diagnosticsFuture(
      final String uri, final String content, final int version) {
    return worker
        .submit(() -> session.diagnosticsFuture(uri, content, version))
        .thenCompose(f -> f);
  }

  public CompletableFuture<List<String>> staleModulesFuture() {
    return worker.submit(() -> session.staleModules());
  }

  public CompletableFuture<Void> refreshStaleModulesFuture() {
    return worker.submit(
        () -> {
          session.refreshStaleScan();
          return null;
        });
  }

  public CompletableFuture<List<? extends SymbolInformation>> workspaceSymbolFuture(
      final String query) {
    return worker.submit(() -> session.workspaceSymbol(query));
  }

  public CompletableFuture<LaunchOutcome> runTestFuture(
      final String moduleRel, final List<TestSelection> selections, final String token) {
    return worker
        .submit(() -> session.runTestFuture(moduleRel, selections, token))
        .thenCompose(f -> f);
  }

  CompletableFuture<LaunchOutcome> runMainFuture(
      final String moduleRel, final String mainClass, final String token) {
    return worker
        .submit(() -> session.runMainFuture(moduleRel, mainClass, token))
        .thenCompose(f -> f);
  }

  CompletableFuture<DebugStartResult> debugTestFuture(
      final String moduleRel, final List<TestSelection> selections, final String token) {
    return worker.submit(() -> session.debugTest(moduleRel, selections, token));
  }

  CompletableFuture<DebugStartResult> debugMainFuture(
      final String moduleRel, final String mainClass, final String token) {
    return worker.submit(() -> session.debugMain(moduleRel, mainClass, token));
  }

  CompletableFuture<LaunchOutcome> runNamedFuture(final String name, final String token) {
    return worker.submit(() -> session.runNamedFuture(name, token)).thenCompose(f -> f);
  }

  CompletableFuture<DebugStartResult> debugNamedFuture(final String name, final String token) {
    return worker.submit(() -> session.debugNamed(name, token));
  }

  CompletableFuture<List<RunConfigInfo>> runConfigsFuture() {
    return worker.submit(() -> session.listRunConfigs());
  }

  CompletableFuture<RunConfigWriter.Saved> saveRunConfigFuture(
      final String name,
      final String moduleRel,
      final RunKind kind,
      final String mainClass,
      final List<TestSelection> selectors,
      final boolean overwrite) {
    return worker.submit(
        () -> session.saveRunConfig(name, moduleRel, kind, mainClass, selectors, overwrite));
  }

  public CompletableFuture<List<RunTarget>> runnablesFuture(final String uri) {
    return worker.submit(() -> session.runnablesFuture(uri)).thenCompose(f -> f);
  }

  CompletableFuture<Object> cancelRunFuture(final String token) {
    return worker.submit(
        () -> {
          session.cancelRun(token);
          return null;
        });
  }

  CompletableFuture<String> refreshResourceFuture(final String uri) {
    return worker.submit(() -> session.refreshResource(uri).map(Path::toString).orElse(null));
  }

  CompletableFuture<List<Location>> instantiationsFuture(final String uri, final Position pos) {
    return withProgress(
        null,
        (cancelChecker, progress) ->
            worker
                .submit(() -> session.instantiationsFuture(uri, pos, cancelChecker, progress))
                .thenCompose(f -> f));
  }

  CompletableFuture<TypeHierarchyExplorerResult> typeHierarchyExplorerFuture(
      final String uri, final Position pos) {
    return worker.submit(() -> session.typeHierarchyExplorerFuture(uri, pos)).thenCompose(f -> f);
  }

  CompletableFuture<MissingImportsResult> missingImportsFuture(final String uri) {
    return worker.submit(() -> session.missingImportsFuture(uri)).thenCompose(f -> f);
  }

  CompletableFuture<CreateTypeResult> createTypeFuture(final CreateTypeArgs args) {
    return worker.submit(() -> session.createType(args));
  }

  CompletableFuture<List<String>> modulesFuture() {
    return worker.submit(() -> session.modules());
  }

  CompletableFuture<List<PackageEntry>> packagesFuture(final String moduleRel) {
    return worker.submit(() -> session.packages(moduleRel));
  }

  CompletableFuture<ContextInfo> resolveContextFuture(final String uri) {
    return worker.submit(() -> session.resolveContext(uri));
  }

  CompletableFuture<DirRun> dirRunFuture(final String uri) {
    return worker.submit(() -> session.dirRun(uri));
  }

  CompletableFuture<List<TestSource>> testSourcesFuture(
      final String moduleRel, final List<String> classNames) {
    return worker.submit(() -> session.testSources(moduleRel, classNames));
  }

  CompletableFuture<List<ResourceEntry>> resourcesFuture() {
    return worker.submit(session::resources);
  }

  CompletableFuture<String> resourceOpenFuture(final String jar, final String entry) {
    return worker.submit(() -> session.resourceOpen(jar, entry));
  }
}
