package io.github.aglibs.lathe.core;

public final class LatheFlags {

  public static final String DISABLED = "lathe.disabled";
  public static final String FORCE_SYNC = "lathe.sync.force";
  public static final String CAPTURE_ONLY = "lathe.capture.only";
  public static final String RESULTS_SINK = "lathe.results.sink";
  // Opt out of delegating non-google Spotless formatters to `mvn spotless:apply` (sync writes
  // `none`).
  public static final String SPOTLESS = "lathe.spotless";

  // Client init options {"lathe": {"style": {"formatter": {"engine": "...", "command": [..]}}}} —
  // the global-default style, same shape as the workspace style file; a project file overrides it.
  public static final String INIT_OPTIONS_KEY = "lathe";
  public static final String STYLE_OPTION = "style";
  public static final String FORMATTER_OPTION = "formatter";
  public static final String FORMATTER_ENGINE_OPTION = "engine";
  public static final String FORMATTER_COMMAND_OPTION = "command";
  public static final String FORMATTER_GOOGLE = "google";
  public static final String FORMATTER_AOSP = "aosp";
  public static final String FORMATTER_NONE = "none";
  public static final String FORMATTER_COMMAND = "command";
  public static final String FORMATTER_COMMAND_FILE = "command-file";
  public static final String FORMAT_FILE_TOKEN = "%FILE%";
  public static final String FORMAT_MODULE_TOKEN = "%MODULE%";
  public static final String FORMAT_MVN_TOKEN = "%MVN%";

  // Spotless step options carried in FormatterSpec.options, with Spotless's own defaults.
  public static final String FORMAT_REFLOW_LONG_STRINGS = "reflowLongStrings";
  public static final String FORMAT_REORDER_IMPORTS = "reorderImports";
  public static final String FORMAT_JAVADOC = "formatJavadoc";
  public static final boolean FORMAT_REFLOW_LONG_STRINGS_DEFAULT = false;
  public static final boolean FORMAT_REORDER_IMPORTS_DEFAULT = false;
  public static final boolean FORMAT_JAVADOC_DEFAULT = true;

  // Coarse client<->server contract version. The server advertises it via
  // capabilities.experimental.latheProtocol; the standalone client compares it against its own
  // (lua/lathe/version.lua) at on_init and warns on a mismatch, since a git-installed client and a
  // Maven-pinned server can drift. Bump ONLY on a breaking contract change (executeCommand names,
  // init_options shape, custom notifications such as lathe/sync) -- not on every release. A
  // drift-guard test keeps this in lockstep with version.lua's PROTOCOL.
  public static final int LATHE_PROTOCOL = 1;

  // Key under InitializeResult capabilities.experimental that carries LATHE_PROTOCOL to the client.
  public static final String PROTOCOL_CAPABILITY = "latheProtocol";

  private LatheFlags() {}

  public static boolean isDisabled() {
    final var disabled = System.getProperty(DISABLED);
    if ("true".equals(disabled)) {
      return true;
    }

    if ("false".equals(disabled)) {
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

  // Delegating non-google Spotless to `mvn spotless:apply` is on unless explicitly disabled.
  public static boolean isSpotlessDelegationEnabled() {
    return !"false".equals(System.getProperty(SPOTLESS));
  }
}
