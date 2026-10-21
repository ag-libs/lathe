package io.github.aglibs.lathe.install;

import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

// The entry point a launcher test starts on the real JDK: prints the JVM options it was started
// with, then "--", then its own arguments, one per line.
final class PrintLaunch {

  private PrintLaunch() {}

  // The argfile arguments that start this class.
  static List<String> argfile() throws URISyntaxException {
    final var classes =
        Path.of(PrintLaunch.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    return List.of("-cp", classes.toString(), PrintLaunch.class.getName());
  }

  public static void main(final String[] args) {
    ManagementFactory.getRuntimeMXBean().getInputArguments().forEach(System.out::println);
    System.out.println("--");
    List.of(args).forEach(System.out::println);
  }
}
