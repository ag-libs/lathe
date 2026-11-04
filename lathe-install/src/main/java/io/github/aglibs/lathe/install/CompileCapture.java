package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.core.schema.SourceStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// What one compile leaves in .lathe/<module>: its lsp-params, and, after a successful compile, the
// bytecode and generated-sources mirrors and the compile stamps. The build tool decides when each
// runs: a failed compile keeps the last good mirror, so its sources stay stale for the server.
public final class CompileCapture {

  private CompileCapture() {}

  public static void writeParams(final Path moduleDir, final ModuleConfigData config)
      throws IOException {
    Json.write(config, moduleDir.resolve(LatheLayout.paramsFileName(config.sourceTree())));
  }

  // resourceDirs join the classes in the bytecode mirror: Gradle processes resources into their
  // own directory, where Maven puts them beside the classes, and a replay that stands the mirror
  // in for a project's jar needs both.
  public static void mirror(
      final Path moduleDir, final ModuleConfigData config, final List<Path> resourceDirs)
      throws IOException {
    final Path classesMirror = moduleDir.resolve(config.sourceTree());
    FileUtil.replaceDir(Path.of(config.outputDir()), classesMirror);
    for (final Path resources : resourceDirs) {
      if (Files.isDirectory(resources)) {
        FileUtil.copyDir(resources, classesMirror);
      }
    }

    mirrorGeneratedSources(moduleDir, config);
    writeStamps(moduleDir, config);
  }

  // To a scope-specific target, so the test compile does not overwrite the main compile's sources.
  // A compile without processor output removes the mirror, so an earlier processor's types do not
  // linger in the editor.
  private static void mirrorGeneratedSources(final Path moduleDir, final ModuleConfigData config)
      throws IOException {
    final Path mirror = moduleDir.resolve(LatheLayout.generatedSourcesDir(config.sourceTree()));
    if (config.generatedSourcesDir() == null
        || !Files.isDirectory(Path.of(config.generatedSourcesDir()))) {
      FileUtil.deleteDir(mirror);
      return;
    }

    FileUtil.replaceDir(Path.of(config.generatedSourcesDir()), mirror);
  }

  // Each source's stamp (its mtime and the content compiled) and, from the same hashes, what the
  // build compiled.
  private static void writeStamps(final Path moduleDir, final ModuleConfigData config)
      throws IOException {
    final Map<Path, Path> sources = CompiledStamps.sourcesByKey(config);
    final Map<Path, ContentFingerprint> built = CompiledStamps.builtOf(sources);
    final Map<Path, SourceStamp> stamps =
        sources.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    entry -> new SourceStamp(mtime(entry.getValue()), built.get(entry.getKey()))));
    CompiledStamps.writeAll(moduleDir, config.sourceTree(), stamps, built);
  }

  private static long mtime(final Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (final IOException e) {
      return 0L;
    }
  }
}
