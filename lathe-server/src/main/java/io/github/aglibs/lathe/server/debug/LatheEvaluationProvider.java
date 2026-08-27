package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.IEvaluatableBreakpoint;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.sun.jdi.ClassType;
import com.sun.jdi.InterfaceType;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import io.github.aglibs.lathe.server.analysis.AttributedExpression;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import io.github.aglibs.lathe.server.module.WorkspaceModuleRegistry;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
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

  // The synthetic parameter object-scoped evaluation attributes the expression against and binds to
  // the target object (see interpretOn); an unlikely name so it never shadows a real member.
  private static final String RECEIVER = "__LATHE_RECV__";

  private final FrameSources frames;

  // One per-JDI-thread lock does both jobs the contract needs: it serializes that thread's whole
  // evaluations (an invocation momentarily resumes the thread, which invalidates every StackFrame,
  // so a concurrent evaluation reading a frame on the same thread would throw
  // InvalidStackFrameException) while allowing different threads to evaluate in parallel, and its
  // held state is the in-evaluation signal that isInEvaluation reads without blocking (via
  // isLocked()) from the event-hub thread to suppress nested events.
  private final Map<Long, ReentrantLock> perThread = new ConcurrentHashMap<>();

  LatheEvaluationProvider(final WorkspaceModuleRegistry workspace, final List<Path> sourceRoots) {
    this.frames = new FrameSources(workspace, sourceRoots);
  }

  @Override
  public CompletableFuture<Value> evaluate(
      final String expression, final ThreadReference thread, final int depth) {
    return evaluateGuarded(expression, thread, depth);
  }

  @Override
  public CompletableFuture<Value> evaluate(
      final String expression, final ObjectReference object, final ThreadReference thread) {
    return withThreadLock(
        thread,
        () -> {
          try {
            return CompletableFuture.completedFuture(interpretOn(expression, object, thread));
          } catch (final Exception e) {
            return CompletableFuture.failedFuture(evaluationError(expression, e));
          }
        });
  }

  /**
   * Object-scoped evaluation: attribute {@code expression} against an accessible type of {@code
   * object} (so its members resolve) and interpret it with the synthetic receiver bound to the
   * object. Used by the adapter's logical collection/map views. Uses the thread's top frame only
   * for a compilation context (module worker) and the interpreter's up-front captures.
   */
  private Value interpretOn(
      final String expression, final ObjectReference object, final ThreadReference thread)
      throws Exception {
    final StackFrame frame = thread.frame(0);
    final Path file =
        frames
            .fileFor(frame.location())
            .orElseThrow(() -> new EvaluationException("source not found for " + frame.location()));
    final CompilationWorker worker =
        frames
            .workerFor(file)
            .orElseThrow(() -> new EvaluationException("no module worker for " + file));
    final String receiverType = accessibleTypeName(object.referenceType()).replace('$', '.');
    final AttributedExpression attributed =
        worker
            .attributeReceiverExpression(
                file.toUri().toString(), receiverType, RECEIVER, expression)
            .join()
            .orElseThrow(() -> new EvaluationException("could not attribute: " + expression));
    final Value value =
        new JdiInterpreter(
                attributed, frame, 0, body -> invokeGuarded(thread, body), Map.of(RECEIVER, object))
            .evaluate();
    LOG.fine(() -> "[eval:object] %s on %s".formatted(expression, object.referenceType().name()));
    return value;
  }

  /**
   * The most-derived public type in {@code type}'s hierarchy — the runtime type when it is public,
   * else the nearest public superclass (e.g. {@code AbstractCollection}/{@code AbstractMap}, which
   * still declare the logical-structure members), falling back to a public interface. Non-public
   * runtime types (e.g. {@code java.util.ImmutableCollections$ListN}) cannot be named in source, so
   * attribution must target an accessible supertype.
   */
  private static String accessibleTypeName(final ReferenceType type) {
    ReferenceType current = type;
    while (current != null) {
      if (Modifier.isPublic(current.modifiers()) && !"java.lang.Object".equals(current.name())) {
        return current.name();
      }

      current = current instanceof final ClassType classType ? classType.superclass() : null;
    }

    final List<InterfaceType> interfaces =
        type instanceof final ClassType classType ? classType.allInterfaces() : List.of();
    return interfaces.stream()
        .filter(candidate -> Modifier.isPublic(candidate.modifiers()))
        .map(ReferenceType::name)
        .findFirst()
        .orElse("java.lang.Object");
  }

  @Override
  public CompletableFuture<Value> evaluateForBreakpoint(
      final IEvaluatableBreakpoint breakpoint, final ThreadReference thread) {
    final String condition = breakpoint.getCondition();
    if (condition == null || condition.isBlank()) {
      return CompletableFuture.failedFuture(
          new EvaluationException("only conditional breakpoints are supported yet (no logpoints)"));
    }

    return evaluateGuarded(condition, thread, 0);
  }

  /**
   * Interprets {@code expression} at {@code depth} under {@code thread}'s serialization lock, held
   * for the whole evaluation so a frame read never races another evaluation's invocation on the
   * same thread. The lock is reentrant, so the interpreter's own invocations (via {@link
   * #invokeGuarded}) re-enter it rather than deadlock.
   */
  private CompletableFuture<Value> evaluateGuarded(
      final String expression, final ThreadReference thread, final int depth) {
    return withThreadLock(
        thread,
        () -> {
          try {
            return CompletableFuture.completedFuture(interpret(expression, thread, depth));
          } catch (final Exception e) {
            return CompletableFuture.failedFuture(evaluationError(expression, e));
          }
        });
  }

  /** Runs {@code body} under {@code thread}'s reentrant serialization lock. */
  private <T> T withThreadLock(final ThreadReference thread, final Supplier<T> body) {
    final ReentrantLock lock =
        perThread.computeIfAbsent(thread.uniqueID(), id -> new ReentrantLock());
    lock.lock();
    try {
      return body.get();
    } finally {
      lock.unlock();
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
    try {
      return CompletableFuture.completedFuture(
          invokeGuarded(
              thread,
              () -> {
                final Method method =
                    object.referenceType().methodsByName(methodName, methodSignature).stream()
                        .findFirst()
                        .orElseThrow(
                            () ->
                                new EvaluationException(
                                    "no method %s%s".formatted(methodName, methodSignature)));
                final List<Value> arguments = args == null ? List.of() : Arrays.asList(args);
                return object.invokeMethod(
                    thread, method, arguments, ObjectReference.INVOKE_SINGLE_THREADED);
              }));
    } catch (final Exception e) {
      return CompletableFuture.failedFuture(evaluationError(methodName, e));
    }
  }

  /**
   * Runs a JDI invocation under {@code thread}'s serialization lock — the seam the interpreter uses
   * so a call made while evaluating honours the same per-thread discipline as this provider's own
   * invocations (the held lock is also what {@link #isInEvaluation} observes).
   */
  private Value invokeGuarded(
      final ThreadReference thread, final JdiInterpreter.InvocationBody body) {
    return withThreadLock(
        thread,
        () -> {
          try {
            return body.run();
          } catch (final Exception e) {
            throw new EvaluationException("invocation failed: " + e.getMessage(), e);
          }
        });
  }

  @Override
  public boolean isInEvaluation(final ThreadReference thread) {
    final ReentrantLock lock = perThread.get(thread.uniqueID());
    return lock != null && lock.isLocked();
  }

  @Override
  public void clearState(final ThreadReference thread) {
    // The lock releases in evaluateGuarded/invokeGuarded's finally, so there is no lingering state
    // to clear; drop the entry when it is idle to keep the map bounded to live threads.
    perThread.computeIfPresent(thread.uniqueID(), (id, lock) -> lock.isLocked() ? lock : null);
  }

  private Value interpret(final String expression, final ThreadReference thread, final int depth)
      throws Exception {
    final StackFrame frame = thread.frame(depth);
    final Location location = frame.location();
    final Path file =
        frames
            .fileFor(location)
            .orElseThrow(() -> new EvaluationException("source not found for " + location));
    final String content = Files.readString(file);
    final CompilationWorker worker =
        frames
            .workerFor(file)
            .orElseThrow(() -> new EvaluationException("no module worker for " + file));
    final AttributedExpression attributed =
        worker
            .attributeExpression(
                file.toUri().toString(), content, location.lineNumber(), expression)
            .join()
            .orElseThrow(() -> new EvaluationException("could not attribute: " + expression));
    final Value value =
        new JdiInterpreter(attributed, frame, depth, body -> invokeGuarded(thread, body), Map.of())
            .evaluate();
    LOG.fine(() -> "[eval] %s @ %s".formatted(expression, location));
    return value;
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
