package io.github.aglibs.lathe.server;

import static java.util.logging.Level.SEVERE;

import io.github.aglibs.lathe.server.analysis.AnalysisEngine;
import io.github.aglibs.lathe.server.module.CompileMode;
import io.github.aglibs.lathe.server.module.ModuleConfig;
import io.github.aglibs.lathe.server.module.ModuleRegistry;
import io.github.aglibs.lathe.server.tokens.SemanticToken;
import io.github.aglibs.lathe.server.tokens.TokenScanner;
import io.github.aglibs.lathe.server.workspace.ExternalFileCompiler;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
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
  private volatile WorkspaceManifest manifest = WorkspaceManifest.empty();
  private volatile Path workspaceRoot;
  private volatile WorkspaceWatcher watcher;
  private volatile LanguageClient client;
  private final long debounceMs;
  private final ExternalFileCompiler externalCompiler =
      new ExternalFileCompiler(WorkspaceManifest.empty());

  private final ScheduledExecutorService debouncer =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            final var t = new Thread(r, "lathe-debouncer");
            t.setDaemon(true);
            return t;
          });
  private final ConcurrentHashMap<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> openFiles = new ConcurrentHashMap<>();

  LatheTextDocumentService(final ModuleRegistry registry) {
    this(registry, DEFAULT_DEBOUNCE_MS);
  }

  LatheTextDocumentService(final ModuleRegistry registry, final long debounceMs) {
    this.registry = registry;
    this.debounceMs = debounceMs;
  }

  void connect(final LanguageClient client) {
    this.client = client;
  }

  void setRegistry(final ModuleRegistry newRegistry) {
    final var old = this.registry;
    this.registry = newRegistry;
    old.close();
  }

  void setManifest(final WorkspaceManifest manifest) {
    this.manifest = manifest;
    externalCompiler.setManifest(manifest);
  }

  void startWatching(final Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
    watcher = new WorkspaceWatcher(workspaceRoot, this::reload);
    watcher.start();
  }

  private void reload() {
    setRegistry(ModuleRegistry.scan(workspaceRoot));
    setManifest(WorkspaceManifest.load(workspaceRoot));
  }

  void close() {
    if (watcher != null) {
      watcher.close();
    }
    registry.close();
    externalCompiler.close();
  }

  @Override
  public void didOpen(final DidOpenTextDocumentParams params) {
    final var uri = params.getTextDocument().getUri();
    final var content = params.getTextDocument().getText();
    openFiles.put(uri, content);
    LOG.fine(() -> "[open] %s".formatted(uri));
    compileWith(uri, content, CompileMode.OPEN);
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
              compileWith(uri, content, CompileMode.FAST);
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
    registry.dropFromAllCaches(uri);
    externalCompiler.analysis().dropFromCache(uri);
    client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
  }

  @Override
  public CompletableFuture<SemanticTokens> semanticTokensFull(final SemanticTokensParams params) {
    final var uri = params.getTextDocument().getUri();
    LOG.fine(() -> "[semanticTokens] %s".formatted(uri));
    final List<SemanticToken> tokens;
    try {
      final var analysis = resolveAnalysis(uri);
      tokens = analysis != null ? analysis.semanticTokens(uri) : null;
    } catch (final Exception e) {
      LOG.log(SEVERE, e, () -> "[semanticTokens] failed for " + uri);
      return CompletableFuture.completedFuture(null);
    }
    if (tokens == null) {
      return CompletableFuture.completedFuture(null);
    }
    final var encoded = TokenScanner.encode(tokens);
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
      final var analysis = resolveAnalysis(uri);
      final var result =
          analysis != null ? analysis.hover(uri, pos, registry.allSourceRoots(), manifest) : null;
      return CompletableFuture.completedFuture(result);
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
      final var analysis = resolveAnalysis(uri);
      final var location =
          analysis != null
              ? analysis.definition(uri, pos, registry.allSourceRoots(), manifest)
              : Optional.<Location>empty();
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
            compileWith(uri, Files.readString(toPath(uri)), CompileMode.FULL);
            registry
                .moduleFor(toPath(uri))
                .ifPresent(module -> scheduleOpenFilesInModule(uri, module));
          } catch (final IOException e) {
            LOG.log(SEVERE, e, () -> "[save] failed to read %s".formatted(uri));
          }
        });
  }

  private void scheduleOpenFilesInModule(final String savedUri, final ModuleConfig savedModule) {
    LOG.fine(
        () ->
            "[save] checking %d open file(s) for dependents of %s"
                .formatted(openFiles.size(), savedUri));
    openFiles.forEach((depUri, ignored) -> scheduleIfDependent(depUri, savedUri, savedModule));
  }

  private void scheduleIfDependent(
      final String depUri, final String savedUri, final ModuleConfig savedModule) {
    if (depUri.equals(savedUri)) {
      return;
    }

    final Optional<ModuleConfig> depModule = registry.moduleFor(toPath(depUri));
    final Optional<ModuleConfig> sameModule =
        depModule.filter(m -> m.moduleDir().equals(savedModule.moduleDir()));
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
                      compileWith(depUri, content, CompileMode.OPEN);
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

  private AnalysisEngine resolveAnalysis(final String uri) {
    final var path = toPath(uri);
    final var reactor = registry.moduleFor(path).map(registry::engineFor);
    if (reactor.isPresent()) {
      return reactor.get();
    }
    if (!manifest.containsFile(path)) {
      return null;
    }
    try {
      return externalCompiler.ensureCompiled(uri);
    } catch (final IOException e) {
      LOG.log(SEVERE, e, () -> "[external] on-demand compile failed for %s".formatted(uri));
      return null;
    }
  }

  private static Path toPath(final String uri) {
    return Path.of(URI.create(uri));
  }

  private void compileWith(final String uri, final String content, final CompileMode mode) {
    registry
        .moduleFor(toPath(uri))
        .ifPresentOrElse(
            module -> {
              try {
                final List<Diagnostic> diagnostics =
                    registry.engineFor(module).compile(uri, content, mode);
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
              if (manifest.containsFile(toPath(uri))) {
                try {
                  final List<Diagnostic> diagnostics =
                      externalCompiler.analysis().compile(uri, content, mode);
                  client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
                  client.refreshSemanticTokens();
                } catch (final Exception ex) {
                  LOG.log(SEVERE, ex, () -> "[external] failed to compile %s".formatted(uri));
                }
              } else {
                LOG.fine(() -> "[%s] no module found for %s".formatted(mode.tag, uri));
                client.publishDiagnostics(
                    singleDiag(
                        uri,
                        "Run `mvn test-compile` to initialize Lathe for this module",
                        DiagnosticSeverity.Warning));
              }
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
