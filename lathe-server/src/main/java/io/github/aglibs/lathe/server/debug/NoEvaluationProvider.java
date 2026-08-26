package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.IEvaluatableBreakpoint;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import java.util.concurrent.CompletableFuture;

/**
 * A no-op expression-evaluation provider for the handshake context, which never attaches a debuggee
 * and so never evaluates. A real debug session uses {@link LatheEvaluationProvider} instead. The
 * adapter still requires an {@link IEvaluationProvider} to be registered, so this satisfies the
 * contract by failing fast.
 */
final class NoEvaluationProvider implements IEvaluationProvider {

  @Override
  public boolean isInEvaluation(final ThreadReference thread) {
    return false;
  }

  @Override
  public CompletableFuture<Value> evaluate(
      final String expression, final ThreadReference thread, final int depth) {
    return unsupported();
  }

  @Override
  public CompletableFuture<Value> evaluate(
      final String expression, final ObjectReference object, final ThreadReference thread) {
    return unsupported();
  }

  @Override
  public CompletableFuture<Value> evaluateForBreakpoint(
      final IEvaluatableBreakpoint breakpoint, final ThreadReference thread) {
    return unsupported();
  }

  @Override
  public CompletableFuture<Value> invokeMethod(
      final ObjectReference object,
      final String methodName,
      final String methodSignature,
      final Value[] args,
      final ThreadReference thread,
      final boolean invokeSuper) {
    return unsupported();
  }

  @Override
  public void clearState(final ThreadReference thread) {}

  private static CompletableFuture<Value> unsupported() {
    return CompletableFuture.failedFuture(
        new UnsupportedOperationException(
            "expression evaluation is not available in this context"));
  }
}
