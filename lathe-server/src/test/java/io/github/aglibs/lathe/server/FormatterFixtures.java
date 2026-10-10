package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

// A pinned google-java-format for tests. The build copies its jars into target/, never onto the
// test class path, and the engine loads them from there, the way the server loads a project's.
final class FormatterFixtures {

  private static final Path GOOGLE_JAVA_FORMAT =
      Path.of("target", "test-formatters", "google-java-format");

  private FormatterFixtures() {}

  static List<String> googleJavaFormatClasspath() {
    return IOUtil.unchecked(
            () -> FileUtil.walkFiles(GOOGLE_JAVA_FORMAT, path -> path.toString().endsWith(".jar")))
        .stream()
        .map(path -> path.toAbsolutePath().toString())
        .sorted()
        .toList();
  }

  static GoogleFormatEngine googleJavaFormat() {
    return googleJavaFormat(Map.of());
  }

  static GoogleFormatEngine googleJavaFormat(final Map<String, String> options) {
    return new GoogleFormatEngine("GOOGLE", options, googleJavaFormatClasspath());
  }
}
