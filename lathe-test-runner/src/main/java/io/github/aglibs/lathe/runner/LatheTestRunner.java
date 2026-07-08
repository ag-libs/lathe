package io.github.aglibs.lathe.runner;

import java.io.PrintStream;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

public final class LatheTestRunner {

  static final int EXIT_OK = 0;
  static final int EXIT_FAILURE = 1;
  static final int EXIT_USAGE = 2;

  private LatheTestRunner() {}

  public static void main(final String[] args) {
    System.exit(run(args, System.err));
  }

  static int run(final String[] args) {
    return run(args, System.err);
  }

  static int run(final String[] args, final PrintStream err) {
    final LauncherDiscoveryRequest request;
    try {
      request = discoveryRequest(args);
    } catch (final IllegalArgumentException e) {
      err.println(e.getMessage());
      return EXIT_USAGE;
    }

    final var listener = new SummaryGeneratingListener();
    final var launcher = LauncherFactory.create();
    launcher.execute(request, listener);
    return listener.getSummary().getTotalFailureCount() == 0 ? EXIT_OK : EXIT_FAILURE;
  }

  private static LauncherDiscoveryRequest discoveryRequest(final String[] args) {
    final var builder = LauncherDiscoveryRequestBuilder.request();
    builder.selectors(TestSelectorParser.parse(args));
    return builder.build();
  }
}
