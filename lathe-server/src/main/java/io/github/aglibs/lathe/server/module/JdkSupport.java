package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

// Whether the JDK the server runs on can check a module as its build does (WS-46). javac rejects a
// --release, -source or -target newer than itself, and cannot read the class files of a newer JDK
// the build compiled against (--system), so a module needing either is left out rather than
// compiled into an exception or into errors the build does not report. A module with --release at
// or below the server's JDK is checked: --release gives javac exactly that Java version's API, from
// its own JDK, whichever JDK the build ran on.
public final class JdkSupport {

  private static final Logger LOG = Logger.getLogger(JdkSupport.class.getName());
  private static final Set<String> RELEASE_FLAGS = Set.of("--release");
  private static final Set<String> LEVEL_FLAGS =
      Set.of("-source", "--source", "-target", "--target");
  private static final Set<String> SYSTEM_FLAGS = Set.of("--system");
  private static final String LEGACY_LEVEL_PREFIX = "1.";

  private JdkSupport() {}

  // A module the server cannot check: the JDK it needs and why, in words that complete "module x".
  public record Mismatch(ModuleSourceConfig module, int needed, String why) {

    public Mismatch {
      ValidCheck.check()
          .notNull(module, "module")
          .isPositive(needed, "needed")
          .notNull(why, "why")
          .validate();
    }
  }

  // Empty when a server on serverFeature can check the module as its build does.
  public static Optional<Mismatch> check(final ModuleSourceConfig config, final int serverFeature) {
    final List<String> args = config.compilerArgs();
    final int release = Math.max(level(config.release()), highestLevel(args, RELEASE_FLAGS));
    if (release > serverFeature) {
      return Optional.of(new Mismatch(config, release, "is built for Java %d".formatted(release)));
    }

    if (release > 0) {
      return Optional.empty();
    }

    final int level = highestLevel(args, LEVEL_FLAGS);
    if (level > serverFeature) {
      return Optional.of(new Mismatch(config, level, "is built for Java %d".formatted(level)));
    }

    return values(args, SYSTEM_FLAGS).stream()
        .map(JdkSupport::systemFeature)
        .filter(feature -> feature > serverFeature)
        .findFirst()
        .map(feature -> new Mismatch(config, feature, "was built with JDK %d".formatted(feature)));
  }

  // What an open file of such a module says in place of its diagnostics: why, and the fix.
  public static String fileMessage(final String module, final Mismatch mismatch) {
    return "Lathe runs on JDK %d, but this file's module %s %s, so Lathe cannot check it as the build does. %s"
        .formatted(Runtime.version().feature(), module, mismatch.why(), fix(mismatch.needed()));
  }

  // What the user is told once a load finds such modules: the first by name, how many more, and
  // the newest JDK any of them needs.
  public static String workspaceMessage(final List<String> modules, final int needed) {
    final String named =
        modules.size() == 1
            ? "module %s needs".formatted(modules.get(0))
            : "modules %s and %d more need".formatted(modules.get(0), modules.size() - 1);
    return "Lathe runs on JDK %d, but %s JDK %d to be checked as the build does. %s"
        .formatted(Runtime.version().feature(), named, needed, fix(needed));
  }

  // Why a rename is refused: a module left out may use the symbol, and its uses would stay.
  public static String renameRefusal(
      final String symbol, final String module, final Mismatch mismatch) {
    return "`%s` cannot be renamed: module %s may use it, but it %s, so Lathe on JDK %d cannot check it. %s"
        .formatted(
            symbol, module, mismatch.why(), Runtime.version().feature(), fix(mismatch.needed()));
  }

  private static String fix(final int needed) {
    return "Run Lathe on JDK %d or newer: set LATHE_JAVA_HOME to it, or unset it so Lathe runs on the build's JDK."
        .formatted(needed);
  }

  // Whether the module compiles with --release: its API then comes from the server's own JDK.
  public static boolean released(final ModuleSourceConfig config) {
    return Math.max(level(config.release()), highestLevel(config.compilerArgs(), RELEASE_FLAGS))
        > 0;
  }

  // The feature version a JDK's release file names (JAVA_VERSION="25.0.1" is 25); 0 when the file
  // is missing or names no version Runtime.Version parses, and the JDK is then not compared.
  public static int jdkFeature(final Path jdk) {
    final var release = new Properties();
    try (final var reader =
        Files.newBufferedReader(
            jdk.resolve(LatheLayout.JDK_RELEASE_FILE), StandardCharsets.UTF_8)) {
      release.load(reader);
      final String version =
          release.getProperty(LatheLayout.JDK_RELEASE_VERSION_KEY, "").replace("\"", "");
      return Runtime.Version.parse(version).feature();
    } catch (final IOException | IllegalArgumentException e) {
      LOG.fine(() -> "[jdk] %s release version unreadable; not compared".formatted(jdk));
      return 0;
    }
  }

  // A --system value is the build's text: one no path can hold is a JDK not compared, not a load
  // that fails for every module.
  private static int systemFeature(final String home) {
    try {
      return jdkFeature(Path.of(home));
    } catch (final InvalidPathException e) {
      LOG.fine(() -> "[jdk] %s not a path; not compared".formatted(home));
      return 0;
    }
  }

  private static int highestLevel(final List<String> args, final Set<String> flags) {
    return values(args, flags).stream().mapToInt(JdkSupport::level).max().orElse(0);
  }

  // A language level as javac takes it ("25", or "1.8" for 8); 0 when absent or unreadable.
  private static int level(final String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }

    final String trimmed = value.trim();
    final String feature =
        trimmed.startsWith(LEGACY_LEVEL_PREFIX)
            ? trimmed.substring(LEGACY_LEVEL_PREFIX.length())
            : trimmed;
    try {
      return Integer.parseInt(feature);
    } catch (final NumberFormatException e) {
      return 0;
    }
  }

  // Each value the args give one of the flags, as `<flag> <value>` or `<flag>=<value>`.
  private static List<String> values(final List<String> args, final Set<String> flags) {
    final var found = new ArrayList<String>();
    for (int i = 0; i < args.size(); i++) {
      final String arg = args.get(i);
      final int eq = arg.indexOf('=');
      if (eq > 0 && flags.contains(arg.substring(0, eq))) {
        found.add(arg.substring(eq + 1));
      } else if (flags.contains(arg) && i + 1 < args.size()) {
        found.add(args.get(i + 1));
        i++;
      }
    }

    return List.copyOf(found);
  }
}
