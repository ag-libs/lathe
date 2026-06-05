package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.server.analysis.ReferenceTarget;
import io.github.aglibs.lathe.server.analysis.SemanticToken;
import io.github.aglibs.lathe.server.analysis.SourceAnalysisSession;
import io.github.aglibs.lathe.server.analysis.SourceFeatureRequest;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.server.analysis.completion.CompletionOutcome;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;

/** Called from the server worker; executes all compilation work on its own module thread. */
public final class ModuleSourceWorker {

  private static final Logger LOG = Logger.getLogger(ModuleSourceWorker.class.getName());

  private final ExecutorService executor;
  private final Supplier<SourceAnalysisSession> contextFactory;
  private SourceAnalysisSession context;
  private boolean closed;
  private CompletableFuture<Void> closeFuture;

  static ModuleSourceWorker module(final ModuleSourceConfig config) {
    return new ModuleSourceWorker(
        "lathe-module-" + config.moduleDir().getFileName() + "-" + config.sourceTree(),
        () -> new SourceAnalysisSession(new ModuleSourceCompiler(config)));
  }

  static ModuleSourceWorker external(final WorkspaceManifest manifest) {
    return new ModuleSourceWorker(
        "lathe-external", () -> new SourceAnalysisSession(new ExternalCompiler(manifest)));
  }

  ModuleSourceWorker(final String name, final Supplier<SourceAnalysisSession> contextFactory) {
    this.contextFactory = contextFactory;
    this.executor =
        Executors.newSingleThreadExecutor(
            r -> {
              final var t = new Thread(r, name);
              t.setDaemon(true);
              return t;
            });
  }

  CompletableFuture<Void> closeAsync() {
    if (closeFuture != null) {
      return closeFuture;
    }

    closed = true;
    closeFuture = new CompletableFuture<>();
    executor.execute(
        () -> {
          try {
            if (context != null) {
              context.close();
              context = null;
            }

            closeFuture.complete(null);
          } catch (final Throwable t) {
            LOG.log(Level.SEVERE, t, () -> "[module] failed to close");
            closeFuture.completeExceptionally(t);
            rethrowError(t);
          } finally {
            executor.shutdown();
          }
        });
    return closeFuture;
  }

  void close() {
    closeAsync().join();
  }

  public CompletableFuture<CompileResponse> compile(final CompileRequest request) {
    return submit(
        ctx ->
            new CompileResponse(
                request.uri(),
                request.generation(),
                ctx.compile(request.uri(), request.content(), request.version(), request.mode())));
  }

  public CompletableFuture<Hover> hover(final SourceFeatureRequest request) {
    return submit(ctx -> ctx.hover(request));
  }

  public CompletableFuture<ReferenceTarget> resolveTarget(final SourceFeatureRequest request) {
    return submit(ctx -> ctx.resolveTarget(request));
  }

  public CompletableFuture<List<Location>> searchReferences(
      final String uri,
      final String content,
      final int version,
      final ReferenceTarget target,
      final boolean includeDeclaration) {
    return submit(ctx -> ctx.searchReferences(uri, content, version, target, includeDeclaration));
  }

  public CompletableFuture<List<Location>> references(
      final SourceFeatureRequest request, final boolean includeDeclaration) {
    return submit(ctx -> ctx.references(request, includeDeclaration));
  }

  public CompletableFuture<Optional<Location>> definition(final SourceFeatureRequest request) {
    return submit(ctx -> ctx.definition(request));
  }

  public CompletableFuture<CompletionOutcome> complete(
      final String uri,
      final String content,
      final int version,
      final Position position,
      final CompletionContext context,
      final WorkspaceTypeIndex typeIndex) {
    return submit(ctx -> ctx.complete(uri, content, version, position, context, typeIndex));
  }

  public CompletableFuture<List<SemanticToken>> semanticTokens(
      final String uri, final int expectedVersion) {
    return submit(ctx -> ctx.semanticTokens(uri, expectedVersion));
  }

  public void dropFromCache(final String uri) {
    if (closed) {
      return;
    }

    executor.execute(
        () -> {
          if (context != null) {
            context.dropFromCache(uri);
          }
        });
  }

  private <T> CompletableFuture<T> submit(final Function<SourceAnalysisSession, T> fn) {
    final var future = new CompletableFuture<T>();
    if (closed) {
      future.completeExceptionally(new IllegalStateException("module worker is closed"));
      return future;
    }

    try {
      executor.execute(
          () -> {
            try {
              if (context == null) {
                context = contextFactory.get();
              }

              future.complete(fn.apply(context));
            } catch (final Throwable t) {
              future.completeExceptionally(t);
              rethrowError(t);
            }
          });
    } catch (final RejectedExecutionException e) {
      future.completeExceptionally(e);
    }
    return future;
  }

  private static void rethrowError(final Throwable t) {
    if (t instanceof final Error error) {
      throw error;
    }
  }
}
