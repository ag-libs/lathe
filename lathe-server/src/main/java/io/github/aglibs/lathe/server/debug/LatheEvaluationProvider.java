package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.IEvaluatableBreakpoint;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import io.github.aglibs.lathe.server.analysis.AttributedExpression;
import io.github.aglibs.lathe.server.analysis.TypeSourceLocator;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import io.github.aglibs.lathe.server.module.WorkspaceModuleRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Read-only expression evaluation (eval v1): resolves the frame's source, attributes the user
 * expression in-frame (Stage 1), and interprets it over the suspended JDI frame (Stage 2),
 * returning a {@link Value} the adapter renders. Powers the DAP {@code evaluate} request (hover,
 * watches, console) and conditional breakpoints via {@link #evaluateForBreakpoint}. Reads run no
 * debuggee code; {@link #invokeMethod} exists for the adapter's {@code toString} rendering.
 * Failures complete the future exceptionally with a clear message — a bad expression never breaks
 * the session.
 */
final class LatheEvaluationProvider implements IEvaluationProvider {

  private static final Logger LOG = Logger.getLogger(LatheEvaluationProvider.class.getName());

  private final WorkspaceModuleRegistry workspace;
  private final List<Path> sourceRoots;

  // One per-JDI-thread lock does both jobs the contract needs: it serializes that thread's method
  // invocations (JDI forbids overlapping invocations on a thread) while allowing different threads
  // to invoke in parallel, and its held state is the in-evaluation signal that isInEvaluation reads
  // without blocking (via isLocked()) from the event-hub thread to suppress nested events.
  private final Map<Long, ReentrantLock> perThread = new ConcurrentHashMap<>();

  LatheEvaluationProvider(final WorkspaceModuleRegistry workspace, final List<Path> sourceRoots) {
    this.workspace = workspace;
    this.sourceRoots = List.copyOf(sourceRoots);
  }

  @Override
  public CompletableFuture<Value> evaluate(
      final String expression, final ThreadReference thread, final int depth) {
    try {
      return CompletableFuture.completedFuture(interpret(expression, thread.frame(depth)));
    } catch (final Exception e) {
      return CompletableFuture.failedFuture(evaluationError(expression, e));
    }
  }

  @Override
  public CompletableFuture<Value> evaluate(
      final String expression, final ObjectReference object, final ThreadReference thread) {
    return CompletableFuture.failedFuture(
        new EvaluationException("object-scoped evaluation is not supported yet"));
  }

  @Override
  public CompletableFuture<Value> evaluateForBreakpoint(
      final IEvaluatableBreakpoint breakpoint, final ThreadReference thread) {
    final String condition = breakpoint.getCondition();
    if (condition == null || condition.isBlank()) {
      return CompletableFuture.failedFuture(
          new EvaluationException("only conditional breakpoints are supported yet (no logpoints)"));
    }

    try {
      return CompletableFuture.completedFuture(interpret(condition, thread.frame(0)));
    } catch (final Exception e) {
      return CompletableFuture.failedFuture(evaluationError(condition, e));
    }
  }

  @Override
  public CompletableFuture<Value> invokeMethod(
      final ObjectReference object,
      final String methodName,
      final String methodSignature,
      final Value[] args,
      final ThreadReference thread,
      final boolean invokeSuper) {
    final ReentrantLock lock =
        perThread.computeIfAbsent(thread.uniqueID(), id -> new ReentrantLock());
    lock.lock();
    try {
      final Method method =
          object.referenceType().methodsByName(methodName, methodSignature).stream()
              .findFirst()
              .orElseThrow(
                  () ->
                      new EvaluationException(
                          "no method %s%s".formatted(methodName, methodSignature)));
      final List<Value> arguments = args == null ? List.of() : Arrays.asList(args);
      return CompletableFuture.completedFuture(
          object.invokeMethod(thread, method, arguments, ObjectReference.INVOKE_SINGLE_THREADED));
    } catch (final Exception e) {
      return CompletableFuture.failedFuture(evaluationError(methodName, e));
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean isInEvaluation(final ThreadReference thread) {
    final ReentrantLock lock = perThread.get(thread.uniqueID());
    return lock != null && lock.isLocked();
  }

  @Override
  public void clearState(final ThreadReference thread) {
    // The invocation lock releases in invokeMethod's finally, so there is no lingering state to
    // clear; drop the entry when it is idle to keep the map bounded to live threads.
    perThread.computeIfPresent(thread.uniqueID(), (id, lock) -> lock.isLocked() ? lock : null);
  }

  private Value interpret(final String expression, final StackFrame frame) throws Exception {
    final Location location = frame.location();
    final Path file = frameSource(location);
    final String content = Files.readString(file);
    final CompilationWorker worker =
        workspace
            .moduleSourceFor(file)
            .map(workspace::workerFor)
            .orElseThrow(() -> new EvaluationException("no module worker for " + file));
    final AttributedExpression attributed =
        worker
            .attributeExpression(
                file.toUri().toString(), content, location.lineNumber(), expression)
            .join()
            .orElseThrow(() -> new EvaluationException("could not attribute: " + expression));
    final Value value = new JdiInterpreter(attributed, frame).evaluate();
    LOG.fine(() -> "[eval] %s @ %s".formatted(expression, location));
    return value;
  }

  private Path frameSource(final Location location) throws Exception {
    final String sourcePath = location.sourcePath();
    final Optional<Path> onSourceRoot =
        sourceRoots.stream()
            .map(root -> root.resolve(sourcePath))
            .filter(Files::exists)
            .findFirst();
    return onSourceRoot.orElseGet(
        () ->
            TypeSourceLocator.findSourceFile(location.declaringType().name(), sourceRoots)
                .orElseThrow(() -> new EvaluationException("source not found for " + sourcePath)));
  }

  private static EvaluationException evaluationError(final String expression, final Throwable e) {
    if (e instanceof final EvaluationException evaluation) {
      return evaluation;
    }

    final Throwable cause = e.getCause() instanceof EvaluationException ? e.getCause() : e;
    return new EvaluationException(
        "cannot evaluate '%s': %s".formatted(expression, cause.getMessage()), cause);
  }
}
