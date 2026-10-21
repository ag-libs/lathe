package io.github.aglibs.lathe.core;

import java.util.List;
import java.util.stream.Stream;

public final class LatheFlags {

  public static final String DISABLED = "lathe.disabled";
  public static final String FORCE_SYNC = "lathe.sync.force";
  public static final String CAPTURE_ONLY = "lathe.capture.only";
  public static final String BUILD_DIR = "lathe.buildDir";
  public static final String RESULTS_SINK = "lathe.results.sink";
  // How often the server scans for files changed outside the editor, in ms (default 2000).
  public static final String SCAN_INTERVAL_MS = "lathe.scanIntervalMs";
  // For every JVM Lathe starts (its server, launched runs and tests): stdout and stderr in UTF-8,
  // as Lathe reads them, instead of the console code page on Windows. JDK 19+; older JDKs ignore
  // the properties.
  public static final List<String> UTF8_OUTPUT_JVM_ARGS =
      List.of("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8");

  // Client init options {"lathe": {"style": {"formatter": {"engine": "...", "command": [..]}}}} —
  // the global-default style, same shape as the workspace style file; a project file overrides it.
  public static final String INIT_OPTIONS_KEY = "lathe";
  public static final String STYLE_OPTION = "style";
  public static final String FORMATTER_OPTION = "formatter";
  public static final String FORMATTER_ENGINE_OPTION = "engine";
  public static final String FORMATTER_COMMAND_OPTION = "command";
  public static final String FORMATTER_GOOGLE = "google";
  public static final String FORMATTER_AOSP = "aosp";
  public static final String FORMATTER_PALANTIR = "palantir";
  public static final String FORMATTER_ECLIPSE = "eclipse";
  public static final String FORMATTER_NONE = "none";
  public static final String FORMATTER_COMMAND = "command";
  public static final String FORMATTER_COMMAND_FILE = "command-file";
  public static final String FORMAT_FILE_TOKEN = "%FILE%";
  public static final String FORMAT_MODULE_TOKEN = "%MODULE%";
  public static final String FORMAT_MVN_TOKEN = "%MVN%";
  // Stands for each module name in a sync script's per-module arguments.
  public static final String SYNC_MODULE_TOKEN = "{module}";

  // Spotless step options carried in FormatterSpec.options, named as in the Spotless POM config.
  // Sync fills unset ones from the step's own default<Option>() (a step without one does not
  // support the option).
  public static final String FORMAT_STYLE = "style";
  public static final String FORMAT_STYLE_GOOGLE = "GOOGLE";
  public static final String FORMAT_STYLE_AOSP = "AOSP";
  public static final String FORMAT_STYLE_PALANTIR = "PALANTIR";
  public static final String FORMAT_REFLOW_LONG_STRINGS = "reflowLongStrings";
  public static final String FORMAT_REORDER_IMPORTS = "reorderImports";
  public static final String FORMAT_JAVADOC = "formatJavadoc";
  public static final List<String> FORMAT_STEP_OPTIONS =
      List.of(FORMAT_STYLE, FORMAT_REFLOW_LONG_STRINGS, FORMAT_REORDER_IMPORTS, FORMAT_JAVADOC);
  // The Eclipse formatter profile (Spotless <file>), as an absolute path.
  public static final String FORMAT_FILE = "file";
  // A custom build of the formatter (Spotless <groupArtifact>), groupId:artifactId.
  public static final String FORMAT_GROUP_ARTIFACT = "groupArtifact";

  // The javac internals google-java-format and palantir reach into. A pinned formatter runs in the
  // unnamed module (an isolated classloader), so sync grants them to ALL-UNNAMED in jvm.args.
  public static final List<String> FORMATTER_JAVAC_GRANTS =
      Stream.of("api", "code", "file", "parser", "tree", "util")
          .map("--add-exports=jdk.compiler/com.sun.tools.javac.%s=ALL-UNNAMED"::formatted)
          .toList();

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
}
