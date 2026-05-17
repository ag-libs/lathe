package io.github.aglibs.lathe.server;

import static java.util.logging.Level.SEVERE;

import io.github.aglibs.lathe.server.analysis.AnalysisEngine;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.SemanticToken;
import io.github.aglibs.lathe.server.analysis.TokenScanner;
import io.github.aglibs.lathe.server.module.ModuleConfig;
import io.github.aglibs.lathe.server.module.ModuleRegistry;
import io.github.aglibs.lathe.server.workspace.ExternalFileCompiler;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

/** Not thread-safe. All methods must be called from the {@link ServerWorker} thread. */
final class DocumentSession {

  private static final Logger LOG = Logger.getLogger(DocumentSession.class.getName());

  private final long debounceMs;
  private final ServerWorker worker;
  private ModuleRegistry registry = ModuleRegistry.empty();
  private WorkspaceManifest manifest = WorkspaceManifest.empty();
  private Path workspaceRoot;
  private WorkspaceWatcher watcher;
  private LanguageClient client;
  private final ExternalFileCompiler externalCompiler =
      new ExternalFileCompiler(WorkspaceManifest.empty());
  private final Map<String, String> openFiles = new HashMap<>();

  DocumentSession(final long debounceMs, final ServerWorker worker) {
    this.debounceMs = debounceMs;
    this.worker = worker;
  }

  void connect(final LanguageClient client) {
    this.client = client;
  }

  void initialize(final Path workspaceRoot) {
    setRegistry(ModuleRegistry.scan(workspaceRoot));
    setManifest(WorkspaceManifest.load(workspaceRoot));
    this.workspaceRoot = workspaceRoot;
    watcher = new WorkspaceWatcher(workspaceRoot, () -> worker.execute(this::reload));
    watcher.start();
  }

  private void setRegistry(final ModuleRegistry newRegistry) {
    final var old = registry;
    registry = newRegistry;
    old.close();
  }

  private void setManifest(final WorkspaceManifest newManifest) {
    manifest = newManifest;
    externalCompiler.setManifest(newManifest);
  }

  void close() {
    if (watcher != null) {
      watcher.close();
    }

    registry.close();
    externalCompiler.close();
  }

  void onOpen(final String uri, final String content) {
    openFiles.put(uri, content);
    LOG.fine(() -> "[open] %s".formatted(uri));
    compileWith(uri, content, CompileMode.OPEN);
  }

  void onChange(final String uri, final String content) {
    openFiles.put(uri, content);
    LOG.fine(() -> "[change] %s".formatted(uri));
    client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
    worker.schedule(
        uri,
        debounceMs,
        () -> {
          final var latest = openFiles.get(uri);
          if (latest != null) {
            compileWith(uri, latest, CompileMode.FAST);
          }
        });
  }

  void onClose(final String uri) {
    openFiles.remove(uri);
    worker.cancel(uri);
    registry.dropFromAllCaches(uri);
    externalCompiler.analysis().dropFromCache(uri);
    client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
  }

  void onSave(final String uri, final String savedContent) {
    LOG.fine(() -> "[save] %s".formatted(uri));
    worker.cancel(uri);
    try {
      final var content = contentForSave(uri, savedContent);
      compileWith(uri, content, CompileMode.FULL);
      registry.moduleFor(toPath(uri)).ifPresent(module -> scheduleOpenFilesInModule(uri, module));
    } catch (final IOException e) {
      LOG.log(SEVERE, e, () -> "[save] failed to read %s".formatted(uri));
    }
  }

  SemanticTokens semanticTokens(final String uri) {
    LOG.fine(() -> "[semanticTokens] %s".formatted(uri));
    final List<SemanticToken> tokens;
    try {
      final var analysis = resolveAnalysis(uri);
      tokens = analysis != null ? analysis.semanticTokens(uri) : null;
    } catch (final Exception e) {
      LOG.log(SEVERE, e, () -> "[semanticTokens] failed for " + uri);
      return null;
    }
    if (tokens == null) {
      return null;
    }

    final var encoded = TokenScanner.encode(tokens);
    return new SemanticTokens(IntStream.of(encoded).boxed().toList());
  }

  Hover hover(final String uri, final Position pos) {
    LOG.fine(() -> "[hover] %s %d:%d".formatted(uri, pos.getLine(), pos.getCharacter()));
    try {
      final var analysis = resolveAnalysis(uri);
      return analysis != null
          ? analysis.hover(uri, pos, registry.allSourceRoots(), manifest)
          : null;
    } catch (final Exception e) {
      LOG.log(SEVERE, e, () -> "[hover] failed for " + uri);
      return null;
    }
  }

  Either<List<? extends Location>, List<? extends LocationLink>> definition(
      final String uri, final Position pos) {
    try {
      final var analysis = resolveAnalysis(uri);
      final var location =
          analysis != null
              ? analysis.definition(uri, pos, registry.allSourceRoots(), manifest)
              : Optional.<Location>empty();
      return Either.forLeft(location.map(List::of).orElseGet(List::of));
    } catch (final Exception e) {
      LOG.log(SEVERE, e, () -> "[definition] failed for " + uri);
      return Either.forLeft(List.of());
    }
  }

  List<CompletionItem> completion(final String uri, final Position pos) {
    final var content = openFiles.get(uri);
    if (content == null) {
      return List.of();
    }

    final var module = registry.moduleFor(toPath(uri));
    if (module.isEmpty()) {
      return List.of();
    }

    return registry.engineFor(module.get()).complete(uri, content, pos);
  }

  List<? extends TextEdit> format(final String tag, final String uri) {
    LOG.fine(() -> "[%s] %s".formatted(tag, uri));
    return JavaFormatter.format(openFiles.get(uri));
  }

  void reload() {
    final var old = registry;
    registry = ModuleRegistry.scan(workspaceRoot);
    old.close();
    manifest = WorkspaceManifest.load(workspaceRoot);
    externalCompiler.setManifest(manifest);
    scheduleAllOpenFiles();
  }

  private String contentForSave(final String uri, final String savedContent) throws IOException {
    if (savedContent != null) {
      return savedContent;
    }

    final var openContent = openFiles.get(uri);
    return openContent != null ? openContent : Files.readString(toPath(uri));
  }

  private void scheduleOpenFilesInModule(final String savedUri, final ModuleConfig savedModule) {
    LOG.fine(
        () ->
            "[save] checking %d open file(s) for dependents of %s"
                .formatted(openFiles.size(), savedUri));
    openFiles.keySet().stream()
        .filter(uri -> !uri.equals(savedUri))
        .filter(
            uri ->
                registry
                    .moduleFor(toPath(uri))
                    .map(m -> m.moduleDir().equals(savedModule.moduleDir()))
                    .orElse(false))
        .forEach(this::scheduleOpenFile);
  }

  private void scheduleAllOpenFiles() {
    openFiles.keySet().forEach(this::scheduleOpenFile);
  }

  private void scheduleOpenFile(final String uri) {
    worker.schedule(
        uri,
        0L,
        () -> {
          final var content = openFiles.get(uri);
          if (content != null) {
            compileWith(uri, content, CompileMode.OPEN);
          }
        });
  }

  private void compileWith(final String uri, final String content, final CompileMode mode) {
    final var module = registry.moduleFor(toPath(uri));
    if (module.isPresent()) {
      compileInModule(uri, content, mode, module.get());
    } else if (manifest.containsFile(toPath(uri))) {
      compileExternal(uri, content, mode);
    } else {
      LOG.fine(() -> "[%s] no module found for %s".formatted(mode.tag, uri));
      client.publishDiagnostics(
          singleDiag(
              uri,
              "Run `mvn process-test-classes` to initialize Lathe for this module",
              DiagnosticSeverity.Warning));
    }
  }

  private void compileInModule(
      final String uri, final String content, final CompileMode mode, final ModuleConfig module) {
    try {
      final var diagnostics = registry.engineFor(module).compile(uri, content, mode);
      publishIfCurrent(uri, content, diagnostics);
    } catch (final Exception ex) {
      LOG.log(SEVERE, ex, () -> "[%s] failed for %s".formatted(mode.tag, uri));
      publishErrorIfCurrent(uri, content, ex);
    }
  }

  private void compileExternal(final String uri, final String content, final CompileMode mode) {
    try {
      publishIfCurrent(uri, content, externalCompiler.analysis().compile(uri, content, mode));
    } catch (final Exception ex) {
      LOG.log(SEVERE, ex, () -> "[external] failed to compile %s".formatted(uri));
      publishErrorIfCurrent(uri, content, ex);
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

  private void publishIfCurrent(
      final String uri, final String content, final List<Diagnostic> diagnostics) {
    if (!isCurrentOpenContent(uri, content)) {
      LOG.fine(() -> "[publish] stale result skipped for %s".formatted(uri));
      return;
    }

    publishDiagnosticsAndRefresh(uri, diagnostics);
  }

  private void publishDiagnosticsAndRefresh(final String uri, final List<Diagnostic> diagnostics) {
    client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
    client.refreshSemanticTokens();
  }

  private void publishErrorIfCurrent(final String uri, final String content, final Exception ex) {
    if (isCurrentOpenContent(uri, content)) {
      publishError(uri, ex);
    }
  }

  private void publishError(final String uri, final Exception ex) {
    final var msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    client.publishDiagnostics(singleDiag(uri, "Lathe: " + msg, DiagnosticSeverity.Error));
  }

  private boolean isCurrentOpenContent(final String uri, final String content) {
    return content.equals(openFiles.get(uri));
  }

  private static Path toPath(final String uri) {
    return Path.of(URI.create(uri));
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
