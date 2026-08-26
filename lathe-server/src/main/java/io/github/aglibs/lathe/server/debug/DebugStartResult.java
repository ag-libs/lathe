package io.github.aglibs.lathe.server.debug;

/**
 * Result of {@code lathe.debug.test} / {@code lathe.debug.main}: the loopback DAP port the editor's
 * debug client connects to, and the JDWP port the adapter attaches to once the suspended debuggee
 * is launched.
 */
public record DebugStartResult(int dapPort, int jdwpPort) {}
