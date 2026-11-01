package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ChildProcessesTest {

  // A debuggee suspended at a breakpoint never ends by itself: only the server can kill it.
  @Test
  void destroyOnExit_parentExits_childIsKilledOnlyWithTheHook() throws Exception {
    final ProcessHandle survivor = ProcessHandle.of(childOfExitedParent("exit")).orElseThrow();
    try {
      assertThat(survivor.isAlive()).isTrue();
    } finally {
      survivor.destroyForcibly();
    }

    // The child sleeps 5 s by itself, so ending within 2 s means the hook killed it.
    final long killed = childOfExitedParent("exit-destroying");
    final Optional<ProcessHandle> handle = ProcessHandle.of(killed);
    if (handle.isPresent()) {
      handle.get().onExit().get(2, TimeUnit.SECONDS);
    }

    assertThat(ProcessHandle.of(killed)).isEmpty();
  }

  private static long childOfExitedParent(final String then)
      throws IOException, InterruptedException {
    final String classPath =
        String.join(
            File.pathSeparator,
            TestCompiler.jarOf(TestProcess.class).toString(),
            TestCompiler.jarOf(ChildProcesses.class).toString());
    final Process parent =
        new ProcessBuilder(
                TestProcess.java().toString(),
                "-cp",
                classPath,
                TestProcess.class.getName(),
                "spawn",
                then)
            .redirectError(Redirect.DISCARD)
            .start();
    final String pid = new String(parent.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    parent.waitFor();
    return Long.parseLong(pid.trim());
  }
}
