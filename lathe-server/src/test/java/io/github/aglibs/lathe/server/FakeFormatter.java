package io.github.aglibs.lathe.server;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

// A formatter process for the command engines' tests, run on the test JDK's own java so the tests
// need no shell or Unix tools and run the same on every OS.
final class FakeFormatter {

  private FakeFormatter() {}

  // The java command that runs this class with the given arguments.
  static List<String> command(final String... args) {
    return Stream.concat(Stream.of(java().toString()), toolArgs(args).stream()).toList();
  }

  static Path java() {
    return Path.of(System.getProperty("java.home"), "bin", "java");
  }

  // What follows the tool in a template whose tool is this JDK's java (%MVN%).
  static List<String> toolArgs(final String... args) {
    return Stream.concat(
            Stream.of("-cp", classes(), FakeFormatter.class.getName()), Stream.of(args))
        .toList();
  }

  private static String classes() {
    try {
      return Path.of(
              FakeFormatter.class.getProtectionDomain().getCodeSource().getLocation().toURI())
          .toString();
    } catch (final URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  // upper [file]: uppercases the file in place, else stdin to stdout. name file: writes the file's
  // own name into it. write text file: writes text into the file. cwd: prints the working
  // directory. exit code: exits with that code, printing nothing. sleep: never finishes in time.
  public static void main(final String[] args) throws IOException, InterruptedException {
    switch (args[0]) {
      case "upper" -> upper(args);
      case "name" -> Files.writeString(Path.of(args[1]), Path.of(args[1]).getFileName().toString());
      case "write" -> Files.writeString(Path.of(args[2]), args[1]);
      case "cwd" -> System.out.print(Path.of("").toRealPath());
      case "exit" -> System.exit(Integer.parseInt(args[1]));
      case "sleep" -> Thread.sleep(5_000);
      default -> throw new IllegalArgumentException(args[0]);
    }
  }

  private static void upper(final String[] args) throws IOException {
    if (args.length == 1) {
      final String in = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
      System.out.print(in.toUpperCase(Locale.ROOT));
      return;
    }

    final Path file = Path.of(args[1]);
    Files.writeString(file, Files.readString(file).toUpperCase(Locale.ROOT));
  }
}
