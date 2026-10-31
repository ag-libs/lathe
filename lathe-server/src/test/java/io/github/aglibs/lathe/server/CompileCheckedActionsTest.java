package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import io.github.aglibs.lathe.server.analysis.ExtractionSupport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

class CompileCheckedActionsTest {

  private static final String URI = "file:///Test.java";
  private static final String CONTENT = "class Test { int a = 1; }";
  private static final List<TextEdit> EDITS =
      List.of(new TextEdit(new Range(new Position(0, 21), new Position(0, 22)), "2"));

  @Test
  void deferAndResolve_compilingEdit_attachedOnlyWhenResolved() {
    final CodeAction method = action(ExtractionSupport.METHOD_KIND);
    final CodeAction variable = action(ExtractionSupport.VARIABLE_KIND);
    final List<Either<Command, CodeAction>> listed =
        CompileCheckedActions.defer(
            URI, 3, List.of(Either.forRight(method), Either.forRight(variable)));

    assertThat(listed.get(0).getRight().getEdit()).isNull();
    assertThat(listed.get(1).getRight()).isSameAs(variable);

    final var compiled = new AtomicReference<String>();
    final CodeAction resolved =
        CompileCheckedActions.resolve(
                throughJson(listed.get(0).getRight()),
                uri -> new OpenDocument(URI, CONTENT, 3, 0),
                (uri, content) -> {
                  compiled.set(content);
                  return CompletableFuture.completedFuture(List.of());
                })
            .join();

    assertThat(compiled.get()).isEqualTo("class Test { int a = 2; }");
    assertThat(resolved.getEdit().getChanges()).isEqualTo(Map.of(URI, EDITS));
    assertThat(resolved.getDisabled()).isNull();
  }

  @Test
  void resolve_errorOrChangedFileOrForeignAction_neverAttachesTheEdit() {
    final CodeAction listed =
        CompileCheckedActions.defer(
                URI, 3, List.of(Either.forRight(action(ExtractionSupport.METHOD_KIND))))
            .get(0)
            .getRight();
    final var error = new Diagnostic(new Range(), "cannot find symbol");
    error.setSeverity(DiagnosticSeverity.Error);

    final CodeAction broken =
        CompileCheckedActions.resolve(
                throughJson(listed),
                uri -> new OpenDocument(URI, CONTENT, 3, 0),
                (uri, content) -> CompletableFuture.completedFuture(List.of(error)))
            .join();
    assertThat(broken.getEdit()).isNull();
    assertThat(broken.getDisabled().getReason()).isEqualTo("Would not compile: cannot find symbol");

    final CodeAction stale =
        CompileCheckedActions.resolve(
                throughJson(listed),
                uri -> new OpenDocument(URI, CONTENT, 4, 0),
                (uri, content) -> null)
            .join();
    assertThat(stale.getEdit()).isNull();
    assertThat(stale.getDisabled().getReason()).contains("changed");

    final CodeAction foreign = action(ExtractionSupport.VARIABLE_KIND);
    assertThat(CompileCheckedActions.resolve(foreign, uri -> null, (uri, content) -> null).join())
        .isSameAs(foreign);
  }

  private static CodeAction action(final String kind) {
    final var action = new CodeAction("Extract");
    action.setKind(kind);
    action.setEdit(new WorkspaceEdit(Map.of(URI, EDITS)));
    return action;
  }

  // The action as the client sends it back: through lsp4j's JSON handling, data and all.
  private static CodeAction throughJson(final CodeAction action) {
    final Gson gson = new MessageJsonHandler(Map.of()).getGson();
    return gson.fromJson(gson.toJson(action), CodeAction.class);
  }
}
