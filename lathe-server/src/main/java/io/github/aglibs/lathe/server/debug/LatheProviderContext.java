package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.adapter.IHotCodeReplaceProvider;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.adapter.IVirtualMachineManagerProvider;
import com.microsoft.java.debug.core.adapter.ProviderContext;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import java.nio.file.Path;
import java.util.List;

/**
 * The seam between Lathe and Microsoft java-debug: it supplies Lathe's implementations of the
 * adapter's provider interfaces. Every context registers the virtual-machine-manager provider (for
 * JDI attach) and the no-op hot-code-replace provider the adapter's {@code initialize} subscribes
 * to; stack-frame management uses java-debug's own default. A session context additionally
 * registers the single-module {@link LatheSourceLookUpProvider} bound to the launched module's
 * worker and source roots; the no-arg context (no source lookup) is used only for the handshake
 * path.
 */
public final class LatheProviderContext extends ProviderContext {

  public LatheProviderContext() {
    registerProvider(IHotCodeReplaceProvider.class, new LatheHotCodeReplaceProvider());
    registerProvider(
        IVirtualMachineManagerProvider.class, new LatheVirtualMachineManagerProvider());
  }

  public LatheProviderContext(final CompilationWorker worker, final List<Path> sourceRoots) {
    this();
    registerProvider(
        ISourceLookUpProvider.class, new LatheSourceLookUpProvider(worker, sourceRoots));
  }
}
