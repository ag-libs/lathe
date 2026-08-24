package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.adapter.IVirtualMachineManagerProvider;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachineManager;

/**
 * Supplies the JDK's JDI virtual-machine manager; the adapter's attach handler uses its {@code
 * SocketAttachingConnector} to dial the suspended debuggee's JDWP port.
 */
public final class LatheVirtualMachineManagerProvider implements IVirtualMachineManagerProvider {

  @Override
  public VirtualMachineManager getVirtualMachineManager() {
    return Bootstrap.virtualMachineManager();
  }
}
