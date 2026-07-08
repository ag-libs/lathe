package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.analysis.CodeActionRequest;
import io.github.aglibs.lathe.server.analysis.JavaSourceCompiler;
import io.github.aglibs.lathe.server.analysis.ReferenceMatch;
import io.github.aglibs.lathe.server.analysis.ReferenceTarget;
import io.github.aglibs.lathe.server.analysis.SemanticToken;
import io.github.aglibs.lathe.server.analysis.SourceAnalysisSession;
import io.github.aglibs.lathe.server.analysis.SourceFeatureRequest;
import io.github.aglibs.lathe.server.analysis.TransientSource;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.server.analysis.completion.CompletionOutcome;
import io.github.aglibs.lathe.server.run.RunTarget;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/** Called from the server event loop; executes all compilation work on its own module thread. */
public final class CompilationWorker {

  private static final Logger LOG = Logger.getLogger(CompilationWorker.class.getName());
  private static final int FATAL_EXIT_STATUS = 1;

  private final ExecutorService executor;
  private final Supplier<SourceAnalysisSession> contextFactory;
  private final IntConsumer processTerminator;
  private SourceAnalysisSession context;
  private boolean closed;
  private CompletableFuture<Void> closeFuture;

  static CompilationWorker module(
      final ModuleSourceConfig config, final CompilationAdmission compilationAdmission) {
    return new CompilationWorker(
        "lathe-module-%s-%s".formatted(config.moduleDir().getFileName(), config.sourceTree()),
        () -> new SourceAnalysisSession(new ModuleSourceCompiler(config, compilationAdmission)));
  }

  static CompilationWorker external(
      final WorkspaceManifest manifest, final CompilationAdmission compilationAdmission) {
    return new CompilationWorker(
        "lathe-external",
        () -> new SourceAnalysisSession(new ExternalCompiler(manifest, compilationAdmission)));
  }

  CompilationWorker(final String name, final Supplier<SourceAnalysisSession> contextFactory) {
    this(name, contextFactory, status -> Runtime.getRuntime().halt(status));
  }

  CompilationWorker(
      final String name,
      final Supplier<SourceAnalysisSession> contextFactory,
      final IntConsumer processTerminator) {
    this.contextFactory = contextFactory;
    this.processTerminator = processTerminator;
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
            IOUtil.rethrowIfError(t);
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
        ctx -> {
          final var diags =
              ctx.compile(request.uri(), request.content(), request.version(), request.mode());
          return new CompileResponse(
              request.uri(), request.generation(), diags, ctx.lastWrittenBinaryNames());
        });
  }

  public CompletableFuture<SignatureHelp> signatureHelp(final SourceFeatureRequest request) {
    return submit(ctx -> ctx.signatureHelp(request));
  }

  public CompletableFuture<Hover> hover(final SourceFeatureRequest request) {
    return submit(ctx -> ctx.hover(request));
  }

  public CompletableFuture<ReferenceTarget> resolveTarget(
      final SourceFeatureRequest request, final CancelChecker cancelChecker) {
    return submit(ctx -> ctx.resolveTarget(request), cancelChecker);
  }

  public CompletableFuture<ReferenceTarget> resolveTarget(final SourceFeatureRequest request) {
    return submit(ctx -> ctx.resolveTarget(request));
  }

  public CompletableFuture<ReferenceTarget> resolveContractTarget(
      final SourceFeatureRequest request, final CancelChecker cancelChecker) {
    return submit(ctx -> ctx.resolveContractTarget(request), cancelChecker);
  }

  public CompletableFuture<List<ReferenceMatch>> searchReferences(
      final String uri,
      final String content,
      final int version,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final CancelChecker cancelChecker) {
    return submit(
        ctx ->
            ctx.searchReferences(uri, content, version, target, includeDeclaration, cancelChecker),
        cancelChecker);
  }

  public CompletableFuture<List<ReferenceMatch>> searchReferencesTransient(
      final List<TransientSource> sources,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final IntConsumer progressPerFile,
      final CancelChecker cancelChecker) {
    return submit(
        ctx ->
            ctx.searchReferencesTransient(
                sources, target, includeDeclaration, progressPerFile, cancelChecker),
        cancelChecker);
  }

  public CompletableFuture<List<CallHierarchyIncomingCall>> searchIncomingCalls(
      final String uri,
      final String content,
      final int version,
      final ReferenceTarget target,
      final CancelChecker cancelChecker) {
    return submit(
        ctx -> ctx.searchIncomingCalls(uri, content, version, target, cancelChecker),
        cancelChecker);
  }

  public CompletableFuture<List<CallHierarchyIncomingCall>> searchIncomingCallsTransient(
      final String uri,
      final String content,
      final ReferenceTarget target,
      final CancelChecker cancelChecker) {
    return submit(
        ctx -> ctx.searchIncomingCallsTransient(uri, content, target, cancelChecker),
        cancelChecker);
  }

  public CompletableFuture<List<Location>> methodImplementations(
      final String uri,
      final String content,
      final int version,
      final ReferenceTarget target,
      final Set<String> candidateBinaryNames) {
    return submit(
        ctx -> ctx.methodImplementations(uri, content, version, target, candidateBinaryNames));
  }

  public CompletableFuture<List<Location>> methodImplementationsTransient(
      final String uri,
      final String content,
      final ReferenceTarget target,
      final Set<String> candidateBinaryNames) {
    return submit(
        ctx -> ctx.methodImplementationsTransient(uri, content, target, candidateBinaryNames));
  }

  public CompletableFuture<Optional<Location>> definition(final SourceFeatureRequest request) {
    return submit(ctx -> ctx.definition(request));
  }

  public CompletableFuture<Optional<Location>> declaration(final SourceFeatureRequest request) {
    return submit(ctx -> ctx.declaration(request));
  }

  public CompletableFuture<List<Location>> typeImplementations(
      final SourceFeatureRequest request, final WorkspaceTypeIndex typeIndex) {
    return submit(ctx -> ctx.typeImplementations(request, typeIndex));
  }

  public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(
      final SourceFeatureRequest request) {
    return submit(ctx -> ctx.prepareCallHierarchy(request));
  }

  public CompletableFuture<List<CallHierarchyOutgoingCall>> outgoingCalls(
      final CallHierarchyItem item,
      final String uri,
      final String content,
      final int version,
      final List<Path> sourceRoots) {
    return submit(ctx -> ctx.outgoingCalls(item, uri, content, version, sourceRoots));
  }

  public CompletableFuture<List<TypeHierarchyItem>> prepareTypeHierarchy(
      final SourceFeatureRequest request, final WorkspaceTypeIndex typeIndex) {
    return submit(ctx -> ctx.prepareTypeHierarchy(request, typeIndex));
  }

  public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySupertypes(
      final TypeHierarchyItem item,
      final WorkspaceTypeIndex typeIndex,
      final List<Path> sourceRoots) {
    return submit(ctx -> ctx.typeHierarchySupertypes(item, typeIndex, sourceRoots));
  }

  public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySubtypes(
      final TypeHierarchyItem item,
      final WorkspaceTypeIndex typeIndex,
      final List<Path> sourceRoots) {
    return submit(ctx -> ctx.typeHierarchySubtypes(item, typeIndex, sourceRoots));
  }

  public CompletableFuture<List<TypeIndexEntry>> cachedTypeEntries(
      final Set<String> neededSimpleNames) {
    return submit(ctx -> ctx.cachedTypeEntries(neededSimpleNames));
  }

  public CompletableFuture<List<DocumentSymbol>> documentSymbol(
      final String uri, final String content) {
    return submit(ctx -> ctx.documentSymbol(uri, content));
  }

  public CompletableFuture<List<RunTarget>> runnables(
      final String uri, final String content, final int expectedVersion, final String moduleRel) {
    return submit(ctx -> ctx.runnables(uri, content, expectedVersion, moduleRel));
  }

  public CompletableFuture<List<FoldingRange>> foldingRange(
      final String uri, final String content) {
    return submit(ctx -> ctx.foldingRange(uri, content));
  }

  public CompletableFuture<CompletionOutcome> complete(
      final String uri,
      final String content,
      final int version,
      final Position position,
      final CompletionContext context,
      final WorkspaceTypeIndex typeIndex,
      final List<String> moduleNames) {
    return submit(
        ctx -> ctx.complete(uri, content, version, position, context, typeIndex, moduleNames));
  }

  public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(
      final String uri,
      final String content,
      final int version,
      final List<CodeActionRequest> requests,
      final WorkspaceTypeIndex typeIndex) {
    return submit(ctx -> ctx.codeAction(uri, content, version, requests, typeIndex));
  }

  public CompletableFuture<List<SemanticToken>> semanticTokens(
      final String uri, final String content, final int expectedVersion) {
    return submit(ctx -> ctx.semanticTokens(uri, content, expectedVersion));
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
    return submit(fn, () -> {});
  }

  private <T> CompletableFuture<T> submit(
      final Function<SourceAnalysisSession, T> fn, final CancelChecker cancelChecker) {
    final var future = new CompletableFuture<T>();
    if (closed) {
      future.completeExceptionally(new IllegalStateException("module worker is closed"));
      return future;
    }

    try {
      executor.execute(
          () -> {
            final var timer = Stopwatch.start();
            try {
              cancelChecker.checkCanceled();
              if (context == null) {
                context = contextFactory.get();
              }

              future.complete(fn.apply(context));
            } catch (final Throwable t) {
              final Error fatal = JavaSourceCompiler.fatalErrorCause(t);
              if (fatal != null) {
                try {
                  LOG.log(
                      Level.SEVERE,
                      fatal,
                      () ->
                          "[compiler] process fatal-error fatal %dms".formatted(timer.elapsedMs()));
                } finally {
                  processTerminator.accept(FATAL_EXIT_STATUS);
                }
              }
              future.completeExceptionally(t);
              IOUtil.rethrowIfError(t);
            }
          });
    } catch (final RejectedExecutionException e) {
      future.completeExceptionally(e);
    }
    return future;
  }
}
