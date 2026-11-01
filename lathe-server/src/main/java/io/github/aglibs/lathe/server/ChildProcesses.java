package io.github.aglibs.lathe.server;

// The processes a server starts (test runs, a debuggee suspended at a breakpoint, formatters) do
// not
// end with it by themselves, so they are killed when its JVM exits: on exit, end of input, or
// SIGTERM. Forcibly and without waiting, as a shutdown hook must finish quickly.
public final class ChildProcesses {

  private ChildProcesses() {}

  public static void destroyOnExit() {
    Runtime.getRuntime().addShutdownHook(new Thread(ChildProcesses::destroyAll, "lathe-children"));
  }

  private static void destroyAll() {
    ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
  }
}
