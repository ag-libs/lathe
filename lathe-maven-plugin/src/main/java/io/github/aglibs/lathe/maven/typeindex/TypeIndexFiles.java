package io.github.aglibs.lathe.maven.typeindex;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.typeindex.TypeIndexFile;
import io.github.aglibs.lathe.core.typeindex.TypeIndexOriginKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class TypeIndexFiles {

  static final String INDEX_JSON = "index.json";

  private TypeIndexFiles() {}

  static void write(final Path index, final TypeIndexFile file) throws IOException {
    Files.createDirectories(index.getParent());
    FileUtil.writeAtomically(index.getParent(), index, Json.toJson(file), false);
  }

  static Optional<TypeIndexFile> current(final Path index, final TypeIndexOriginKind kind) {
    if (!Files.exists(index)) {
      return Optional.empty();
    }

    try {
      final TypeIndexFile file = Json.read(index, TypeIndexFile.class);
      if (!LatheLayout.SCHEMA_VERSION.equals(file.schema()) || file.origin().kind() != kind) {
        return Optional.empty();
      }

      return Optional.of(file);
    } catch (final RuntimeException | IOException e) {
      return Optional.empty();
    }
  }
}
