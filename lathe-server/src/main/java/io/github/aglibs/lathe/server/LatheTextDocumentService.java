package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.CompletionOutcome;
import io.github.aglibs.lathe.core.LatheUri;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.logging.Logger;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.services.TextDocumentService;

// The LSP document service: turns lsp4j requests and notifications into session work on the event
// loop, through the SessionHost it shares with the workspace service.
public final class LatheTextDocumentService implements TextDocumentService {

  private static final Logger LOG = Logger.getLogger(LatheTextDocumentService.class.getName());
  private final SessionHost host;
  // Whether the client can apply a RenameFile resource operation; gates renaming a public top-level
  // type (which must move its .java file). Written in initialize, read per rename request.
  private volatile boolean fileRenameSupported;

  // Whether the client resolves code actions (CompileCheckedActions). Written in initialize.
  private volatile boolean codeActionResolveSupported;

  LatheTextDocumentService(final SessionHost host) {
    this.host = host;
  }

  void setFileRenameSupported(final boolean supported) {
    fileRenameSupported = supported;
  }

  void setCodeActionResolveSupported(final boolean supported) {
    codeActionResolveSupported = supported;
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
    host.execute(session -> session.onOpen(uri, content, version));
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
    host.execute(session -> session.onChange(uri, content, version));
  }

  @Override
  public void didClose(final DidCloseTextDocumentParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    if (ignoreNonFile(uri, "close")) {
      return;
    }

    host.execute(session -> session.onClose(uri));
  }

  @Override
  public void didSave(final DidSaveTextDocumentParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    if (ignoreNonFile(uri, "save")) {
      return;
    }

    final var content = params.getText();
    host.execute(session -> session.onSave(uri, content));
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

    return host.onLoopFuture(session -> session.features().completionFuture(uri, pos, context))
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

    return host.onLoopFuture(
        session ->
            session
                .features()
                .codeActionFuture(uri, params.getRange(), context, codeActionResolveSupported));
  }

  @Override
  public CompletableFuture<CodeAction> resolveCodeAction(final CodeAction unresolved) {
    return host.onLoopFuture(session -> session.features().resolveCodeActionFuture(unresolved));
  }

  @Override
  public CompletableFuture<SemanticTokens> semanticTokensFull(final SemanticTokensParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    if (ignoreNonFile(uri, "semanticTokens")) {
      return CompletableFuture.completedFuture(new SemanticTokens(List.of()));
    }

    return host.onLoopFuture(session -> session.features().semanticTokensFuture(uri));
  }

  @Override
  public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
      final DocumentSymbolParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    if (ignoreNonFile(uri, "documentSymbol")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return host.onLoopFuture(session -> session.features().documentSymbolFuture(uri))
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

    return host.onLoopFuture(session -> session.features().foldingRangeFuture(uri));
  }

  @Override
  public CompletableFuture<List<? extends Location>> references(final ReferenceParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    final var pos = params.getPosition();
    final var incl = params.getContext().isIncludeDeclaration();
    if (ignoreNonFile(uri, "references")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return host.onLoopCompose(
        params.getWorkDoneToken(),
        (session, cancelChecker, progress) ->
            session.search().referencesFuture(uri, pos, incl, cancelChecker, progress));
  }

  @Override
  public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>>
      prepareRename(final PrepareRenameParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "prepareRename")) {
      return CompletableFuture.completedFuture(null);
    }

    return host.onLoopFuture(
        session -> session.renames().prepareRenameFuture(uri, pos, fileRenameSupported));
  }

  @Override
  public CompletableFuture<WorkspaceEdit> rename(final RenameParams params) {
    // The editor applies and saves the edit, so the session tracks it as one transaction. The
    // response is sent only once the transaction is open, ahead of the edit's document events.
    return renameEdit(params)
        .thenCompose(
            edit ->
                host.onLoop(
                    session -> {
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

    return host.onLoopCompose(
        null,
        (session, cancelChecker, progress) ->
            session
                .renames()
                .renameFuture(uri, pos, newName, fileRenameSupported, cancelChecker, progress));
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
        host.onLoopFuture(session -> session.search().documentHighlightFuture(uri, pos));
    return work.thenApply(highlights -> highlights);
  }

  @Override
  public CompletableFuture<SignatureHelp> signatureHelp(final SignatureHelpParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "signatureHelp")) {
      return CompletableFuture.completedFuture(null);
    }

    return host.onLoopFuture(session -> session.features().signatureHelpFuture(uri, pos));
  }

  @Override
  public CompletableFuture<Hover> hover(final HoverParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "hover")) {
      return CompletableFuture.completedFuture(null);
    }

    return host.onLoopFuture(session -> session.features().hoverFuture(uri, pos));
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      definition(final DefinitionParams params) {
    return locations(
        "definition",
        params,
        (uri, pos) -> host.onLoopFuture(session -> session.features().definitionFuture(uri, pos)));
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      declaration(final DeclarationParams params) {
    return locations(
        "declaration",
        params,
        (uri, pos) -> host.onLoopFuture(session -> session.features().declarationFuture(uri, pos)));
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      implementation(final ImplementationParams params) {
    return locations(
        "implementation",
        params,
        (uri, pos) ->
            host.onLoopFuture(session -> session.search().implementationFuture(uri, pos)));
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      typeDefinition(final TypeDefinitionParams params) {
    return locations(
        "typeDefinition",
        params,
        (uri, pos) ->
            host.onLoopFuture(session -> session.features().typeDefinitionFuture(uri, pos)));
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

    return request.apply(uri, params.getPosition());
  }

  @Override
  public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(
      final CallHierarchyPrepareParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "prepareCallHierarchy")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return host.onLoopFuture(session -> session.search().prepareCallHierarchyFuture(uri, pos));
  }

  @Override
  public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(
      final CallHierarchyIncomingCallsParams params) {
    return host.onLoopCompose(
        params.getWorkDoneToken(),
        (session, cancelChecker, progress) ->
            session.search().incomingCallsFuture(params.getItem(), cancelChecker, progress));
  }

  @Override
  public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(
      final CallHierarchyOutgoingCallsParams params) {
    return host.onLoopFuture(session -> session.search().outgoingCallsFuture(params.getItem()));
  }

  @Override
  public CompletableFuture<List<TypeHierarchyItem>> prepareTypeHierarchy(
      final TypeHierarchyPrepareParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    final var pos = params.getPosition();
    if (ignoreNonFile(uri, "prepareTypeHierarchy")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return host.onLoopFuture(session -> session.features().prepareTypeHierarchyFuture(uri, pos));
  }

  @Override
  public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySupertypes(
      final TypeHierarchySupertypesParams params) {
    return host.onLoopFuture(
        session -> session.features().typeHierarchySupertypesFuture(params.getItem()));
  }

  @Override
  public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySubtypes(
      final TypeHierarchySubtypesParams params) {
    return host.onLoopFuture(
        session -> session.features().typeHierarchySubtypesFuture(params.getItem()));
  }

  @Override
  public CompletableFuture<List<? extends TextEdit>> formatting(
      final DocumentFormattingParams params) {
    final String uri = LatheUri.canonical(params.getTextDocument().getUri());
    if (ignoreNonFile(uri, "formatting")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return host.onLoopFuture(
        session -> session.formats().formatFuture(uri).thenApply(edits -> edits));
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

  private CompletableFuture<List<? extends TextEdit>> formatRanges(
      final String uri, final List<Range> ranges) {
    if (ignoreNonFile(uri, "rangeFormatting")) {
      return CompletableFuture.completedFuture(List.of());
    }

    return host.onLoopFuture(
        session -> session.formats().formatRangesFuture(uri, ranges).thenApply(edits -> edits));
  }
}
