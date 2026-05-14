package io.github.aglibs.lathe.compiler;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.maven.ModuleConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.codehaus.plexus.compiler.CompilerConfiguration;

final class ParamsWriter {

  private ParamsWriter() {}

  static void write(final CompilerConfiguration config, final Path latheModuleDir)
      throws IOException {
    final var outputBaseName = Path.of(config.getOutputLocation()).getFileName().toString();
    final var genSources = config.getGeneratedSourcesDirectory();
    final var moduleConfig =
        new ModuleConfig(
            outputBaseName,
            config.getOutputLocation(),
            genSources != null ? genSources.toString() : null,
            nullToEmpty(config.getSourceLocations()),
            nullToEmpty(config.getClasspathEntries()),
            nullToEmpty(config.getModulepathEntries()),
            nullToEmpty(config.getProcessorPathEntries()),
            config.getReleaseVersion(),
            config.getSourceEncoding() != null ? config.getSourceEncoding() : "UTF-8",
            config.isParameters(),
            config.isEnablePreview(),
            config.getProc(),
            compilerArgs(config));
    Json.write(moduleConfig, latheModuleDir.resolve("lsp-params-" + outputBaseName + ".json"));
  }

  private static List<String> compilerArgs(final CompilerConfiguration config) {
    final var map = config.getCustomCompilerArgumentsAsMap();
    if (map == null || map.isEmpty()) {
      return List.of();
    }
    return map.entrySet().stream()
        .flatMap(
            e -> {
              final var val = e.getValue();
              return (val != null && !val.isBlank())
                  ? Stream.of(e.getKey(), val)
                  : Stream.of(e.getKey());
            })
        .toList();
  }

  private static List<String> nullToEmpty(final List<String> list) {
    return list != null ? list : List.of();
  }
}
