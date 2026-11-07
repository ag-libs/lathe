package io.github.aglibs.lathe.server.format;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheFlags;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

// Pinned formatters for tests. The build copies their jars into target/, never onto the test class
// path, and the engine loads them from there, the way the server loads a project's.
public final class FormatterFixtures {

  private static final Path FORMATTERS = Path.of("target", "test-formatters");

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
