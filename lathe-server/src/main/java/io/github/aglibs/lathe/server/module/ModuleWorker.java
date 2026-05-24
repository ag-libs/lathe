package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.server.analysis.CompilationContext;
import io.github.aglibs.lathe.server.analysis.FeatureRequest;
import io.github.aglibs.lathe.server.analysis.SemanticToken;
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
import org.eclipse.lsp4j.*;

/** Called from the server worker; executes all compilation work on its own module thread. */
public final class ModuleWorker {

  private static final Logger LOG = Logger.getLogger(ModuleWorker.class.getName());

  private final ExecutorService executor;
  private final Supplier<CompilationContext> contextFactory;
  private CompilationContext context;
  private boolean closed;
  private CompletableFuture<Void> closeFuture;

  static ModuleWorker module(final ModuleConfig config) {
    return new ModuleWorker(
        "lathe-module-" + config.moduleDir().getFileName(),
        () -> new CompilationContext(new ModuleCompiler(config)));
  }

  static ModuleWorker external(final WorkspaceManifest manifest) {
    return new ModuleWorker(
        "lathe-external", () -> new CompilationContext(new ExternalCompiler(manifest)));
  }

  ModuleWorker(final String name, final Supplier<CompilationContext> contextFactory) {
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

  public CompletableFuture<CompileResult> compile(final CompileRequest request) {
    return submit(
        ctx ->
            new CompileResult(
                request.uri(),
                request.generation(),
                ctx.compile(request.uri(), request.content(), request.version(), request.mode())));
  }

  public CompletableFuture<Hover> hover(final FeatureRequest request) {
    return submit(ctx -> ctx.hover(request));
  }

  public CompletableFuture<Optional<Location>> definition(final FeatureRequest request) {
    return submit(ctx -> ctx.definition(request));
  }

  public CompletableFuture<List<CompletionItem>> complete(
      final String uri,
      final String content,
      final Position position,
      final CompletionContext context) {
    return submit(ctx -> ctx.complete(uri, content, position, context));
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

  private <T> CompletableFuture<T> submit(final Function<CompilationContext, T> fn) {
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
