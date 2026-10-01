package io.github.aglibs.lathe.openjdk;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.AnalysisMode;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Emits the OpenJDK-native {@code .lathe/}: per-module compiled-deps params + stamps, {@code
 * workspace.json}, and {@code java-home} (the exploded JDK = the read-only base). No bytecode
 * mirror — dependencies are referenced from the build's exploded {@code modules/}, never copied.
 */
public final class OpenJdkSync {

  private static final String SOURCE_TREE = "classes";

  private OpenJdkSync() {}

  /** Discovers every built module under {@code buildDir/jdk/modules} and syncs it. */
  public static void sync(
      final Path buildDir,
      final Path workspaceRoot,
      final String serverVersion,
      final Consumer<String> log)
      throws IOException {
    final Path explodedJdk = buildDir.resolve("jdk");
    final Path modulesDir = explodedJdk.resolve("modules");
    if (!Files.isDirectory(modulesDir)) {
      throw new IOException("no built modules at %s — build the JDK first".formatted(modulesDir));
    }

    int synced = 0;
    try (Stream<Path> dirs = Files.list(modulesDir)) {
      for (final Path moduleOut : dirs.filter(Files::isDirectory).sorted().toList()) {
        final var module = moduleOut.getFileName().toString();
        final Path cmdline = moduleOut.resolve("_the.%s_batch.cmdline".formatted(module));
        if (!Files.isRegularFile(cmdline)) {
          log.accept("[sync] %s skipped — not compiled (no .cmdline)".formatted(module));
          continue;
        }
        syncModule(module, moduleOut, cmdline, workspaceRoot);
        synced++;
      }
    }

    writeWorkspace(workspaceRoot, serverVersion);
    writeJavaHome(workspaceRoot, explodedJdk);
    final int count = synced;
    log.accept("[sync] %d module(s) -> %s".formatted(count, latheDir(workspaceRoot)));
  }

  private static void syncModule(
      final String module, final Path moduleOut, final Path cmdlineFile, final Path workspaceRoot)
      throws IOException {
    final CmdlineReader.Parsed parsed = CmdlineReader.read(Files.readString(cmdlineFile), module);
    final List<Path> sourceRoots = existingRoots(parsed.sourceRootPatterns());
    final var config =
        new ModuleConfigData(
            SOURCE_TREE,
            moduleOut.toString(),
            null,
            sourceRoots.stream().map(Path::toString).toList(),
            List.of(),
            List.of(),
            List.of(),
            "",
            parsed.encoding(),
            false,
            false,
            null,
            parsed.compilerArgs(),
            AnalysisMode.MODULE_SYSTEM);

    final Path latheModuleDir = latheDir(workspaceRoot).resolve(module);
    Files.createDirectories(latheModuleDir);
    Json.write(config, latheModuleDir.resolve(LatheLayout.paramsFileName(SOURCE_TREE)));
    CompiledStamps.writeAll(latheModuleDir, SOURCE_TREE, stamps(moduleOut, module, sourceRoots));
  }

  private static List<Path> existingRoots(final List<String> patterns) {
    return patterns.stream().map(Path::of).filter(Files::isDirectory).toList();
  }

  // filelist -> {sourceRoot-relative path -> mtime}, keyed as LatheCompiler.writeCompiledStamps
  // does.
  private static Map<String, Long> stamps(
      final Path moduleOut, final String module, final List<Path> sourceRoots) throws IOException {
    final Path filelist = moduleOut.resolve("_the.%s_batch.filelist".formatted(module));
    if (!Files.isRegularFile(filelist)) {
      return Map.of();
    }

    final var stamps = new HashMap<String, Long>();
    for (final String line : Files.readAllLines(filelist)) {
      if (line.isBlank()) {
        continue;
      }

      final Path source = Path.of(line.trim());
      final Optional<Path> root = rootOf(source, sourceRoots);
      if (root.isPresent()) {
        final long mtime = Files.getLastModifiedTime(source).toMillis();
        stamps.put(root.get().relativize(source).toString(), mtime);
      }
    }
    return stamps;
  }

  private static Path latheDir(final Path workspaceRoot) {
    return workspaceRoot.resolve(LatheLayout.LATHE_DIR);
  }

  private static Optional<Path> rootOf(final Path source, final List<Path> roots) {
    return roots.stream()
        .filter(source::startsWith)
        .max(Comparator.comparingInt(Path::getNameCount));
  }

  private static void writeWorkspace(final Path workspaceRoot, final String serverVersion)
      throws IOException {
    final var manifest =
        new WorkspaceManifestData(
            LatheLayout.SCHEMA_VERSION,
            workspaceRoot.toString(),
            serverVersion,
            List.of(),
            null,
            List.of(),
            List.of(),
            List.of());
    Json.write(manifest, latheDir(workspaceRoot).resolve(LatheLayout.WORKSPACE_JSON));
  }

  private static void writeJavaHome(final Path workspaceRoot, final Path explodedJdk)
      throws IOException {
    Files.writeString(
        latheDir(workspaceRoot).resolve(LatheLayout.JAVA_HOME_FILE),
        explodedJdk + System.lineSeparator());
  }
}
