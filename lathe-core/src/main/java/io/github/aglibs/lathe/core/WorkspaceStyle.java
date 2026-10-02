package io.github.aglibs.lathe.core;

import com.google.gson.JsonParseException;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WorkspaceStyle {

  private WorkspaceStyle() {}

  // Committed lathe-style.json wins over generated .lathe/style.json; null when neither exists.
  // Throws IOException on a malformed file so the caller can log and fall back.
  public static WorkspaceStyleData read(final Path workspaceRoot) throws IOException {
    final Path committed = workspaceRoot.resolve(LatheLayout.STYLE_SHARED_FILE);
    if (Files.exists(committed)) {
      return parse(committed);
    }

    final Path generated =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.STYLE_FILE);
    if (Files.exists(generated)) {
      return parse(generated);
    }

    return null;
  }

  private static WorkspaceStyleData parse(final Path file) throws IOException {
    try {
      return Json.read(file, WorkspaceStyleData.class);
    } catch (final JsonParseException e) {
      throw new IOException("malformed style file %s".formatted(file), e);
    }
  }
}
