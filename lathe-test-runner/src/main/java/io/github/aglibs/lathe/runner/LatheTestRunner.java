package io.github.aglibs.lathe.runner;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.platform.engine.Filter;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.launcher.listeners.TestExecutionSummary.Failure;

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

  /** Discover-mode system property, mirroring {@code LatheFlags.DISCOVER} by value. */
  static final String DISCOVER = "lathe.discover";

  private LatheTestRunner() {}

  public static void main(final String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  static int run(final String[] args) {
    return run(args, System.out, System.err);
  }

  static int run(final String[] args, final PrintStream out, final PrintStream err) {
    final LauncherDiscoveryRequest request;
    try {
      request = discoveryRequest(args);
    } catch (final IllegalArgumentException e) {
      err.println(e.getMessage());
      return EXIT_USAGE;
    }

    if (Boolean.getBoolean(DISCOVER)) {
      return discover(request, Path.of(System.getProperty(RESULTS_SINK)), err);
    }

    final var summary = new SummaryGeneratingListener();
    final var launcher = LauncherFactory.create();
    launcher.execute(request, listeners(summary, out, err));
    final TestExecutionSummary result = summary.getSummary();
    printFailures(result, out);
    return result.getTotalFailureCount() == 0 ? EXIT_OK : EXIT_FAILURE;
  }

  // Lists the tests a run of this request would execute, one record each into the results sink,
  // without running any of them.
  private static int discover(
      final LauncherDiscoveryRequest request, final Path sink, final PrintStream err) {
    final TestPlan plan = LauncherFactory.create().discover(request);
    final var lines = new ArrayList<String>();
    final var pending = new ArrayDeque<TestIdentifier>(plan.getRoots());
    while (!pending.isEmpty()) {
      final TestIdentifier id = pending.poll();
      ResultsListener.discovered(id).map(ResultsListener::toJson).ifPresent(lines::add);
      pending.addAll(plan.getChildren(id));
    }

    try {
      Files.write(sink, lines, StandardCharsets.UTF_8);
      return EXIT_OK;
    } catch (final IOException e) {
      err.printf("[discover] sink %s write failed: %s%n", sink, e.getMessage());
      return EXIT_FAILURE;
    }
  }

  /**
   * Writes each failed test's exception and full stack trace to the console, the only channel back
   * to the replay transcript. Nothing is printed on a clean run. Surefire prints this for a normal
   * {@code mvn test}; the replay runner stands in for Surefire, so it must print it too, otherwise
   * the output window and its stack-trace navigation have nothing to show.
   *
   * <p>Uses {@link Throwable#printStackTrace(PrintStream)} rather than {@code
   * TestExecutionSummary.printFailuresTo}: the latter omits the {@code at } prefix on frame lines,
   * which is exactly what the client's stack-frame parser keys on to make frames navigable.
   */
  private static void printFailures(final TestExecutionSummary summary, final PrintStream out) {
    final List<Failure> failures = summary.getFailures();
    if (failures.isEmpty()) {
      return;
    }

    for (final Failure failure : failures) {
      out.println(failure.getTestIdentifier().getDisplayName());
      failure.getException().printStackTrace(out);
      out.println();
    }

    out.flush();
  }

  private static TestExecutionListener[] listeners(
      final SummaryGeneratingListener summary, final PrintStream out, final PrintStream err) {
    final String sink = System.getProperty(RESULTS_SINK);
    if (sink == null) {
      return new TestExecutionListener[] {summary};
    }

    return new TestExecutionListener[] {summary, new ResultsListener(Path.of(sink), out, err)};
  }

  private static LauncherDiscoveryRequest discoveryRequest(final String[] args) {
    final TestSelectorParser.Request request = TestSelectorParser.parse(args);
    return LauncherDiscoveryRequestBuilder.request()
        .selectors(request.selectors())
        .filters(request.filters().toArray(Filter<?>[]::new))
        .build();
  }
}
