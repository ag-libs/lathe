package io.github.aglibs.lathe.core;

public final class LatheFlags {

  public static final String SKIP = "lathe.skip";
  public static final String FORCE_SYNC = "lathe.sync.force";
  public static final String TEST_CAPTURE_SKIP_EXECUTION = "latheSkipTests";
  public static final String RESULTS_SINK = "lathe.results.sink";

  private LatheFlags() {}

  public static boolean isDisabled() {
    final var skip = System.getProperty(SKIP);
    if ("true".equals(skip)) {
      return true;
    }

    if ("false".equals(skip)) {
      return false;
    }

    return System.getenv("CI") != null;
  }

  public static boolean isForcedSync() {
    return "true".equals(System.getProperty(FORCE_SYNC));
  }

  public static boolean isTestExecutionSkipped() {
    return "true".equals(System.getProperty(TEST_CAPTURE_SKIP_EXECUTION));
  }
}
