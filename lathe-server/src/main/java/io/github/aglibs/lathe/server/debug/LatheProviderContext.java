package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.adapter.IHotCodeReplaceProvider;
import com.microsoft.java.debug.core.adapter.ProviderContext;

/**
 * The seam between Lathe and Microsoft java-debug: it supplies Lathe's implementations of the
 * adapter's provider interfaces, backed by the {@code .lathe/} workspace model. Phase 0 registers
 * only the no-op hot-code-replace provider the adapter's {@code initialize} subscribes to; Phase 1
 * adds the source-lookup, virtual-machine-manager, and stack-frame providers required to attach.
 */
public final class LatheProviderContext extends ProviderContext {

  public LatheProviderContext() {
    registerProvider(IHotCodeReplaceProvider.class, new LatheHotCodeReplaceProvider());
  }
}
