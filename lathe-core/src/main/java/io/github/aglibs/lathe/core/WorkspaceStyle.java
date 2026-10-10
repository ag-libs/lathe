package io.github.aglibs.lathe.core;

import com.google.gson.JsonParseException;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WorkspaceStyle {

  private WorkspaceStyle() {}

  // Committed lathe-style.json wins over generated .lathe/style.json section by section: a team
  // can commit only an indent and keep the formatter sync derives. Null when neither file exists.
  // Throws IOException on a malformed file so the caller can log and fall back.
  public static WorkspaceStyleData read(final Path workspaceRoot) throws IOException {
    final WorkspaceStyleData committed =
        parseIfExists(workspaceRoot.resolve(LatheLayout.STYLE_SHARED_FILE));
    final WorkspaceStyleData generated =
        parseIfExists(workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.STYLE_FILE));
    if (committed == null || generated == null) {
      return committed != null ? committed : generated;
    }

    return new WorkspaceStyleData(
        committed.formatter() != null ? committed.formatter() : generated.formatter(),
        committed.indent() != null ? committed.indent() : generated.indent());
  }

  private static WorkspaceStyleData parseIfExists(final Path file) throws IOException {
    return Files.exists(file) ? parse(file) : null;
  }

  private static WorkspaceStyleData parse(final Path file) throws IOException {
    try {
      return Json.read(file, WorkspaceStyleData.class);
    } catch (final JsonParseException e) {
      throw new IOException("malformed style file %s".formatted(file), e);
    }
  }
}
