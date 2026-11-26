package io.github.aglibs.lathe.server.format;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheFlags;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Pinned formatters for tests. The build copies their jars into target/, never onto the test class
// path, and the engine loads them from there, the way the server loads a project's.
public final class FormatterFixtures {

  private static final Path FORMATTERS = Path.of("target", "test-formatters");
  private static final Pattern SPRING_FORMATTER_JAR =
      Pattern.compile("spring-javaformat-formatter-(\\d[^-]*)\\.jar");

  // The google-java-format release the fixtures pin runs on JDK 21+ (it links to javac classes 17
  // lacks); on JDK 17 Lathe reports it as unsupported instead, as FormatterLoaderTest checks.
  public static final int GOOGLE_JAVA_FORMAT_MIN_JDK = 21;

  private FormatterFixtures() {}

  public static List<String> googleJavaFormatClasspath() {
    return classpath("google-java-format");
  }

  public static FormatEngine googleJavaFormat() {
    return googleJavaFormat(Map.of());
  }

  static FormatEngine googleJavaFormat(final Map<String, String> options) {
    return new GjfFormatEngine(
        GjfFormatEngine.Family.GOOGLE,
        LatheFlags.FORMAT_STYLE_GOOGLE,
        options,
        googleJavaFormatClasspath());
  }

  static FormatEngine palantirJavaFormat() {
    return new GjfFormatEngine(
        GjfFormatEngine.Family.PALANTIR,
        LatheFlags.FORMAT_STYLE_PALANTIR,
        Map.of(),
        classpath("palantir-java-format"));
  }

  // profile is an Eclipse formatter settings file, or empty for Eclipse's defaults.
  static FormatEngine eclipseJdt(final String profile) {
    return new EclipseFormatEngine(profile, classpath("eclipse-jdt"));
  }

  public static List<String> springJavaFormatClasspath() {
    return classpath("spring-javaformat");
  }

  // The spring-javaformat release the build copied (the root POM's spring-javaformat.version, which
  // a dependency bump moves), read from the formatter jar's name.
  public static String springJavaFormatRelease() {
    return springJavaFormatClasspath().stream()
        .map(jar -> SPRING_FORMATTER_JAR.matcher(Path.of(jar).getFileName().toString()))
        .filter(Matcher::matches)
        .map(matcher -> matcher.group(1))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("no spring-javaformat-formatter jar"));
  }

  // root is the workspace root the target's module is relative to.
  static FormatEngine springJavaFormat(final Path root) {
    return new SpringFormatEngine(springJavaFormatRelease(), springJavaFormatClasspath(), root);
  }

  private static List<String> classpath(final String formatter) {
    return IOUtil.unchecked(
            () ->
                FileUtil.walkFiles(
                    FORMATTERS.resolve(formatter), path -> path.toString().endsWith(".jar")))
        .stream()
        .map(path -> path.toAbsolutePath().toString())
        .sorted()
        .toList();
  }

  // A file to format, in the reactor root's module, with no build tool recorded.
  static FormatTarget target(final Path file) {
    return new FormatTarget(file, ".", null);
  }
}
