package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.IndentSpec;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// The formatters Lathe runs in-process, completed and resolved as Spotless runs them: the version
// and options a project leaves out are its Spotless step's own defaults, read from the step classes
// in a classloader the front-end supplies (the build's Spotless, else the release Lathe pins).
public final class FormatterSteps {

  public static final String SPOTLESS_GROUP = "com.diffplug.spotless";
  // spotless-lib holds the google/palantir steps, spotless-lib-extra the eclipse step and its
  // lockfiles; neither has dependencies of its own that the defaults need.
  public static final List<String> SPOTLESS_LIBRARIES =
      List.of("spotless-lib", "spotless-lib-extra");

  private static final Step GOOGLE_STEP =
      new Step(
          "com.diffplug.spotless.java.GoogleJavaFormatStep",
          "com.google.googlejavaformat:google-java-format",
          true,
          "",
          "com/google/googlejavaformat/java/Formatter.class");
  private static final Map<String, Step> STEPS =
      Map.of(
          LatheFlags.FORMATTER_GOOGLE,
          GOOGLE_STEP,
          LatheFlags.FORMATTER_AOSP,
          GOOGLE_STEP,
          LatheFlags.FORMATTER_PALANTIR,
          new Step(
              "com.diffplug.spotless.java.PalantirJavaFormatStep",
              "com.palantir.javaformat:palantir-java-format",
              true,
              "",
              "com/palantir/javaformat/java/Formatter.class"),
          LatheFlags.FORMATTER_ECLIPSE,
          new Step(
              "com.diffplug.spotless.extra.java.EclipseJdtFormatterStep",
              "org.eclipse.jdt:org.eclipse.jdt.core",
              false,
              "com/diffplug/spotless/extra/eclipse_jdt_formatter/v%s.lockfile",
              "org/eclipse/jdt/core/formatter/CodeFormatter.class"));
  private static final String INDENT_GOOGLE = "google";
  private static final IndentSpec EDITORCONFIG_INDENT = new IndentSpec("editorconfig", 0, 0);
  private static final String PINNED_SPOTLESS_RESOURCE = "spotless.properties";
  private static final String PINNED_SPOTLESS_KEY = "spotless-lib.version";
  // A class file's major version is its Java release plus 44 (Java 17 is 61).
  private static final int CLASS_MAJOR_OFFSET = 44;
  private static final int CLASS_HEADER_BYTES = 8;

  private FormatterSteps() {}

  // Whether the engine runs from the formatter's own jars, which a sync must resolve.
  public static boolean pinned(final String engine) {
    return STEPS.containsKey(engine);
  }

  // The Spotless libraries Lathe pins, for a build without Spotless of its own: groupId:artifactId:
  // version, each resolved without its dependencies.
  public static List<String> pinnedSpotless() {
    final String version = pinnedSpotlessVersion();
    return SPOTLESS_LIBRARIES.stream()
        .map(library -> "%s:%s:%s".formatted(SPOTLESS_GROUP, library, version))
        .toList();
  }

  // Spotless's libraries in a classloader of their own, so they never meet the build's classes.
  public static URLClassLoader spotlessLoader(final List<Path> libraries) {
    return new URLClassLoader(
        libraries.stream()
            .map(library -> IOUtil.unchecked(() -> library.toUri().toURL()))
            .toArray(URL[]::new),
        ClassLoader.getPlatformClassLoader());
  }

  // The formatter section of a lathe-style.json, when it declares one.
  public static Optional<FormatterSpec> declared(final String styleJson) {
    try {
      final WorkspaceStyleData style = Json.fromJson(styleJson, WorkspaceStyleData.class);
      return Optional.ofNullable(style).map(WorkspaceStyleData::formatter);
    } catch (final RuntimeException e) {
      throw new SyncException(
          "malformed %s: %s".formatted(LatheLayout.STYLE_SHARED_FILE, e.getMessage()), e);
    }
  }

  // Fills what the project left out with the Spotless step's own defaults (defaultVersion(),
  // defaultGroupArtifact(), default<Option>()); declared values win. An aosp engine is google's
  // step in AOSP style.
  public static FormatterSpec withDefaults(final FormatterSpec spec, final ClassLoader spotless)
      throws ReflectiveOperationException {
    final Step step = step(spec);
    final Class<?> type = spotless.loadClass(step.type());
    final var options = new HashMap<>(spec.options());
    if (LatheFlags.FORMATTER_AOSP.equals(spec.engine())) {
      options.putIfAbsent(LatheFlags.FORMAT_STYLE, LatheFlags.FORMAT_STYLE_AOSP);
    }

    options.putIfAbsent(
        LatheFlags.FORMAT_GROUP_ARTIFACT,
        stepDefault(type, LatheFlags.FORMAT_GROUP_ARTIFACT).orElse(step.groupArtifact()));
    LatheFlags.FORMAT_STEP_OPTIONS.forEach(
        option -> stepDefault(type, option).ifPresent(value -> options.putIfAbsent(option, value)));
    final String version = spec.version().isBlank() ? call(type, "defaultVersion") : spec.version();
    return new FormatterSpec(spec.engine(), spec.command(), version, options, spec.classpath());
  }

  // The jars a completed spec runs from: Spotless's lockfile for the release, each jar alone, or
  // else the formatter with its runtime dependencies.
  public static Jars jars(final FormatterSpec completed, final ClassLoader spotless)
      throws IOException {
    final Step step = step(completed);
    if (step.lockfile().isEmpty()) {
      return new Jars(
          List.of(
              "%s:%s"
                  .formatted(
                      completed.options().get(LatheFlags.FORMAT_GROUP_ARTIFACT),
                      completed.version())),
          true);
    }

    final String lockfile = step.lockfile().formatted(completed.version());
    try (InputStream in = spotless.getResourceAsStream(lockfile)) {
      if (in == null) {
        throw new SyncException(
            "Spotless has no lockfile %s; only lockfile-pinned Eclipse releases are supported"
                .formatted(lockfile),
            null);
      }

      return new Jars(
          new String(in.readAllBytes(), StandardCharsets.UTF_8)
              .lines()
              .map(String::trim)
              .filter(line -> !line.isEmpty() && !line.startsWith("#"))
              .toList(),
          false);
    }
  }

  // The Eclipse profile as an absolute path, resolved against basedir as Spotless resolves it;
  // empty when it is not a local file. Any other spec is returned as it is.
  public static Optional<FormatterSpec> withLocalProfile(
      final FormatterSpec spec, final Path basedir) {
    final String profile = spec.options().get(LatheFlags.FORMAT_FILE);
    if (profile == null) {
      return Optional.of(spec);
    }

    final Path file = basedir.resolve(profile).normalize();
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }

    final var options = new HashMap<>(spec.options());
    options.put(LatheFlags.FORMAT_FILE, file.toString());
    return Optional.of(
        new FormatterSpec(
            spec.engine(), spec.command(), spec.version(), options, spec.classpath()));
  }

  // Completes the spec and resolves its jars through the build tool, for a front-end that
  // resolves on demand.
  public static FormatterSpec resolve(
      final FormatterSpec spec, final ClassLoader spotless, final ArtifactResolver resolver)
      throws ReflectiveOperationException, IOException {
    final FormatterSpec completed = withDefaults(spec, spotless);
    final Jars jars = jars(completed, spotless);
    final List<String> classpath =
        jars.transitive()
            ? resolver.resolveTransitive(jars.coordinates().get(0)).stream()
                .map(Path::toString)
                .toList()
            : jars.coordinates().stream()
                .map(coordinates -> resolver.resolve(coordinates).toString())
                .toList();
    return withClasspath(completed, classpath);
  }

  public static FormatterSpec withClasspath(
      final FormatterSpec spec, final List<String> classpath) {
    return new FormatterSpec(
        spec.engine(), spec.command(), spec.version(), spec.options(), classpath);
  }

  // The Java release a resolved formatter needs: its main class's class-file version, read from the
  // jar that holds it; empty when no jar on its classpath does, or it cannot be read.
  public static OptionalInt requiredJava(final FormatterSpec resolved) {
    final Step step = STEPS.get(resolved.engine());
    if (step == null) {
      return OptionalInt.empty();
    }

    for (final String jar : resolved.classpath()) {
      final OptionalInt release = classRelease(Path.of(jar), step.mainClass());
      if (release.isPresent()) {
        return release;
      }
    }

    return OptionalInt.empty();
  }

  private static OptionalInt classRelease(final Path jar, final String entry) {
    try (var zip = new ZipFile(jar.toFile())) {
      final ZipEntry found = zip.getEntry(entry);
      if (found == null) {
        return OptionalInt.empty();
      }

      try (InputStream in = zip.getInputStream(found)) {
        // A class file starts with the magic number, then the minor and major versions.
        final byte[] header = in.readNBytes(CLASS_HEADER_BYTES);
        if (header.length < CLASS_HEADER_BYTES) {
          return OptionalInt.empty();
        }

        final int major = ((header[6] & 0xff) << 8) | (header[7] & 0xff);
        return OptionalInt.of(major - CLASS_MAJOR_OFFSET);
      }
    } catch (final IOException e) {
      return OptionalInt.empty();
    }
  }

  public static List<String> javacGrants(final FormatterSpec spec) {
    final Step step = STEPS.get(spec.engine());
    return step != null && step.javacGrants() ? LatheFlags.FORMATTER_JAVAC_GRANTS : List.of();
  }

  // The as-you-type indentation the formatter implies: google-java-format's and palantir's GOOGLE
  // style indents by 2, their other styles by 4; any other formatter leaves it to .editorconfig.
  public static IndentSpec indent(final FormatterSpec spec) {
    if (!pinned(spec.engine()) || LatheFlags.FORMATTER_ECLIPSE.equals(spec.engine())) {
      return EDITORCONFIG_INDENT;
    }

    final String style =
        spec.options()
            .getOrDefault(LatheFlags.FORMAT_STYLE, defaultStyle(spec))
            .toUpperCase(Locale.ROOT);
    return LatheFlags.FORMAT_STYLE_GOOGLE.equals(style)
        ? new IndentSpec(INDENT_GOOGLE, 2, 4)
        : new IndentSpec(INDENT_GOOGLE, 4, 8);
  }

  private static String defaultStyle(final FormatterSpec spec) {
    return switch (spec.engine()) {
      case LatheFlags.FORMATTER_AOSP -> LatheFlags.FORMAT_STYLE_AOSP;
      case LatheFlags.FORMATTER_PALANTIR -> LatheFlags.FORMAT_STYLE_PALANTIR;
      default -> LatheFlags.FORMAT_STYLE_GOOGLE;
    };
  }

  private static Step step(final FormatterSpec spec) {
    final Step step = STEPS.get(spec.engine());
    if (step == null) {
      throw new IllegalArgumentException(
          "formatter %s runs no pinned jars".formatted(spec.engine()));
    }

    return step;
  }

  // A step that has no default for an option does not support it.
  private static Optional<String> stepDefault(final Class<?> step, final String option) {
    final String method =
        "default%s%s"
            .formatted(option.substring(0, 1).toUpperCase(Locale.ROOT), option.substring(1));
    try {
      return Optional.of(call(step, method));
    } catch (final ReflectiveOperationException e) {
      return Optional.empty();
    }
  }

  private static String call(final Class<?> step, final String method)
      throws ReflectiveOperationException {
    return String.valueOf(step.getMethod(method).invoke(null));
  }

  private static String pinnedSpotlessVersion() {
    final var props = new Properties();
    try (InputStream in = FormatterSteps.class.getResourceAsStream(PINNED_SPOTLESS_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("%s is missing".formatted(PINNED_SPOTLESS_RESOURCE));
      }

      props.load(in);
    } catch (final IOException e) {
      throw new IllegalStateException("%s is unreadable".formatted(PINNED_SPOTLESS_RESOURCE), e);
    }

    return props.getProperty(PINNED_SPOTLESS_KEY);
  }

  // The coordinates a formatter runs from, and whether each brings its runtime dependencies.
  public record Jars(List<String> coordinates, boolean transitive) {

    public Jars {
      ValidCheck.check().notEmpty(coordinates, "coordinates").validate();
      coordinates = List.copyOf(coordinates);
    }
  }

  // A Spotless step class (its defaults), the formatter's groupId:artifactId, whether the
  // formatter reaches into javac internals and so needs FORMATTER_JAVAC_GRANTS, the Spotless
  // lockfile (a resource pattern taking the version) pinning its jars, or empty to resolve the
  // artifact's runtime closure, and a class every release of the formatter has, whose class-file
  // version says the Java it needs.
  private record Step(
      String type, String groupArtifact, boolean javacGrants, String lockfile, String mainClass) {

    private Step {
      ValidCheck.check()
          .notBlank(type, "type")
          .notBlank(groupArtifact, "groupArtifact")
          .notNull(lockfile, "lockfile")
          .notBlank(mainClass, "mainClass")
          .validate();
    }
  }
}
