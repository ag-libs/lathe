package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.JavaArgfile;
import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
      final List<String> argv,
      final String configLabel,
      final Path resultsSink,
      final Consumer<TranscriptLine> onLine,
      final Consumer<TestResult> onResult,
      final Map<String, String> env,
      final Path cwd)
      throws IOException {
    LOG.fine(() -> "[launch] argv=%s".formatted(argv));
    final List<String> command = commandLine(argv);
    // The config, then the command on its own line (copy-pasteable: an argfile stays on disk),
    // before any process output.
    onLine.accept(new TranscriptLine(TranscriptLine.Stream.COMMAND, "config: " + configLabel));
    onLine.accept(new TranscriptLine(TranscriptLine.Stream.COMMAND, String.join(" ", command)));
    final var processBuilder = new ProcessBuilder(command);
    processBuilder.environment().putAll(env);
    if (cwd != null) {
      processBuilder.directory(cwd.toFile());
    }

    final Process process = processBuilder.start();
    return new LaunchSession(process, resultsSink, onLine, onResult);
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
