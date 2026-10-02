package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

// Runs a build command that formats a file in place (e.g. `mvn spotless:apply`), unlike the
// stdin/stdout ExternalCommandFormatEngine. Touching the open file on disk would make the editor
// flicker or flag a write conflict, so it instead formats a throwaway sibling temp file in the same
// source directory (which still matches Spotless's src/**/*.java includes) and returns its content.
// Tokens: %MVN% -> mvnd/mvnw/mvn, %FILE% -> temp path, %MODULE% -> module dir or ".".
record FileCommandFormatEngine(List<String> command, Path workspaceRoot, Duration timeout)
    implements FormatEngine {

  private static final Logger LOG = Logger.getLogger(FileCommandFormatEngine.class.getName());

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
    final Path scratch =
        Files.createTempFile(file.toAbsolutePath().getParent(), "lathe-fmt-", ".java");
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

  private List<String> resolve(final Path file) {
    final String absolute = file.toAbsolutePath().toString();
    final String module = moduleRelativePath(file);
    final String maven = mavenExecutable(workspaceRoot, System.getenv("PATH"));
    return command.stream()
        .map(
            arg ->
                arg.replace(LatheFlags.FORMAT_MVN_TOKEN, maven)
                    .replace(LatheFlags.FORMAT_FILE_TOKEN, absolute)
                    .replace(LatheFlags.FORMAT_MODULE_TOKEN, module))
        .toList();
  }

  // %MVN% -> the fastest/most-faithful Maven in the server env: the mvnd daemon, else the project's
  // ./mvnw wrapper, else plain mvn. Package-private (with an explicit PATH) for tests.
  static String mavenExecutable(final Path workspaceRoot, final String path) {
    if (onPath("mvnd", path)) {
      return "mvnd";
    }

    final Path wrapper = workspaceRoot.resolve("mvnw");
    if (Files.isExecutable(wrapper)) {
      return wrapper.toAbsolutePath().toString();
    }

    return "mvn";
  }

  private static boolean onPath(final String name, final String path) {
    if (path == null) {
      return false;
    }

    for (final String dir : path.split(File.pathSeparator)) {
      if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, name))) {
        return true;
      }
    }

    return false;
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
