package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.adapter.IHotCodeReplaceProvider;
import com.microsoft.java.debug.core.adapter.IVirtualMachineManagerProvider;
import com.microsoft.java.debug.core.adapter.ProviderContext;

/**
 * The seam between Lathe and Microsoft java-debug: it supplies Lathe's implementations of the
 * adapter's provider interfaces. It registers the virtual-machine-manager provider (for JDI attach)
 * and the no-op hot-code-replace provider the adapter's {@code initialize} subscribes to;
 * stack-frame management uses java-debug's own default. The single-module {@link
 * LatheSourceLookUpProvider} is registered by the attach orchestration, which supplies the launched
 * module's source roots.
 */
public final class LatheProviderContext extends ProviderContext {

  public LatheProviderContext() {
    registerProvider(IHotCodeReplaceProvider.class, new LatheHotCodeReplaceProvider());
    registerProvider(
        IVirtualMachineManagerProvider.class, new LatheVirtualMachineManagerProvider());
  }
}
