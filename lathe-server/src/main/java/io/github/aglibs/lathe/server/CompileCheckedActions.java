package io.github.aglibs.lathe.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.analysis.ExtractionSupport;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Logger;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionDisabled;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// Code actions whose edit is offered only once it compiles. Listing the actions computes the edit
// cheaply and carries it in the action's data; codeAction/resolve, which the client sends only for
// the action picked, compiles the rewritten document transiently and attaches the edit when it has
// no error. Otherwise the action resolves disabled, with the first error as the reason. For edits
// built from an approximate analysis (extract method), so a miss never reaches the buffer.
final class CompileCheckedActions {

  private static final Logger LOG = Logger.getLogger(CompileCheckedActions.class.getName());
  private static final Set<String> CHECKED_KINDS = Set.of(ExtractionSupport.METHOD_KIND);
  private static final Gson GSON = new Gson();

  private CompileCheckedActions() {}

  // The edit of a listed action, with the document version it was computed against.
  private record Pending(String uri, int version, List<TextEdit> edits) {

    Pending {
      ValidCheck.check().notNull(uri, "uri").notNull(edits, "edits").validate();
      edits = List.copyOf(edits);
    }
  }

  // Each checked-kind action without its edit, which moves into its data; the rest unchanged.
  static List<Either<Command, CodeAction>> defer(
      final String uri, final int version, final List<Either<Command, CodeAction>> actions) {
    return actions.stream()
        .map(
            action ->
                action.isRight() && CHECKED_KINDS.contains(action.getRight().getKind())
                    ? Either.<Command, CodeAction>forRight(
                        deferred(uri, version, action.getRight()))
                    : action)
        .toList();
  }

  private static CodeAction deferred(final String uri, final int version, final CodeAction action) {
    final var deferred = new CodeAction(action.getTitle());
    deferred.setKind(action.getKind());
    deferred.setData(encode(new Pending(uri, version, action.getEdit().getChanges().get(uri))));
    return deferred;
  }

  static CompletableFuture<CodeAction> resolve(
      final CodeAction action,
      final Function<String, OpenDocument> documents,
      final BiFunction<String, String, CompletableFuture<List<Diagnostic>>> compile) {
    final Pending pending = decode(action.getData());
    if (pending == null) {
      return CompletableFuture.completedFuture(action);
    }

    final OpenDocument document = documents.apply(pending.uri());
    if (document == null || document.version() != pending.version()) {
      action.setDisabled(new CodeActionDisabled("The file changed since the action was offered"));
      return CompletableFuture.completedFuture(action);
    }

    final var t = Stopwatch.start();
    return compile
        .apply(pending.uri(), SourceLocator.applyEdits(document.content(), pending.edits()))
        .thenApply(diagnostics -> checked(action, pending, diagnostics, t));
  }

  private static CodeAction checked(
      final CodeAction action,
      final Pending pending,
      final List<Diagnostic> diagnostics,
      final Stopwatch t) {
    final Diagnostic error =
        diagnostics.stream()
            .filter(d -> d.getSeverity() == DiagnosticSeverity.Error)
            .findFirst()
            .orElse(null);
    LOG.info(
        () ->
            "[codeAction:resolve] %s '%s' %dms %s"
                .formatted(
                    pending.uri(),
                    action.getTitle(),
                    t.elapsedMs(),
                    error == null ? "compiles" : "refused"));
    if (error == null) {
      action.setEdit(new WorkspaceEdit(Map.of(pending.uri(), pending.edits())));
    } else {
      action.setDisabled(new CodeActionDisabled("Would not compile: %s".formatted(message(error))));
    }

    return action;
  }

  private static String message(final Diagnostic diagnostic) {
    return diagnostic.getMessage().isLeft()
        ? diagnostic.getMessage().getLeft().lines().findFirst().orElse("")
        : diagnostic.getMessage().getRight().getValue();
  }

  private static JsonObject encode(final Pending pending) {
    final var json = new JsonObject();
    json.addProperty("uri", pending.uri());
    json.addProperty("version", pending.version());
    json.add("edits", GSON.toJsonTree(pending.edits()));
    return json;
  }

  private static Pending decode(final Object data) {
    if (!(data instanceof final JsonObject json)
        || !json.has("uri")
        || !json.has("version")
        || !json.has("edits")) {
      return null;
    }

    return new Pending(
        json.get("uri").getAsString(),
        json.get("version").getAsInt(),
        List.of(GSON.fromJson(json.get("edits"), TextEdit[].class)));
  }
}
