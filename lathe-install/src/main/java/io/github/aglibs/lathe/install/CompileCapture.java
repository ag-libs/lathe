package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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

  public static void mirror(final Path moduleDir, final ModuleConfigData config)
      throws IOException {
    FileUtil.replaceDir(Path.of(config.outputDir()), moduleDir.resolve(config.sourceTree()));
    mirrorGeneratedSources(moduleDir, config);
    writeStamps(moduleDir, config);
  }

  // To a scope-specific target, so the test compile does not overwrite the main compile's sources.
  private static void mirrorGeneratedSources(final Path moduleDir, final ModuleConfigData config)
      throws IOException {
    if (config.generatedSourcesDir() == null
        || !Files.isDirectory(Path.of(config.generatedSourcesDir()))) {
      return;
    }

    FileUtil.replaceDir(
        Path.of(config.generatedSourcesDir()),
        moduleDir.resolve(LatheLayout.generatedSourcesDir(config.sourceTree())));
  }

  // A stamp per source (root-relative path -> mtime), excluding the annotation-processor output:
  // the scan skips it too, and its files could collide with real sources on their key.
  private static void writeStamps(final Path moduleDir, final ModuleConfigData config)
      throws IOException {
    final Path genRoot =
        config.generatedSourcesDir() != null
            ? Path.of(config.generatedSourcesDir()).normalize()
            : null;
    final var stamps = new HashMap<Path, Long>();
    for (final String location : config.sourceRoots()) {
      final var root = Path.of(location);
      if (root.normalize().equals(genRoot) || !Files.isDirectory(root)) {
        continue;
      }

      try (final var walk = Files.walk(root)) {
        stamps.putAll(
            walk.filter(FileUtil::isJavaFile)
                .collect(Collectors.toMap(root::relativize, CompileCapture::mtime)));
      }
    }

    CompiledStamps.writeAll(moduleDir, config.sourceTree(), stamps);
  }

  private static long mtime(final Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (final IOException e) {
      return 0L;
    }
  }
}
