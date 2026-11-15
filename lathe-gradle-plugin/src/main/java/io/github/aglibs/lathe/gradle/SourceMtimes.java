package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.install.CompileCapture;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

// The sources' mtimes just before a compile task, kept in a file until the capture after it reads
// them: a source written while javac compiles then gets no stamp. Keyed by absolute path.
record SourceMtimes(Map<String, Long> byPath) {

  // In the compile task's temporary directory: it lives from just before that task to the capture.
  static final String FILE_NAME = "lathe-source-mtimes.json";

  SourceMtimes {
    ValidCheck.check().notNull(byPath, "byPath").validate();
    byPath = Map.copyOf(byPath);
  }

  static void write(final Collection<Path> sources, final Path file) throws IOException {
    Files.createDirectories(file.getParent());
    Json.write(
        new SourceMtimes(
            CompileCapture.sourceMtimes(sources).entrySet().stream()
                .collect(
                    Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().toString(), Map.Entry::getValue))),
        file);
  }

  // The mtimes the compile recorded, and the file gone; empty when it did not run (up to date, or
  // from the build cache), so nothing was compiled that could race.
  static Optional<Map<Path, Long>> take(final Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }

    final SourceMtimes recorded = Json.read(file, SourceMtimes.class);
    Files.delete(file);
    return Optional.of(
        recorded.byPath().entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    entry -> Path.of(entry.getKey()), Map.Entry::getValue)));
  }
}
