package io.github.aglibs.lathe.server.debug;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import io.github.aglibs.lathe.analysis.TypeSourceLocator;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

// Resolves a suspended JDI frame's location to Lathe's source file and calls the module worker
// that owns it: the shared front half of the debugger's evaluation, completion and source lookup.
// The providers run on the debug adapter's threads, but a worker is reached only from the event
// loop: onLoop runs a task there, and workerFor (loop-confined) finds the file's worker in the
// current registry, since a sync replaces it mid-session.
public final class FrameSources {

  private final Function<Runnable, CompletableFuture<?>> onLoop;
  private final Function<Path, Optional<CompilationWorker>> workerFor;
  private final List<Path> sourceRoots;

  public FrameSources(
      final Function<Runnable, CompletableFuture<?>> onLoop,
      final Function<Path, Optional<CompilationWorker>> workerFor,
      final List<Path> sourceRoots) {
    this.onLoop = onLoop;
    this.workerFor = workerFor;
    this.sourceRoots = List.copyOf(sourceRoots);
  }

  List<Path> sourceRoots() {
    return sourceRoots;
  }

  /**
   * The source file backing {@code location}, found on a configured source root or, failing that,
   * by the declaring type's name. Empty when the location carries no source information or no file
   * matches.
   */
  Optional<Path> fileFor(final Location location) {
    try {
      final String sourcePath = location.sourcePath();
      final Optional<Path> onRoot =
          sourceRoots.stream()
              .map(root -> root.resolve(sourcePath))
              .filter(Files::exists)
              .findFirst();
      if (onRoot.isPresent()) {
        return onRoot;
      }

      return TypeSourceLocator.findSourceFile(location.declaringType().name(), sourceRoots);
    } catch (final AbsentInformationException e) {
      return Optional.empty();
    }
  }

  // The call's result on the worker that owns the file, or empty when no module owns it. The
  // lookup and the call run on the event loop; a request after the session closed fails, as the
  // loop runs nothing then.
  <T> CompletableFuture<Optional<T>> onWorker(
      final Path file, final Function<CompilationWorker, CompletableFuture<T>> call) {
    final var routed = new CompletableFuture<Optional<CompletableFuture<T>>>();
    onLoop
        .apply(() -> routed.complete(workerFor.apply(file).map(call)))
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null) {
                routed.completeExceptionally(failure);
              }
            });
    return routed.thenCompose(
        result ->
            result
                .map(future -> future.thenApply(Optional::of))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())));
  }
}
