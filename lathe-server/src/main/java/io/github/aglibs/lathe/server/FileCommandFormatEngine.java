package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

// Runs a build command that formats a file in place (e.g. `mvn spotless:apply`), unlike the
// stdin/stdout ExternalCommandFormatEngine. Touching the open file on disk would make the editor
// flicker or flag a write conflict, so it instead formats a throwaway sibling temp file in the same
// source directory (which still matches Spotless's src/**/*.java includes) and returns its content.
// The name is a dotfile so file-trees/fuzzy-finders that hide dotfiles (neo-tree, oil, Telescope)
// do not flash it during a slow in-place build; it still ends in .java, so Spotless formats it.
// Tokens: %MVN% -> buildTool, the tool that ran the last build (never searched for), %FILE% -> temp
// path, %MODULE% -> the file's module or ".", as moduleOf (the workspace's module registry) names
// it.
record FileCommandFormatEngine(
    List<String> command,
    Path workspaceRoot,
    Duration timeout,
    Function<Path, Optional<String>> moduleOf,
    Supplier<Optional<Path>> buildTool)
    implements FormatEngine {

  private static final Logger LOG = Logger.getLogger(FileCommandFormatEngine.class.getName());

  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  FileCommandFormatEngine {
    ValidCheck.check()
        .notEmpty(command, "command")
        .notNull(workspaceRoot, "workspaceRoot")
        .notNull(moduleOf, "moduleOf")
        .notNull(buildTool, "buildTool")
        .assertTrue(
            timeout != null && timeout.compareTo(Duration.ZERO) > 0, "timeout must be positive")
        .validate();
    command = List.copyOf(command);
  }

  @Override
  public String format(final String source, final Path file)
      throws IOException, InterruptedException {
    final Path scratch =
        Files.createTempFile(file.toAbsolutePath().getParent(), ".lathe-fmt-", ".java");
    try {
      Files.writeString(scratch, source, StandardCharsets.UTF_8);
      run(resolve(scratch));
      return Files.readString(scratch, StandardCharsets.UTF_8);
    } finally {
      deleteQuietly(scratch);
    }
  }

  private static void deleteQuietly(final Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[format] failed to delete scratch file %s".formatted(file));
    }
  }

  private List<String> resolve(final Path file) throws IOException {
    final String absolute = file.toAbsolutePath().toString();
    final String module = moduleOf.apply(file).orElse(".");
    // Only a command that names %MVN% needs a recorded build tool.
    final boolean usesMaven =
        command.stream().anyMatch(arg -> arg.contains(LatheFlags.FORMAT_MVN_TOKEN));
    final String maven =
        usesMaven
            ? buildTool
                .get()
                .map(Path::toString)
                .orElseThrow(
                    () ->
                        new IOException(
                            "%s needs the tool of a Lathe-enabled build; run one first"
                                .formatted(LatheFlags.FORMAT_MVN_TOKEN)))
            : "";
    return command.stream()
        .map(
            arg ->
                arg.replace(LatheFlags.FORMAT_MVN_TOKEN, maven)
                    .replace(LatheFlags.FORMAT_FILE_TOKEN, absolute)
                    .replace(LatheFlags.FORMAT_MODULE_TOKEN, module))
        .toList();
  }

  private void run(final List<String> argv) throws IOException, InterruptedException {
    final var builder = new ProcessBuilder(argv).directory(workspaceRoot.toFile());
    builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    builder.redirectError(ProcessBuilder.Redirect.INHERIT);
    FormatProcess.run(builder, timeout, argv);
  }
}
