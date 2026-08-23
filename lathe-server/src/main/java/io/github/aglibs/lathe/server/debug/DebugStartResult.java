package io.github.aglibs.lathe.server.debug;

/**
 * Result of {@code lathe.debug.start}: the loopback DAP port the editor's debug client connects to.
 * Phase 1 adds the JDWP port the adapter attaches to once the debuggee is launched suspended.
 */
public record DebugStartResult(int dapPort) {}
