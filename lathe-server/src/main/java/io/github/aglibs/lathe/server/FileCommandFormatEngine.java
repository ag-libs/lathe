package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

// Formats by running a build command that rewrites the file in place (e.g. `mvn spotless:apply`),
// unlike the stdin/stdout ExternalCommandFormatEngine: write the buffer to `file`, run the command,
// read `file` back. %FILE% -> absolute path; %MODULE% -> the file's module dir (nearest ancestor
// pom.xml) relative to the workspace root, or "." for the root, so `-pl %MODULE%` scopes the build.
record FileCommandFormatEngine(List<String> command, Path workspaceRoot, Duration timeout)
    implements FormatEngine {

  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  FileCommandFormatEngine {
    ValidCheck.check()
        .notEmpty(command, "command")
        .notNull(workspaceRoot, "workspaceRoot")
        .assertTrue(timeout != null && timeout.isPositive(), "timeout must be positive")
        .validate();
    command = List.copyOf(command);
  }

  @Override
  public String format(final String source, final Path file)
      throws IOException, InterruptedException {
    Files.writeString(file, source, StandardCharsets.UTF_8);
    run(resolve(file));
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  private List<String> resolve(final Path file) {
    final String absolute = file.toAbsolutePath().toString();
    final String module = moduleRelativePath(file);
    return command.stream()
        .map(
            arg ->
                arg.replace(LatheFlags.FORMAT_FILE_TOKEN, absolute)
                    .replace(LatheFlags.FORMAT_MODULE_TOKEN, module))
        .toList();
  }

  // The file's Maven module for `-pl`: the nearest ancestor holding a pom.xml, relative to the
  // workspace root, or "." when that is the root itself.
  private String moduleRelativePath(final Path file) {
    var dir = file.toAbsolutePath().getParent();
    while (dir != null && dir.startsWith(workspaceRoot)) {
      if (Files.exists(dir.resolve(LatheLayout.POM_XML))) {
        final String rel = workspaceRoot.relativize(dir).toString();
        return rel.isEmpty() ? "." : rel;
      }

      dir = dir.getParent();
    }

    return ".";
  }

  private void run(final List<String> argv) throws IOException, InterruptedException {
    final var builder = new ProcessBuilder(argv).directory(workspaceRoot.toFile());
    builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    builder.redirectError(ProcessBuilder.Redirect.INHERIT);
    FormatProcess.run(builder, timeout, argv);
  }
}
