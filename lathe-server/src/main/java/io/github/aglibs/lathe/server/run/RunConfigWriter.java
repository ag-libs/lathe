package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.schema.RunKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Upserts a named config under {@code configs} in the machine-local {@code .lathe/run.json},
 * preserving {@code defaults} and sibling configs. The only place Lathe writes a run-config file,
 * and never the committed {@code lathe-run.json}; the write is atomic (temp + move).
 */
public final class RunConfigWriter {

  private final Path workspaceRoot;

  public RunConfigWriter(final Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
  }

  /** The saved config's name (server-derived when the request omitted one) and the written path. */
  public record Saved(String name, String path) {}

  public Saved save(
      final String requestedName,
      final String moduleRel,
      final RunKind kind,
      final String mainClass,
      final List<TestSelection> selectors,
      final boolean overwrite)
      throws IOException {
    final String name = resolveName(requestedName, kind, mainClass, selectors);
    final Path latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    final Path file = latheDir.resolve(LatheLayout.RUN_CONFIG_LOCAL_FILE);

    final RunConfigFile existing = readOrEmpty(file);
    if (existing.configs().containsKey(name) && !overwrite) {
      throw new IllegalArgumentException(
          "config '%s' already exists — use :LatheRunSave! to overwrite".formatted(name));
    }

    final var configs = new LinkedHashMap<>(existing.configs());
    configs.put(name, entryFor(moduleRel, kind, mainClass, selectors));
    Files.createDirectories(latheDir);
    FileUtil.writeAtomically(
        latheDir, file, Json.toJson(new RunConfigFile(existing.defaults(), configs)), false);
    return new Saved(name, file.toString());
  }

  private static RunConfigEntry entryFor(
      final String moduleRel,
      final RunKind kind,
      final String mainClass,
      final List<TestSelection> selectors) {
    if (kind == RunKind.MAIN) {
      return RunConfigEntry.mainTarget(moduleRel, mainClass);
    }

    return RunConfigEntry.testTarget(moduleRel, selectors.stream().map(RunSelector::from).toList());
  }

  private static RunConfigFile readOrEmpty(final Path file) throws IOException {
    if (!Files.exists(file)) {
      return RunConfigFile.empty();
    }

    final RunConfigFile parsed = Json.read(file, RunConfigFile.class);
    return parsed != null ? parsed : RunConfigFile.empty();
  }

  private static String resolveName(
      final String requestedName,
      final RunKind kind,
      final String mainClass,
      final List<TestSelection> selectors) {
    if (requestedName != null && !requestedName.isBlank()) {
      return requestedName;
    }

    if (kind == RunKind.MAIN) {
      return simpleName(mainClass);
    }

    if (selectors.isEmpty()) {
      throw new IllegalArgumentException("cannot derive a config name: no test selector");
    }

    return derivedTestName(selectors.getFirst());
  }

  private static String derivedTestName(final TestSelection selection) {
    return switch (selection.kind()) {
      case METHOD -> methodName(selection.value());
      case CLASS, PACKAGE, MODULE -> simpleName(selection.value());
    };
  }

  // "com.example.SmokeTest#testBar(..)" -> "SmokeTest.testBar"
  private static String methodName(final String value) {
    final int hash = value.indexOf('#');
    if (hash < 0) {
      return simpleName(value);
    }

    final String method = value.substring(hash + 1).split("\\(", 2)[0];
    return "%s.%s".formatted(simpleName(value.substring(0, hash)), method);
  }

  private static String simpleName(final String dotted) {
    final int dot = dotted.lastIndexOf('.');
    return dot < 0 ? dotted : dotted.substring(dot + 1);
  }
}
