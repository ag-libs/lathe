package io.github.aglibs.lathe.core;

public final class LatheFlags {

  public static final String SKIP = "lathe.skip";
  public static final String FORCE_SYNC = "lathe.sync.force";
  public static final String CAPTURE_ONLY = "lathe.capture.only";
  public static final String RESULTS_SINK = "lathe.results.sink";

  // LSP initialization option keys, sent by the editor client as
  // {"lathe": {"formatter": "google"}} and read by the server to gate formatting.
  public static final String INIT_OPTIONS_KEY = "lathe";
  public static final String FORMATTER_OPTION = "formatter";
  public static final String FORMATTER_GOOGLE = "google";

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

  public static boolean isCaptureOnly() {
    return "true".equals(System.getProperty(CAPTURE_ONLY));
  }
}
