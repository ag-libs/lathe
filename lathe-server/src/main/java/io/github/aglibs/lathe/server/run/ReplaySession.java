package io.github.aglibs.lathe.server.run;

import java.util.concurrent.CompletableFuture;

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

  public CompletableFuture<Integer> onExit() {
    return process.onExit().thenApply(Process::exitValue);
  }
}
