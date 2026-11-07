package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.JavaArgfile;
import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class Launcher {

  private static final Logger LOG = Logger.getLogger(Launcher.class.getName());
  // Windows caps a command line at 32,767 characters, and a real project's classpath passes that.
  // Past this length everything after java goes into an argfile, which java expands. The rule is
  // the same on every OS, so it is tested everywhere.
  private static final int MAX_COMMAND_LINE = 30_000;

  private Launcher() {}

  public static LaunchSession launch(
      final ResolvedLaunch launch,
      final String configLabel,
      final Path resultsSink,
      final Consumer<TranscriptLine> onLine,
      final Consumer<TestResult> onResult)
      throws IOException {
    final List<String> argv = launch.argv();
    LOG.fine(() -> "[launch] argv=%s".formatted(argv));
    final List<String> command = commandLine(argv);
    // The config, then the command on its own line (copy-pasteable: an argfile stays on disk),
    // before any process output.
    onLine.accept(new TranscriptLine(TranscriptLine.Stream.COMMAND, "config: " + configLabel));
    onLine.accept(new TranscriptLine(TranscriptLine.Stream.COMMAND, String.join(" ", command)));
    final var processBuilder = new ProcessBuilder(command);
    final List<TranscriptLine> notes =
        unappliedOptionNotes(processBuilder.environment(), launch).stream()
            .map(note -> new TranscriptLine(TranscriptLine.Stream.COMMAND, note))
            .toList();
    environment(processBuilder.environment(), launch);
    if (launch.cwd() != null) {
      processBuilder.directory(launch.cwd().toFile());
    }

    final Process process = processBuilder.start();
    // Nothing ever writes to the program's input; closed, a read of System.in ends at once instead
    // of waiting until the run is cancelled.
    process.getOutputStream().close();
    return new LaunchSession(process, notes, resultsSink, onLine, onResult);
  }

  // The server's environment, less what the launch does not inherit, plus what it sets.
  static void environment(final Map<String, String> environment, final ResolvedLaunch launch) {
    launch.unsetEnv().forEach(environment::remove);
    environment.putAll(launch.env());
  }

  // A variable the launch does not inherit may carry options the build's test JVM ran without (set
  // for the editor only, or since the last build): say so, rather than drop them silently.
  static List<String> unappliedOptionNotes(
      final Map<String, String> inherited, final ResolvedLaunch launch) {
    return launch.unsetEnv().stream()
        .filter(name -> inherited.containsKey(name) && !launch.env().containsKey(name))
        .flatMap(name -> unappliedOptionNote(name, inherited.get(name), launch.argv()).stream())
        .toList();
  }

  // Split on whitespace, which misreads a quoted option: so this only ever adds the note, never
  // decides what the launch gets.
  private static Optional<String> unappliedOptionNote(
      final String name, final String value, final List<String> argv) {
    final List<String> missing =
        Arrays.stream(value.trim().split("\\s+"))
            .filter(option -> !option.isEmpty() && !argv.contains(option))
            .toList();
    if (missing.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(
        "%s%s not applied: the build's test JVM ran without %s; set it in the run configuration's env to add it"
            .formatted(TranscriptLine.NOTE_PREFIX, name, String.join(" ", missing)));
  }

  private static List<String> commandLine(final List<String> argv) throws IOException {
    if (String.join(" ", argv).length() <= MAX_COMMAND_LINE) {
      return List.copyOf(argv);
    }

    final Path argfile =
        Files.createTempFile(LatheLayout.LAUNCH_ARGFILE_PREFIX, LatheLayout.ARGFILE_SUFFIX);
    JavaArgfile.write(
        argfile.getParent(), argfile, argv.subList(1, argv.size()), JavaArgfile.NATIVE_CHARSET);
    return List.of(argv.get(0), "@" + argfile);
  }
}
