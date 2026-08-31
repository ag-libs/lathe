package io.github.aglibs.lathe.server.debug;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.java.debug.core.adapter.IDebugAdapterContext;
import com.microsoft.java.debug.core.adapter.IStackFrameManager;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import io.github.aglibs.lathe.server.module.WorkspaceModuleRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * DB-6: an invocation resumes the thread and invalidates the StackFrames java-debug cached for the
 * Variables view, so the provider must refresh that cache after every invocation.
 */
class LatheEvaluationProviderTest {

  private static ObjectReference invocableObject() throws Exception {
    final ObjectReference object = mock(ObjectReference.class);
    final ReferenceType type = mock(ReferenceType.class);
    final Method method = mock(Method.class);
    when(object.referenceType()).thenReturn(type);
    when(type.methodsByName("m", "()V")).thenReturn(List.of(method));
    when(object.invokeMethod(any(), any(), any(), anyInt())).thenReturn(mock(Value.class));
    return object;
  }

  @Test
  void invokeMethod_afterInvocation_reloadsJavaDebugStackFrames() throws Exception {
    final IStackFrameManager stackFrames = mock(IStackFrameManager.class);
    final IDebugAdapterContext context = mock(IDebugAdapterContext.class);
    when(context.getStackFrameManager()).thenReturn(stackFrames);

    final var provider =
        new LatheEvaluationProvider(mock(WorkspaceModuleRegistry.class), List.of());
    provider.initialize(context, Map.of());

    final ThreadReference thread = mock(ThreadReference.class);
    when(thread.uniqueID()).thenReturn(1L);

    provider.invokeMethod(invocableObject(), "m", "()V", null, thread, false).join();

    verify(stackFrames, times(1)).reloadStackFrames(thread);
  }

  @Test
  void invokeMethod_withoutInitialize_doesNotReloadOrThrow() throws Exception {
    // No initialize() means no captured frame manager: the reload is a no-op and the invocation
    // still completes normally (no NullPointerException from the missing cache).
    final var provider =
        new LatheEvaluationProvider(mock(WorkspaceModuleRegistry.class), List.of());
    final ThreadReference thread = mock(ThreadReference.class);
    when(thread.uniqueID()).thenReturn(1L);

    final ObjectReference object = invocableObject();
    assertThatCode(() -> provider.invokeMethod(object, "m", "()V", null, thread, false).join())
        .doesNotThrowAnyException();
  }

  @Test
  void invokeMethod_reloadFailure_isSwallowed() throws Exception {
    // A thread that is no longer suspended makes reloadStackFrames throw; the invocation result
    // must still be returned rather than surfacing the reload error.
    final IStackFrameManager stackFrames = mock(IStackFrameManager.class);
    final ThreadReference thread = mock(ThreadReference.class);
    when(thread.uniqueID()).thenReturn(1L);
    when(stackFrames.reloadStackFrames(thread)).thenThrow(new IllegalStateException("resumed"));
    final IDebugAdapterContext context = mock(IDebugAdapterContext.class);
    when(context.getStackFrameManager()).thenReturn(stackFrames);

    final var provider =
        new LatheEvaluationProvider(mock(WorkspaceModuleRegistry.class), List.of());
    provider.initialize(context, Map.of());

    assertThatCode(
            () -> provider.invokeMethod(invocableObject(), "m", "()V", null, thread, false).join())
        .doesNotThrowAnyException();
    verify(stackFrames).reloadStackFrames(thread);
  }
}
