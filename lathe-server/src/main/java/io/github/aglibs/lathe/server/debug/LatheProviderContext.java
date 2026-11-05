package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.adapter.ICompletionsProvider;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.microsoft.java.debug.core.adapter.IHotCodeReplaceProvider;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.adapter.IVirtualMachineManagerProvider;
import com.microsoft.java.debug.core.adapter.ProviderContext;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;

/**
 * The seam between Lathe and Microsoft java-debug: it supplies Lathe's implementations of the
 * adapter's provider interfaces. Every context registers the virtual-machine-manager provider (for
 * JDI attach) and the no-op hot-code-replace provider the adapter's {@code initialize} subscribes
 * to; stack-frame management uses java-debug's own default. A session context additionally
 * registers the {@link LatheSourceLookUpProvider}, the real {@link LatheEvaluationProvider}, and
 * the real {@link LatheCompletionsProvider}, all bound to the workspace's URI→worker routing and
 * the launched module's source roots; the no-arg handshake context registers only the no-op {@link
 * NoEvaluationProvider} and {@link NoCompletionsProvider} and no source lookup.
 */
public final class LatheProviderContext extends ProviderContext {

  public LatheProviderContext() {
    registerCommon();
    registerProvider(IEvaluationProvider.class, new NoEvaluationProvider());
    registerProvider(ICompletionsProvider.class, new NoCompletionsProvider());
  }

  public LatheProviderContext(final FrameSources frames, final WorkspaceTypeIndex typeIndex) {
    registerCommon();
    registerProvider(IEvaluationProvider.class, new LatheEvaluationProvider(frames));
    registerProvider(ISourceLookUpProvider.class, new LatheSourceLookUpProvider(frames));
    registerProvider(ICompletionsProvider.class, new LatheCompletionsProvider(frames, typeIndex));
  }

  private void registerCommon() {
    registerProvider(IHotCodeReplaceProvider.class, new LatheHotCodeReplaceProvider());
    registerProvider(
        IVirtualMachineManagerProvider.class, new LatheVirtualMachineManagerProvider());
  }
}
