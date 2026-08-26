package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.adapter.ICompletionsProvider;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.microsoft.java.debug.core.adapter.IHotCodeReplaceProvider;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.adapter.IVirtualMachineManagerProvider;
import com.microsoft.java.debug.core.adapter.ProviderContext;
import io.github.aglibs.lathe.server.module.WorkspaceModuleRegistry;
import java.nio.file.Path;
import java.util.List;

/**
 * The seam between Lathe and Microsoft java-debug: it supplies Lathe's implementations of the
 * adapter's provider interfaces. Every context registers the virtual-machine-manager provider (for
 * JDI attach), the no-op hot-code-replace provider the adapter's {@code initialize} subscribes to,
 * and the no-op completions provider its {@code attach} handler requires; stack-frame management
 * uses java-debug's own default. A session context additionally registers the {@link
 * LatheSourceLookUpProvider} and the real {@link LatheEvaluationProvider}, both bound to the
 * workspace's URI→worker routing and the launched module's source roots; the no-arg handshake
 * context registers only a {@link NoEvaluationProvider} and no source lookup.
 */
public final class LatheProviderContext extends ProviderContext {

  public LatheProviderContext() {
    registerCommon();
    registerProvider(IEvaluationProvider.class, new NoEvaluationProvider());
  }

  public LatheProviderContext(
      final WorkspaceModuleRegistry workspace, final List<Path> sourceRoots) {
    registerCommon();
    registerProvider(
        IEvaluationProvider.class, new LatheEvaluationProvider(workspace, sourceRoots));
    registerProvider(
        ISourceLookUpProvider.class, new LatheSourceLookUpProvider(workspace, sourceRoots));
  }

  private void registerCommon() {
    registerProvider(IHotCodeReplaceProvider.class, new LatheHotCodeReplaceProvider());
    registerProvider(ICompletionsProvider.class, new LatheCompletionsProvider());
    registerProvider(
        IVirtualMachineManagerProvider.class, new LatheVirtualMachineManagerProvider());
  }
}
