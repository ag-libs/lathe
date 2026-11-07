package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.TestProcess;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LauncherTest {

  private static final long TIMEOUT_SECONDS = 10;

  @Test
  void launch_shortCommandLine_runsItAsIs() throws Exception {
    final List<String> argv = TestProcess.command("echo", "out:hello");

    final List<TranscriptLine> transcript = launch(argv, Map.of());

    assertThat(transcript)
        .containsExactly(
            command("config: test"),
            command(String.join(" ", argv)),
            new TranscriptLine(TranscriptLine.Stream.STDOUT, "hello"));
  }

  // Past Windows' command-line limit: the arguments after java move into an argfile, which java
  // expands, so the process still sees them all and the transcript names the argfile to rerun.
  @Test
  void launch_commandLinePastTheLimit_runsThroughAnArgfile() throws Exception {
    final List<String> command = TestProcess.command("echo", "out:hello");
    final String padding = "-Dlathe.test.padding=%s".formatted("x".repeat(40_000));
    final List<String> argv =
        Stream.concat(Stream.of(command.get(0), padding), command.stream().skip(1)).toList();

    final List<TranscriptLine> transcript = launch(argv, Map.of());

    final String shown = transcript.get(1).text();
    assertThat(shown).startsWith("%s @".formatted(argv.get(0))).hasSizeLessThan(1_000);
    assertThat(Path.of(shown.substring(shown.indexOf('@') + 1))).exists();
    assertThat(transcript.get(2))
        .isEqualTo(new TranscriptLine(TranscriptLine.Stream.STDOUT, "hello"));
  }

  // A run never writes to the program's input, so a read of it ends instead of waiting forever.
  @Test
  void launch_programReadingStdin_getsEndOfInput() throws Exception {
    final List<TranscriptLine> transcript = launch(TestProcess.command("read"), Map.of());

    assertThat(transcript).last().isEqualTo(new TranscriptLine(TranscriptLine.Stream.STDOUT, "-1"));
  }

  // What the launch unsets goes, what it sets wins, and the rest is the server's own.
  @Test
  void environment_unsetVariables_inheritedDropped_setOnesKept() {
    final var environment = new HashMap<String, String>();
    LatheLayout.JVM_OPTIONS_VARIABLES.forEach(name -> environment.put(name, "-javaagent:a.jar"));
    environment.put("PATH", "/bin");

    Launcher.environment(
        environment,
        new ResolvedLaunch(
            List.of("java"),
            Map.of("JDK_JAVA_OPTIONS", "-Dconfigured=1"),
            LatheLayout.JVM_OPTIONS_VARIABLES,
            null));

    assertThat(environment)
        .containsOnly(entry("PATH", "/bin"), entry("JDK_JAVA_OPTIONS", "-Dconfigured=1"));
  }

  @Test
  void environment_nothingUnset_keepsTheInheritedVariables() {
    final var environment = new HashMap<String, String>(Map.of("JAVA_TOOL_OPTIONS", "-Dx=1"));

    Launcher.environment(
        environment, new ResolvedLaunch(List.of("java"), Map.of(), List.of(), null));

    assertThat(environment).containsOnly(entry("JAVA_TOOL_OPTIONS", "-Dx=1"));
  }

  // Options the capture already has stay quiet, "--add-opens=x" against the replay's two-argument
  // form included; one the build's test JVM ran without is named; and a variable the run
  // configuration sets, or one the launch inherits, gets no note.
  @Test
  void unappliedOptionNotes_optionsTheBuildRanWithout_namedOnce() {
    final var launch =
        new ResolvedLaunch(
            List.of(
                "java",
                "-javaagent:a.jar",
                "-Dcaptured=1",
                "--add-opens",
                "java.base/java.lang=ALL-UNNAMED"),
            Map.of("JDK_JAVA_OPTIONS", "-Dconfigured=1"),
            LatheLayout.JVM_OPTIONS_VARIABLES,
            null);

    assertThat(
            Launcher.unappliedOptionNotes(
                Map.of(
                    "JAVA_TOOL_OPTIONS", " -javaagent:a.jar  -Dproxy=x ",
                    "_JAVA_OPTIONS", "-Dcaptured=1 --add-opens=java.base/java.lang=ALL-UNNAMED",
                    "JDK_JAVA_OPTIONS", "-Dother=1"),
                launch))
        .containsExactly(
            TranscriptLine.NOTE_PREFIX
                + "JAVA_TOOL_OPTIONS not applied: the build's test JVM ran without -Dproxy=x; set it"
                + " in the run configuration's env to add it");
    assertThat(
            Launcher.unappliedOptionNotes(
                Map.of("JAVA_TOOL_OPTIONS", "-Dproxy=x"),
                new ResolvedLaunch(List.of("java"), Map.of(), List.of(), null)))
        .as("a main launch inherits the variables")
        .isEmpty();
  }

  @Test
  void launch_jvmOptionVariableInTheRunConfiguration_reachesTheProgram() throws Exception {
    final List<TranscriptLine> transcript =
        launch(
            TestProcess.command("prop", "lathe.probe"),
            Map.of("JAVA_TOOL_OPTIONS", "-Dlathe.probe=configured"));

    assertThat(transcript).contains(new TranscriptLine(TranscriptLine.Stream.STDOUT, "configured"));
  }

  private static List<TranscriptLine> launch(final List<String> argv, final Map<String, String> env)
      throws Exception {
    final List<TranscriptLine> transcript = Collections.synchronizedList(new ArrayList<>());
    final LaunchSession session =
        Launcher.launch(
            new ResolvedLaunch(argv, env, LatheLayout.JVM_OPTIONS_VARIABLES, null),
            "test",
            null,
            transcript::add,
            result -> {});
    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).exitCode()).isZero();
    return List.copyOf(transcript);
  }

  private static TranscriptLine command(final String text) {
    return new TranscriptLine(TranscriptLine.Stream.COMMAND, text);
  }
}
