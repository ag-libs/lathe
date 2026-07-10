package io.github.aglibs.lathe.runner;

import java.io.PrintStream;
import java.nio.file.Path;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

public final class LatheTestRunner {

  static final int EXIT_OK = 0;
  static final int EXIT_FAILURE = 1;
  static final int EXIT_USAGE = 2;

  /**
   * Results-sink system property. Mirrors {@code LatheFlags.RESULTS_SINK} in {@code lathe-core} by
   * value only: the runner rides the user's test classpath, so it must not depend on {@code
   * lathe-core} (which would drag gson onto that classpath). A drift-guard test pins the two equal.
   */
  static final String RESULTS_SINK = "lathe.results.sink";

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

    final var summary = new SummaryGeneratingListener();
    final var launcher = LauncherFactory.create();
    launcher.execute(request, listeners(summary, err));
    return summary.getSummary().getTotalFailureCount() == 0 ? EXIT_OK : EXIT_FAILURE;
  }

  private static TestExecutionListener[] listeners(
      final SummaryGeneratingListener summary, final PrintStream err) {
    final String sink = System.getProperty(RESULTS_SINK);
    if (sink == null) {
      return new TestExecutionListener[] {summary};
    }

    return new TestExecutionListener[] {summary, new ResultsListener(Path.of(sink), err)};
  }

  private static LauncherDiscoveryRequest discoveryRequest(final String[] args) {
    final var builder = LauncherDiscoveryRequestBuilder.request();
    builder.selectors(TestSelectorParser.parse(args));
    return builder.build();
  }
}
