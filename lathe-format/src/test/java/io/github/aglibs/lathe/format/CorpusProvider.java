package io.github.aglibs.lathe.format;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

// Enumerates the vendored GJF golden pairs; gates fixtures needing a newer JDK, like GJF itself.
final class CorpusProvider {

  private CorpusProvider() {}

  private static final Map<String, Integer> MIN_FEATURE =
      Map.ofEntries(
          Map.entry("SwitchGuardClause", 21),
          Map.entry("SwitchRecord", 21),
          Map.entry("SwitchDouble", 21),
          Map.entry("SwitchUnderscore", 21),
          Map.entry("I880", 21),
          Map.entry("Unnamed", 21),
          Map.entry("I981", 21),
          Map.entry("I1020", 21),
          Map.entry("I1037", 21),
          Map.entry("ModuleImport", 25),
          Map.entry("InstanceMain", 25));

  static Stream<Arguments> goldenCases() throws Exception {
    final Path root = Path.of(CorpusProvider.class.getResource("/golden").toURI());
    final int feature = Runtime.version().feature();
    final List<Arguments> cases = new ArrayList<>();
    try (Stream<Path> files = Files.list(root)) {
      for (final Path input : files.filter(CorpusProvider::isInput).sorted().toList()) {
        final String name = baseName(input);
        if (feature < MIN_FEATURE.getOrDefault(name, Integer.MIN_VALUE)) {
          continue;
        }

        final String source = Files.readString(input);
        final String expected = Files.readString(root.resolve(name + ".output"));
        cases.add(Arguments.of(name, source, expected));
      }
    }

    return cases.stream();
  }

  private static boolean isInput(final Path path) {
    return path.getFileName().toString().endsWith(".input");
  }

  private static String baseName(final Path input) {
    final String fileName = input.getFileName().toString();
    return fileName.substring(0, fileName.length() - ".input".length());
  }
}
