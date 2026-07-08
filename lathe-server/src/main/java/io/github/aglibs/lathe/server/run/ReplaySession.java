package io.github.aglibs.lathe.server.run;

public final class ReplaySession {

  private final Process process;

  ReplaySession(final Process process) {
    this.process = process;
  }

  public long pid() {
    return process.pid();
  }

  public void cancel() {
    process.destroy();
  }

  public int awaitExit() throws InterruptedException {
    return process.waitFor();
  }
}
