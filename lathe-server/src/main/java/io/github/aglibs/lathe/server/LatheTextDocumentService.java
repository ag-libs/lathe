package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.RunKind;
import io.github.aglibs.lathe.server.analysis.MissingImportsResult;
import io.github.aglibs.lathe.server.analysis.TypeHierarchyExplorerResult;
import io.github.aglibs.lathe.server.analysis.completion.CompletionOutcome;
import io.github.aglibs.lathe.server.debug.DebugStartResult;
import io.github.aglibs.lathe.server.engine.LatheNotes;
import io.github.aglibs.lathe.server.run.LaunchOutcome;
import io.github.aglibs.lathe.server.run.RunConfigInfo;
import io.github.aglibs.lathe.server.run.RunConfigWriter;
import io.github.aglibs.lathe.server.run.RunTarget;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Logger;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
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

  private final ServerEventLoop eventLoop = new ServerEventLoop();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final long debounceMs;
  private ProgressReporter progressReporter;
  // Created, read and written only on the event loop: reach it through a lambda the loop runs
  // (eventLoop.submit(() -> session.x())), never a method reference (session::x), which reads the
  // field on the caller's thread, where it may still be null.
  private WorkspaceSession session;
  // Written in initialize, read in formatting; volatile publishes it safely across lsp4j's
  // request-handling thread(s). The engine runs on the event loop, not here. Null = formatting
  // disabled.
  private volatile FormatEngine formatEngine;
  private volatile boolean formatterPinned;
  // Whether the client can apply a RenameFile resource operation; gates renaming a public top-level
  // type (which must move its .java file). Written in initialize, read per rename request.
  private volatile boolean fileRenameSupported;

  // Whether the client resolves code actions (CompileCheckedActions). Written in initialize.
  private volatile boolean codeActionResolveSupported;

  public LatheTextDocumentService() {
    this(DEFAULT_DEBOUNCE_MS);
  }

  LatheTextDocumentService(final long debounceMs) {
    this.debounceMs = debounceMs;
  }

  public void connect(final LanguageClient client) {
    progressReporter = new ProgressReporter(client);
    eventLoop.execute(
        () -> session = new WorkspaceSession(client, progressReporter, eventLoop, debounceMs));
  }

  void setWorkDoneProgressSupported(final boolean supported) {
    progressReporter.setSupported(supported);
  }

  void setFormatEngine(final FormatEngine engine) {
    formatEngine = engine;
  }

  public void setFileRenameSupported(final boolean supported) {
    fileRenameSupported = supported;
  }

  void setCodeActionResolveSupported(final boolean supported) {
    codeActionResolveSupported = supported;
  }

  // On the event loop like all session state; queued after connect, so it is set before initialize.
  public void setDocumentsFollowDisk(final boolean follow) {
    eventLoop.execute(() -> session.setDocumentsFollowDisk(follow));
  }

  void cancelProgress(final WorkDoneProgressCancelParams params) {
    progressReporter.cancel(params.getToken());
  }

  public void initialize(final Path workspaceRoot) {
    eventLoop.execute(() -> session.initialize(workspaceRoot));
  }

  // Test/tool seam: run one reconcile pass and complete once its in-process recompiles finish.
  CompletableFuture<Void> reconcileNow() {
    return reconcileNow(false);
  }

  CompletableFuture<Void> reconcileNow(final boolean eager) {
    return eventLoop.submitCompose(() -> session.reconcileNow(eager));
  }

  /**
   * Freshen the mirror for a verify_change call, reporting what recompiled or why it was refused.
   */
  public CompletableFuture<ReconcileOutcome> reconcileForVerify() {
    return eventLoop.submitCompose(() -> session.reconcileForVerify());
  }

  /** Write files the caller changed on disk itself, completing once the mirror has them. */
  public CompletableFuture<Void> writeReported(final Set<Path> changed) {
    return eventLoop.submitCompose(() -> session.writeReported(changed));
  }

  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }

    // On the loop, as the last thing it runs: connect() assigns the session there.
    eventLoop.close(
        () -> {
          if (session != null) {
            session.close();
          }
        });
  }

  @Override
  public void didOpen(final DidOpenTextDocumentParams params) {
    final var doc = params.getTextDocument();
    final String uri = LatheUri.canonical(doc.getUri());
    if (ignoreNonFile(uri, "open")) {
      return;
    }

    final var content = doc.getText();
    final var version = doc.getVersion();
    eventLoop.execute(() -> session.onOpen(uri, content, version));
  }

  @Override
  public void didChange(final DidChangeTextDocumentParams params) {
    final var doc = params.getTextDocument();
    final String uri = LatheUri.canonical(doc.getUri());
    if (ignoreNonFile(uri, "change")) {
      return;
    }

    final var content = params.getContentChanges().get(0).getText();
    final var version = doc.getVersion();
    eventLoop.execute(() -> session.onChange(uri, content, version));
  }

  @Override
  public void didClose(final DidCloseTextDocumentParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    if (ignoreNonFile(uri, "close")) {
      return;
    }

    eventLoop.execute(() -> session.onClose(uri));
  }

  @Override
  public void didSave(final DidSaveTextDocumentParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    if (ignoreNonFile(uri, "save")) {
      return;
    }

    final var content = params.getText();
    eventLoop.execute(() -> session.onSave(uri, content));
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
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    final var pos = params.getPosition();
    final var ctx = params.getContext();
    final var context = ctx != null ? ctx : new CompletionContext(CompletionTriggerKind.Invoked);
    if (ignoreNonFile(uri, "completion")) {
      return CompletableFuture.completedFuture(Either.forLeft(List.of()));
    }

    return eventLoop
        .submitCompose(() -> session.completionFuture(uri, pos, context))
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
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    final var context = params.getContext();
    if (ignoreNonFile(uri, "codeAction")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return eventLoop.submitCompose(
        () ->
            session.codeActionFuture(uri, params.getRange(), context, codeActionResolveSupported));
  }

  @Override
  public CompletableFuture<CodeAction> resolveCodeAction(final CodeAction unresolved) {
    return eventLoop.submitCompose(() -> session.resolveCodeActionFuture(unresolved));
  }

  @Override
  public CompletableFuture<SemanticTokens> semanticTokensFull(final SemanticTokensParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    if (ignoreNonFile(uri, "semanticTokens")) {
      return CompletableFuture.completedFuture(new SemanticTokens(List.of()));
    }

    return eventLoop.submitCompose(() -> session.semanticTokensFuture(uri));
  }

  @Override
  public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
      final DocumentSymbolParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    if (ignoreNonFile(uri, "documentSymbol")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return eventLoop
        .submitCompose(() -> session.documentSymbolFuture(uri))
        .thenApply(LatheTextDocumentService::documentSymbolResult);
  }

  static List<Either<SymbolInformation, DocumentSymbol>> documentSymbolResult(
      final List<DocumentSymbol> symbols) {
    return symbols.stream().map(Either::<SymbolInformation, DocumentSymbol>forRight).toList();
  }

  @Override
  public CompletableFuture<List<FoldingRange>> foldingRange(
      final FoldingRangeRequestParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    if (ignoreNonFile(uri, "foldingRange")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return eventLoop.submitCompose(() -> session.foldingRangeFuture(uri));
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
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    final var pos = params.getPosition();
    final var incl = params.getContext().isIncludeDeclaration();
    if (ignoreNonFile(uri, "references")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return withProgress(
        params.getWorkDoneToken(),
        (cancelChecker, progress) ->
            eventLoop.submitCompose(
                () -> session.referencesFuture(uri, pos, incl, cancelChecker, progress)));
  }

  @Override
  public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>>
      prepareRename(final PrepareRenameParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "prepareRename")) {
      return CompletableFuture.completedFuture(null);
    }

    return eventLoop.submitCompose(
        () -> session.prepareRenameFuture(uri, pos, fileRenameSupported));
  }

  @Override
  public CompletableFuture<WorkspaceEdit> rename(final RenameParams params) {
    // The editor applies and saves the edit, so the session tracks it as one transaction. The
    // response is sent only once the transaction is open, ahead of the edit's document events.
    return renameEdit(params)
        .thenCompose(
            edit ->
                eventLoop.submit(
                    () -> {
                      session.beginRenameTransaction(edit);
                      return edit;
                    }));
  }

  // The rename's edit, before the transaction that applies it opens.
  private CompletableFuture<WorkspaceEdit> renameEdit(final RenameParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    final var pos = params.getPosition();
    final var newName = params.getNewName();
    if (ignoreNonFile(uri, "rename")) {
      return CompletableFuture.completedFuture(null);
    }

    return withProgress(
        null,
        (cancelChecker, progress) ->
            eventLoop.submitCompose(
                () ->
                    session.renameFuture(
                        uri, pos, newName, fileRenameSupported, cancelChecker, progress)));
  }

  @Override
  public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(
      final DocumentHighlightParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "documentHighlight")) {
      return CompletableFuture.completedFuture(List.of());
    }

    final CompletableFuture<List<DocumentHighlight>> work =
        eventLoop.submitCompose(() -> session.documentHighlightFuture(uri, pos));
    return work.thenApply(highlights -> highlights);
  }

  @Override
  public CompletableFuture<SignatureHelp> signatureHelp(final SignatureHelpParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "signatureHelp")) {
      return CompletableFuture.completedFuture(null);
    }

    return eventLoop.submitCompose(() -> session.signatureHelpFuture(uri, pos));
  }

  @Override
  public CompletableFuture<Hover> hover(final HoverParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "hover")) {
      return CompletableFuture.completedFuture(null);
    }

    return eventLoop.submitCompose(() -> session.hoverFuture(uri, pos));
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      definition(final DefinitionParams params) {
    return locations("definition", params, (uri, pos) -> session.definitionFuture(uri, pos));
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      declaration(final DeclarationParams params) {
    return locations("declaration", params, (uri, pos) -> session.declarationFuture(uri, pos));
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      implementation(final ImplementationParams params) {
    return locations(
        "implementation", params, (uri, pos) -> session.implementationFuture(uri, pos));
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      typeDefinition(final TypeDefinitionParams params) {
    return locations(
        "typeDefinition", params, (uri, pos) -> session.typeDefinitionFuture(uri, pos));
  }

  private CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      locations(
          final String operation,
          final TextDocumentPositionParams params,
          final BiFunction<
                  String,
                  Position,
                  CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>>
              request) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    if (ignoreNonFile(uri, operation)) {
      return emptyLocationResult();
    }

    return eventLoop.submitCompose(() -> request.apply(uri, params.getPosition()));
  }

  @Override
  public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(
      final CallHierarchyPrepareParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "prepareCallHierarchy")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return eventLoop.submitCompose(() -> session.prepareCallHierarchyFuture(uri, pos));
  }

  @Override
  public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(
      final CallHierarchyIncomingCallsParams params) {
    return withProgress(
        params.getWorkDoneToken(),
        (cancelChecker, progress) ->
            eventLoop.submitCompose(
                () -> session.incomingCallsFuture(params.getItem(), cancelChecker, progress)));
  }

  @Override
  public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(
      final CallHierarchyOutgoingCallsParams params) {
    return eventLoop.submitCompose(() -> session.outgoingCallsFuture(params.getItem()));
  }

  @Override
  public CompletableFuture<List<TypeHierarchyItem>> prepareTypeHierarchy(
      final TypeHierarchyPrepareParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "prepareTypeHierarchy")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return eventLoop.submitCompose(() -> session.prepareTypeHierarchyFuture(uri, pos));
  }

  @Override
  public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySupertypes(
      final TypeHierarchySupertypesParams params) {
    return eventLoop.submitCompose(() -> session.typeHierarchySupertypesFuture(params.getItem()));
  }

  @Override
  public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySubtypes(
      final TypeHierarchySubtypesParams params) {
    return eventLoop.submitCompose(() -> session.typeHierarchySubtypesFuture(params.getItem()));
  }

  @Override
  public CompletableFuture<List<? extends TextEdit>> formatting(
      final DocumentFormattingParams params) {
    final FormatEngine engine = formatEngine;
    if (engine == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    if (ignoreNonFile(uri, "formatting")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return eventLoop.submit(() -> session.format(uri, engine));
  }

  // The formatter the workspace style file pins, for a client that sends no initializationOptions.
  public void useWorkspaceFormatter(final Path root) {
    final FormatterSpec spec = FormatEngines.workspaceSpec(root);
    formatterPinned = spec != null;
    formatEngine = spec == null ? null : FormatEngines.forSpec(spec, root, this);
  }

  // The whole-file formatting edits, refused (not empty) when there is no formatter or it fails.
  // Only a pinned formatter whose jars are missing is one a sync can supply.
  public CompletableFuture<List<TextEdit>> formatFuture(final String uri) {
    final FormatEngine engine = formatEngine;
    if (engine == null) {
      final String reason =
          formatterPinned
              ? "The project's formatter is not resolved yet: run `%s`."
                  .formatted(LatheLayout.SYNC_SCRIPT_COMMAND)
              : "No formatter for this workspace: the project pins none (no Spotless Java step), so a sync does not add one; name one in %s to format here."
                  .formatted(LatheLayout.STYLE_SHARED_FILE);
      return CompletableFuture.failedFuture(
          new ResponseErrorException(
              new ResponseError(ResponseErrorCode.RequestFailed, reason, null)));
    }

    final String canonical = LatheUri.canonical(uri);
    return eventLoop.submit(() -> session.formatOrRefuse(canonical, engine));
  }

  @Override
  public CompletableFuture<List<? extends TextEdit>> rangeFormatting(
      final DocumentRangeFormattingParams params) {
    return formatRanges(
        LatheUri.canonical(params.getTextDocument().getUri()), List.of(params.getRange()));
  }

  @Override
  public CompletableFuture<List<? extends TextEdit>> rangesFormatting(
      final DocumentRangesFormattingParams params) {
    return formatRanges(LatheUri.canonical(params.getTextDocument().getUri()), params.getRanges());
  }

  // Only in-process engines format ranges; whole-file ones do not advertise the capability, so a
  // stray request gets no edits.
  // A format engine calls this while the event loop runs session.format, so it reads the session
  // directly rather than through the event loop.
  Optional<String> moduleRelOf(final Path file) {
    return session.moduleRelOf(file);
  }

  Optional<Path> buildTool() {
    return session.buildTool();
  }

  // Reads session state on the event loop, for a caller outside it such as a test.
  <T> CompletableFuture<T> onLoop(final Function<WorkspaceSession, T> read) {
    return eventLoop.submit(() -> read.apply(session));
  }

  // Session work a caller outside the loop starts as one request, the in-process engine above all:
  // it runs on the loop, and so does what it chains on worker results. The cancel checker is tied
  // to the returned future, so cancelling the request stops a search at its next check.
  @FunctionalInterface
  interface LoopWork<T> {
    CompletableFuture<T> start(
        WorkspaceSession session, CancelChecker cancelChecker, ProgressReporter.Task progress);
  }

  <T> CompletableFuture<T> onLoopCompose(final LoopWork<T> work) {
    return withProgress(
        null,
        (cancelChecker, progress) ->
            eventLoop.submitCompose(() -> work.start(session, cancelChecker, progress)));
  }

  private CompletableFuture<List<? extends TextEdit>> formatRanges(
      final String uri, final List<Range> ranges) {
    final FormatEngine engine = formatEngine;
    if (engine == null || !engine.formatsRanges() || ignoreNonFile(uri, "rangeFormatting")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return eventLoop.submit(() -> session.formatRanges(uri, ranges, engine));
  }

  @Override
  public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(
      final DocumentOnTypeFormattingParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
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
    return eventLoop.submitCompose(() -> session.diagnosticsFuture(uri, content, version));
  }

  /** What an answer about {@code files} cannot vouch for, after the last write pass. */
  public CompletableFuture<LatheNotes> notesFuture(final List<Path> files) {
    return eventLoop.submit(() -> session.notes(files));
  }

  public CompletableFuture<List<? extends SymbolInformation>> workspaceSymbolFuture(
      final String query, final boolean reactorOnly) {
    return eventLoop.submit(() -> session.workspaceSymbol(query, reactorOnly));
  }

  public CompletableFuture<LaunchOutcome> runTestFuture(
      final String moduleRel, final List<TestSelection> selections, final String token) {
    return eventLoop.submitCompose(() -> session.runTestFuture(moduleRel, selections, token));
  }

  CompletableFuture<LaunchOutcome> runMainFuture(
      final String moduleRel, final String mainClass, final String token) {
    return eventLoop.submitCompose(() -> session.runMainFuture(moduleRel, mainClass, token));
  }

  CompletableFuture<DebugStartResult> debugTestFuture(
      final String moduleRel, final List<TestSelection> selections, final String token) {
    return eventLoop.submitCompose(() -> session.debugTest(moduleRel, selections, token));
  }

  CompletableFuture<DebugStartResult> debugMainFuture(
      final String moduleRel, final String mainClass, final String token) {
    return eventLoop.submitCompose(() -> session.debugMain(moduleRel, mainClass, token));
  }

  CompletableFuture<LaunchOutcome> runNamedFuture(final String name, final String token) {
    return eventLoop.submitCompose(() -> session.runNamedFuture(name, token));
  }

  CompletableFuture<DebugStartResult> debugNamedFuture(final String name, final String token) {
    return eventLoop.submitCompose(() -> session.debugNamed(name, token));
  }

  CompletableFuture<List<RunConfigInfo>> runConfigsFuture() {
    return eventLoop.submit(() -> session.listRunConfigs());
  }

  CompletableFuture<RunConfigWriter.Saved> saveRunConfigFuture(
      final String name,
      final String moduleRel,
      final RunKind kind,
      final String mainClass,
      final List<TestSelection> selectors,
      final boolean overwrite) {
    return eventLoop.submit(
        () -> session.saveRunConfig(name, moduleRel, kind, mainClass, selectors, overwrite));
  }

  public CompletableFuture<List<RunTarget>> runnablesFuture(final String uri) {
    return eventLoop.submitCompose(() -> session.runnablesFuture(uri));
  }

  CompletableFuture<Object> cancelRunFuture(final String token) {
    return eventLoop.submit(
        () -> {
          session.cancelRun(token);
          return null;
        });
  }

  CompletableFuture<String> refreshResourceFuture(final String uri) {
    return eventLoop.submit(() -> session.refreshResource(uri).map(Path::toString).orElse(null));
  }

  CompletableFuture<List<Location>> instantiationsFuture(final String uri, final Position pos) {
    return withProgress(
        null,
        (cancelChecker, progress) ->
            eventLoop.submitCompose(
                () -> session.instantiationsFuture(uri, pos, cancelChecker, progress)));
  }

  CompletableFuture<TypeHierarchyExplorerResult> typeHierarchyExplorerFuture(
      final String uri, final Position pos) {
    return eventLoop.submitCompose(() -> session.typeHierarchyExplorerFuture(uri, pos));
  }

  public CompletableFuture<MissingImportsResult> missingImportsFuture(final String uri) {
    return eventLoop.submitCompose(() -> session.missingImportsFuture(uri));
  }

  CompletableFuture<CreateTypeResult> createTypeFuture(final CreateTypeArgs args) {
    return eventLoop.submit(() -> session.createType(args));
  }

  CompletableFuture<List<String>> modulesFuture() {
    return eventLoop.submit(() -> session.modules());
  }

  CompletableFuture<List<PackageEntry>> packagesFuture(final String moduleRel) {
    return eventLoop.submit(() -> session.packages(moduleRel));
  }

  CompletableFuture<ContextInfo> resolveContextFuture(final String uri) {
    return eventLoop.submit(() -> session.resolveContext(uri));
  }

  CompletableFuture<DirRun> dirRunFuture(final String uri) {
    return eventLoop.submit(() -> session.dirRun(uri));
  }

  CompletableFuture<List<String>> testModulesFuture() {
    return eventLoop.submit(() -> session.testModules());
  }

  CompletableFuture<TestDiscovery> discoverTestsFuture(final String moduleRel) {
    return eventLoop.submitCompose(() -> session.discoverTests(moduleRel));
  }

  CompletableFuture<List<TestSource>> testSourcesFuture(
      final String moduleRel, final List<String> classNames) {
    return eventLoop.submit(() -> session.testSources(moduleRel, classNames));
  }

  CompletableFuture<TestCounterparts> testCounterpartsFuture(final String uri) {
    return eventLoop.submit(() -> session.testCounterparts(uri));
  }

  CompletableFuture<List<ResourceEntry>> resourcesFuture() {
    return eventLoop.submit(() -> session.resources());
  }

  CompletableFuture<String> resourceOpenFuture(final String jar, final String entry) {
    return eventLoop.submit(() -> session.resourceOpen(jar, entry));
  }
}
