package io.github.aglibs.lathe.server;

import static java.util.logging.Level.SEVERE;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

final class LatheTextDocumentService implements TextDocumentService {

  private static final Logger LOG = Logger.getLogger(LatheTextDocumentService.class.getName());
  private static final long DEFAULT_DEBOUNCE_MS = 500;

  private volatile ModuleRegistry registry;
  private final AnalysisEngine engine;
  private volatile LanguageClient client;
  private final long debounceMs;

  private final ScheduledExecutorService debouncer =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            final var t = new Thread(r, "lathe-debouncer");
            t.setDaemon(true);
            return t;
          });
  private final ConcurrentHashMap<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> openFiles = new ConcurrentHashMap<>();

  LatheTextDocumentService(final ModuleRegistry registry, final AnalysisEngine engine) {
    this(registry, engine, DEFAULT_DEBOUNCE_MS);
  }

  LatheTextDocumentService(
      final ModuleRegistry registry, final AnalysisEngine engine, final long debounceMs) {
    this.registry = registry;
    this.engine = engine;
    this.debounceMs = debounceMs;
  }

  void connect(final LanguageClient client) {
    this.client = client;
  }

  void setRegistry(final ModuleRegistry registry) {
    this.registry = registry;
    engine.invalidate();
  }

  void startWatching(final Path workspaceRoot) {
    final var marker =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.ROOT_MARKER);
    final var lastSeen = new AtomicLong(mtime(marker));
    debouncer.scheduleAtFixedRate(
        () -> {
          final long current = mtime(marker);
          if (current != lastSeen.get()) {
            lastSeen.set(current);
            LOG.info(() -> "[registry] root.marker changed — reloading");
            setRegistry(ModuleRegistry.scan(workspaceRoot));
          }
        },
        2,
        2,
        TimeUnit.SECONDS);
  }

  void close() {
    engine.close();
  }

  private static long mtime(final Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (final IOException e) {
      return 0;
    }
  }

  @Override
  public void didOpen(final DidOpenTextDocumentParams params) {
    final var uri = params.getTextDocument().getUri();
    final var content = params.getTextDocument().getText();
    openFiles.put(uri, content);
    LOG.fine(() -> "[open] %s".formatted(uri));
    compileWith(uri, content, ModuleCompiler.Mode.OPEN);
  }

  @Override
  public void didChange(final DidChangeTextDocumentParams params) {
    final var uri = params.getTextDocument().getUri();
    final var content = params.getContentChanges().getFirst().getText();
    openFiles.put(uri, content);
    LOG.fine(() -> "[change] %s".formatted(uri));
    cancelPending(uri, true, "change");
    client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));

    final var future =
        debouncer.schedule(
            () -> {
              pending.remove(uri);
              compileWith(uri, content, ModuleCompiler.Mode.FAST);
            },
            debounceMs,
            TimeUnit.MILLISECONDS);
    pending.put(uri, future);
  }

  @Override
  public void didClose(final DidCloseTextDocumentParams params) {
    final var uri = params.getTextDocument().getUri();
    openFiles.remove(uri);
    cancelPending(uri, false, "close");
    engine.dropFromCache(uri);
    client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
  }

  @Override
  public CompletableFuture<SemanticTokens> semanticTokensFull(final SemanticTokensParams params) {
    final var uri = params.getTextDocument().getUri();
    LOG.fine(() -> "[semanticTokens] %s".formatted(uri));
    final var tokens = engine.semanticTokens(uri);
    if (tokens == null) {
      return CompletableFuture.completedFuture(null);
    }
    final var encoded = SemanticTokensScanner.encode(tokens);
    final var dataList = new ArrayList<Integer>(encoded.length);
    for (final var v : encoded) {
      dataList.add(v);
    }
    return CompletableFuture.completedFuture(new SemanticTokens(dataList));
  }

  @Override
  public CompletableFuture<Hover> hover(final HoverParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    LOG.fine(() -> "[hover] %s %d:%d".formatted(uri, pos.getLine(), pos.getCharacter()));
    try {
      return CompletableFuture.completedFuture(engine.hover(uri, pos));
    } catch (final Exception e) {
      LOG.log(SEVERE, e, () -> "[hover] failed for " + uri);
      return CompletableFuture.completedFuture(null);
    }
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      definition(final DefinitionParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    try {
      final var location = engine.definition(uri, pos, registry.allSourceRoots());
      return CompletableFuture.completedFuture(
          Either.forLeft(location.map(List::of).orElseGet(List::of)));
    } catch (final Exception e) {
      LOG.log(SEVERE, e, () -> "[definition] failed for " + uri);
      return CompletableFuture.completedFuture(Either.forLeft(List.of()));
    }
  }

  public void didSave(final DidSaveTextDocumentParams params) {
    final var uri = params.getTextDocument().getUri();
    cancelPending(uri, false, "save");
    LOG.fine(() -> "[save] %s".formatted(uri));
    debouncer.submit(
        () -> {
          try {
            compileWith(uri, Files.readString(toPath(uri)), ModuleCompiler.Mode.FULL);
            registry
                .findForFile(toPath(uri))
                .ifPresent(module -> scheduleOpenFilesInModule(uri, module));
          } catch (final IOException e) {
            LOG.log(SEVERE, e, () -> "[save] failed to read %s".formatted(uri));
          }
        });
  }

  private void scheduleOpenFilesInModule(final String savedUri, final ModuleParams savedModule) {
    LOG.fine(
        () ->
            "[save] checking %d open file(s) for dependents of %s"
                .formatted(openFiles.size(), savedUri));
    openFiles.forEach((depUri, ignored) -> scheduleIfDependent(depUri, savedUri, savedModule));
  }

  private void scheduleIfDependent(
      final String depUri, final String savedUri, final ModuleParams savedModule) {
    if (depUri.equals(savedUri)) {
      return;
    }

    final Optional<ModuleParams> depModule = registry.findForFile(toPath(depUri));
    final Optional<ModuleParams> sameModule =
        depModule.filter(m -> m.latheModuleDir().equals(savedModule.latheModuleDir()));
    LOG.fine(
        () ->
            "[save] dep=%s module=%s same=%s"
                .formatted(depUri, depModule.isPresent(), sameModule.isPresent()));
    sameModule.ifPresent(
        ignored -> {
          cancelPending(depUri, true, "save");

          final var future =
              debouncer.schedule(
                  () -> {
                    pending.remove(depUri);
                    final var content = openFiles.get(depUri);
                    if (content != null) {
                      compileWith(depUri, content, ModuleCompiler.Mode.OPEN);
                    }
                  },
                  0L,
                  TimeUnit.MILLISECONDS);
          pending.put(depUri, future);
          LOG.fine(() -> "[save] scheduled dependent recompile for %s".formatted(depUri));
        });
  }

  @Override
  public CompletableFuture<List<? extends TextEdit>> formatting(
      final DocumentFormattingParams params) {
    return format("format", params.getTextDocument().getUri());
  }

  @Override
  public CompletableFuture<List<? extends TextEdit>> rangeFormatting(
      final DocumentRangeFormattingParams params) {
    return format("rangeFormat", params.getTextDocument().getUri());
  }

  private CompletableFuture<List<? extends TextEdit>> format(final String tag, final String uri) {
    LOG.fine(() -> "[%s] %s".formatted(tag, uri));
    return CompletableFuture.completedFuture(JavaFormatter.format(openFiles.get(uri)));
  }

  private void cancelPending(final String uri, final boolean mayInterrupt, final String tag) {
    final var previous = pending.remove(uri);
    if (previous != null) {
      final var cancelled = previous.cancel(mayInterrupt);
      LOG.fine(() -> "[%s] cancelled pending=%s %s".formatted(tag, cancelled, uri));
    }
  }

  private static Path toPath(final String uri) {
    return Path.of(URI.create(uri));
  }

  private void compileWith(final String uri, final String content, final ModuleCompiler.Mode mode) {
    registry
        .findForFile(toPath(uri))
        .ifPresentOrElse(
            module -> {
              try {
                final List<Diagnostic> diagnostics = engine.compile(uri, content, module, mode);
                if (Thread.interrupted()) {
                  LOG.fine(
                      () -> "[%s] interrupted, skipping publish for %s".formatted(mode.tag, uri));
                  return;
                }

                client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
                client.refreshSemanticTokens();
              } catch (final Exception ex) {
                LOG.log(SEVERE, ex, () -> "[%s] failed for %s".formatted(mode.tag, uri));
                publishError(uri, ex);
              }
            },
            () -> {
              LOG.fine(() -> "[%s] no module found for %s".formatted(mode.tag, uri));
              client.publishDiagnostics(
                  singleDiag(
                      uri,
                      "Run `mvn test-compile` to initialize Lathe for this module",
                      DiagnosticSeverity.Warning));
            });
  }

  private void publishError(final String uri, final Exception ex) {
    final var msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    client.publishDiagnostics(singleDiag(uri, "Lathe: " + msg, DiagnosticSeverity.Error));
  }

  private static PublishDiagnosticsParams singleDiag(
      final String uri, final String message, final DiagnosticSeverity severity) {
    return new PublishDiagnosticsParams(
        uri,
        List.of(
            new Diagnostic(
                new Range(new Position(0, 0), new Position(0, 1)), message, severity, "lathe")));
  }
}
