package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.ExceptionUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.format.FormatEngine;
import io.github.aglibs.lathe.server.format.FormatTarget;
import io.github.aglibs.lathe.server.format.JavaFormatter;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

// Formats an open document: its text and what the formatter needs from the session (FormatTarget)
// are read on the loop, the format runs on this service's own thread, as an external command waits
// on its process, and the edits come back on the loop. Event-loop state: the formats in flight and
// whether a failure was already reported.
final class FormatService {

  private static final Logger LOG = Logger.getLogger(FormatService.class.getName());

  private final ServerEventLoop eventLoop;
  private final LatheLanguageClient client;
  private final WorkspaceState state;
  private final DocumentRegistry docs;
  private final Formatted onFormatted;
  // The workspace's formatter, null for none, and whether the project pins one (whose jars a sync
  // may not have resolved yet).
  private FormatEngine engine;
  private boolean pinned;

  // Loop state like the rest: a format failure is reported to the user once, then only logged.
  private boolean formatFailureReported;
  // Formatting runs here, off the event loop: an external command waits on its process, a build
  // tool for up to a minute. One thread, so two formats never run a build in one module at once.
  private final ExecutorService formatThread =
      Executors.newSingleThreadExecutor(FormatService::newFormatThread);
  // The formats queued or running there, so close() can cancel them: the thread's shutdown drops
  // what is queued, and a caller would otherwise wait for an answer that cannot come.
  private final Set<CompletableFuture<List<TextEdit>>> formatsInFlight = new HashSet<>();

  FormatService(
      final ServerEventLoop eventLoop,
      final LatheLanguageClient client,
      final WorkspaceState state,
      final DocumentRegistry docs,
      final Formatted onFormatted) {
    this.eventLoop = eventLoop;
    this.client = client;
    this.state = state;
    this.docs = docs;
    this.onFormatted = onFormatted;
  }

  // The formatter the client's settings or the workspace style file name, resolved off the loop,
  // before the first format; pinned when the project names one (whose jars a sync may not have
  // resolved yet, so engine is null).
  void use(final FormatEngine engine, final boolean pinned) {
    eventLoop.checkOnLoop();
    this.engine = engine;
    this.pinned = pinned;
  }

  void close() {
    formatThread.shutdownNow();
    List.copyOf(formatsInFlight).forEach(format -> format.cancel(false));
  }

  // Told of a format that changed a document, with the text it formatted, on the loop: a pending
  // rename keeps the formatted text of its files.
  @FunctionalInterface
  interface Formatted {
    void accept(String uri, String content, List<TextEdit> edits);
  }

  private static Thread newFormatThread(final Runnable task) {
    final var thread = new Thread(task, "lathe-format");
    thread.setDaemon(true);
    return thread;
  }

  // The registry module owning the file, as a command formatter's %MODULE% names it: "." for the
  // root module.
  Optional<String> moduleRelOf(final Path file) {
    return state
        .registry()
        .moduleSourceFor(file)
        .map(state.layout()::moduleRel)
        .map(SourceLayout::displayed);
  }

  // No edits without a formatter.
  CompletableFuture<List<TextEdit>> formatFuture(final String uri) {
    eventLoop.checkOnLoop();
    final FormatEngine engine = this.engine;
    if (engine == null) {
      return CompletableFuture.completedFuture(List.of());
    }

    return formatDocument(uri, (content, target) -> JavaFormatter.format(engine, content, target));
  }

  // Only in-process engines format ranges; whole-file ones do not advertise the capability, so a
  // stray request gets no edits.
  CompletableFuture<List<TextEdit>> formatRangesFuture(final String uri, final List<Range> ranges) {
    eventLoop.checkOnLoop();
    final FormatEngine engine = this.engine;
    if (engine == null || !engine.formatsRanges()) {
      return CompletableFuture.completedFuture(List.of());
    }

    return formatDocument(
        uri, (content, target) -> JavaFormatter.formatRanges(engine, content, ranges));
  }

  // For the agent, which must tell a failure from an already formatted file: a refusal that
  // carries the formatter's reason, or why there is no formatter. Only a pinned formatter whose
  // jars are missing is one a sync can supply.
  CompletableFuture<List<TextEdit>> formatOrRefuseFuture(final String uri) {
    eventLoop.checkOnLoop();
    final FormatEngine engine = this.engine;
    if (engine == null) {
      final String reason =
          pinned
              ? "The project's formatter is not resolved yet: run `%s`."
                  .formatted(LatheLayout.SYNC_SCRIPT_COMMAND)
              : "No formatter for this workspace: the project pins none (no Spotless Java step), so a sync does not add one; name one in %s to format here."
                  .formatted(LatheLayout.STYLE_SHARED_FILE);
      return CompletableFuture.failedFuture(FeatureAnswers.refused(reason));
    }

    return formatted(uri, (content, target) -> JavaFormatter.format(engine, content, target))
        .exceptionally(
            failure -> {
              throw FeatureAnswers.refused(
                  "Could not format %s: %s"
                      .formatted(
                          LatheUri.toPath(uri).getFileName(),
                          ExceptionUtil.rootCause(failure).getMessage()));
            });
  }

  // Formats the open document's text, reporting the outcome once and answering no edits on a
  // failure.
  private CompletableFuture<List<TextEdit>> formatDocument(
      final String uri, final DocumentFormat format) {
    final var t = Stopwatch.start();
    return formatted(uri, format)
        .handle(
            (result, failure) -> {
              if (failure == null) {
                // Formatting is a synchronous request (format-on-save blocks the editor), so a
                // progress spinner would only paint after the edit; report the outcome once it is
                // done instead. Stay quiet when nothing changed, so saving an already-formatted
                // file does not nag.
                if (!result.isEmpty()) {
                  client.showMessage(
                      new MessageParams(
                          MessageType.Info, "Lathe: formatted in %dms".formatted(t.elapsedMs())));
                }

                return result;
              }

              final Throwable cause = ExceptionUtil.completionCause(failure);
              if (cause instanceof CancellationException) {
                LOG.fine(() -> "[format] %s cancelled: the server is closing".formatted(uri));
                return List.of();
              }

              LOG.log(
                  Level.SEVERE,
                  cause,
                  () -> "[format] %s failed %dms".formatted(uri, t.elapsedMs()));
              // Format-on-save would repeat the same failure (a formatter that cannot run on this
              // JDK, a file that does not parse) on every save; say why once, then only log.
              if (!formatFailureReported) {
                formatFailureReported = true;
                client.showMessage(
                    new MessageParams(
                        MessageType.Warning,
                        "Lathe: formatting failed: %s (further failures are only logged)"
                            .formatted(ExceptionUtil.rootCause(cause).getMessage())));
              }

              return List.of();
            });
  }

  // The open document's text and its FormatTarget are read on the loop; the format runs on the
  // format thread, as an external command waits on its process; the result comes back on the loop,
  // where a pending rename hears of it.
  private CompletableFuture<List<TextEdit>> formatted(
      final String uri, final DocumentFormat format) {
    final var t = Stopwatch.start();
    final OpenDocument openFile = docs.get(uri);
    final String content = openFile != null ? openFile.content() : null;
    final FormatTarget target = formatTarget(LatheUri.toPath(uri));
    final CompletableFuture<List<TextEdit>> running =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return format.apply(content, target);
              } catch (final Exception e) {
                if (e instanceof InterruptedException) {
                  Thread.currentThread().interrupt();
                }

                throw ExceptionUtil.asCompletion(e);
              }
            },
            formatThread);
    formatsInFlight.add(running);
    return eventLoop
        .rejoin(running)
        .whenComplete((result, failure) -> formatsInFlight.remove(running))
        .thenApply(
            result -> {
              LOG.info(
                  () -> "[format] %s %dms edits=%d".formatted(uri, t.elapsedMs(), result.size()));
              if (!result.isEmpty()) {
                onFormatted.accept(uri, content, result);
              }

              return result;
            });
  }

  private FormatTarget formatTarget(final Path file) {
    return new FormatTarget(
        file, moduleRelOf(file).orElse("."), state.manifest().buildTool().orElse(null));
  }

  @FunctionalInterface
  private interface DocumentFormat {
    List<TextEdit> apply(String content, FormatTarget target) throws Exception;
  }
}
