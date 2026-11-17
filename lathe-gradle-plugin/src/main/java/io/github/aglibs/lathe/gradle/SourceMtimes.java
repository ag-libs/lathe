package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.install.CompileCapture;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

// The sources' mtimes just before a compile task, kept in the module's .lathe/ directory until the
// capture after it reads them: a source written while javac compiles then gets no stamp. Keyed by
// absolute path.
record SourceMtimes(Map<String, Long> byPath) {

  SourceMtimes {
    ValidCheck.check().notNull(byPath, "byPath").validate();
    byPath = Map.copyOf(byPath);
  }

  static String fileName(final String sourceTree) {
    return "source-mtimes-%s.json".formatted(sourceTree);
  }

  // Every .java file under the source roots, as the stamps walk them, filters left out.
  static void write(final Collection<Path> sourceRoots, final Path file) throws IOException {
    final var sources =
        sourceRoots.stream()
            .flatMap(
                root ->
                    IOUtil.unchecked(() -> FileUtil.walkFiles(root, FileUtil::isJavaFile)).stream())
            .toList();
    Files.createDirectories(file.getParent());
    Json.write(
        new SourceMtimes(
            CompileCapture.sourceMtimes(sources).entrySet().stream()
                .collect(
                    Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().toString(), Map.Entry::getValue))),
        file);
  }

  // The mtimes recorded before the compile, and the file gone. The snapshot task runs before every
  // compile task, up to date or not: no file means it did not run (left out with -x), so nothing
  // tells which sources javac read, and none is stamped. A file a crash damaged tells as little,
  // and must not fail the capture, which is best-effort.
  static Map<Path, Long> take(final Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return Map.of();
    }

    try {
      return Json.read(file, SourceMtimes.class).byPath().entrySet().stream()
          .collect(
              Collectors.toUnmodifiableMap(entry -> Path.of(entry.getKey()), Map.Entry::getValue));
    } catch (final IOException e) {
      return Map.of();
    } finally {
      Files.delete(file);
    }
  }
}
