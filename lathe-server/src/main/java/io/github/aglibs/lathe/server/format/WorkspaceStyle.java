package io.github.aglibs.lathe.server.format;

import com.google.gson.JsonParseException;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class WorkspaceStyle {

  private WorkspaceStyle() {}

  // Committed lathe-style.json wins over generated .lathe/style.json section by section: a team
  // can commit only an indent and keep the formatter sync derives, or declare a formatter whose
  // jars the sync resolved. Null when neither file exists.
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
        formatter(committed.formatter(), generated.formatter(), workspaceRoot),
        committed.indent() != null ? committed.indent() : generated.indent());
  }

  // A committed formatter without jars is a declaration the sync resolves: the generated one is its
  // completion when it agrees on everything the declaration says, and runs in its place.
  private static FormatterSpec formatter(
      final FormatterSpec committed, final FormatterSpec generated, final Path workspaceRoot) {
    if (committed == null) {
      return generated;
    }

    return resolves(committed, generated, workspaceRoot) ? generated : committed;
  }

  private static boolean resolves(
      final FormatterSpec declared, final FormatterSpec generated, final Path workspaceRoot) {
    if (generated == null || !declared.classpath().isEmpty() || generated.classpath().isEmpty()) {
      return false;
    }

    if (!declared.engine().equals(generated.engine())
        || !(declared.version().isBlank() || declared.version().equals(generated.version()))) {
      return false;
    }

    return declared.options().entrySet().stream()
        .allMatch(option -> sameOption(option, generated.options(), workspaceRoot));
  }

  // The sync makes the Eclipse profile absolute, from a root its build tool may have canonicalized;
  // the declaration names it from the workspace root.
  private static boolean sameOption(
      final Map.Entry<String, String> declared,
      final Map<String, String> generated,
      final Path workspaceRoot) {
    final String value = generated.get(declared.getKey());
    if (value == null) {
      return false;
    }

    if (LatheFlags.FORMAT_FILE.equals(declared.getKey())) {
      return sameFile(workspaceRoot.resolve(declared.getValue()), Path.of(value));
    }

    return declared.getValue().equals(value);
  }

  private static boolean sameFile(final Path declared, final Path generated) {
    try {
      return Files.isSameFile(declared, generated);
    } catch (final IOException e) {
      return false;
    }
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
