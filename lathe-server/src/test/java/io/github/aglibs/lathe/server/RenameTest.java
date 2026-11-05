package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.analysis.SourceLocator.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.github.aglibs.lathe.analysis.RenameProvider;
import io.github.aglibs.lathe.analysis.SourceLocator;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheUri;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.junit.jupiter.api.Test;

// An editor-applied rename: held until its last save, then committed as one write.
class RenameTest extends ServiceTestBase {

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

    try (final var compiles = new CompileLog(tmp)) {
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
    }
  }

  // A refusal reaches the client only as the request's error, so the log keeps why: an invalid name
  // refused up front, and a clash refused once the rename has resolved its target.
  @Test
  void rename_refused_logsTheReasonAtInfo() throws Exception {
    final DepFixture fx = depWithUsers(1);
    service.initialize(tmp);
    awaitStartup();
    final String depSource = Files.readString(fx.dep());
    openDoc(fx.dep(), depSource);
    awaitWorkerIdle();
    final var uri = fx.dep().toUri().toString();
    final var logged = new ConcurrentLinkedQueue<String>();
    final var handler =
        new Handler() {
          @Override
          public void publish(final LogRecord record) {
            logged.add(record.getMessage());
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    final var log = Logger.getLogger(RenameService.class.getName());
    log.addHandler(handler);
    try {
      Map.of("v()", "a.b", "Dep {", "User0")
          .forEach(
              (at, newName) ->
                  assertThat(
                          catchThrowable(
                              () ->
                                  service
                                      .rename(
                                          new RenameParams(
                                              new TextDocumentIdentifier(uri),
                                              offsetToPosition(depSource, depSource.indexOf(at)),
                                              newName))
                                      .get(10, TimeUnit.SECONDS)))
                      .as(newName)
                      .isNotNull());
    } finally {
      log.removeHandler(handler);
    }

    assertThat(logged)
        .filteredOn(message -> message.startsWith("[rename] %s ".formatted(uri)))
        .anySatisfy(message -> assertThat(message).contains("refused: `a.b` is not a valid name"))
        .anySatisfy(message -> assertThat(message).contains("refused:").contains("User0"));
  }
}
