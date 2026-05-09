package io.github.aglibs.lathe.compiler;

import io.github.aglibs.lathe.core.ParamStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.codehaus.plexus.compiler.CompilerConfiguration;

final class ParamsWriter {

  private ParamsWriter() {}

  static void write(final CompilerConfiguration config, final Path latheModuleDir)
      throws IOException {
    final var store = new ParamStore();

    final var outputBaseName = Path.of(config.getOutputLocation()).getFileName().toString();
    store.set("sourceTree", outputBaseName);
    store.set("outputDir", config.getOutputLocation());
    final var genSources = config.getGeneratedSourcesDirectory();
    if (genSources != null) {
      store.set("generatedSourcesDir", genSources.toString());
    }

    store.putListIfPresent("sourceRoots", config.getSourceLocations());
    store.putListIfPresent("classpath", config.getClasspathEntries());
    store.putListIfPresent("modulepath", config.getModulepathEntries());
    store.putListIfPresent("processorPath", config.getProcessorPathEntries());

    final var release = config.getReleaseVersion();
    if (release != null && !release.isBlank()) {
      store.set("release", release);
    }
    store.set(
        "encoding", config.getSourceEncoding() != null ? config.getSourceEncoding() : "UTF-8");
    store.set("parameters", String.valueOf(config.isParameters()));
    store.set("enablePreview", String.valueOf(config.isEnablePreview()));
    final var proc = config.getProc();
    if (proc != null && !proc.isBlank()) {
      store.set("proc", proc);
    }

    final var processors = config.getAnnotationProcessors();
    if (processors != null) {
      store.putList("annotationProcessors", Arrays.asList(processors));
    }

    final var compilerArgs = config.getCustomCompilerArgumentsAsMap();
    if (compilerArgs != null && !compilerArgs.isEmpty()) {
      final var argList =
          compilerArgs.entrySet().stream()
              .flatMap(
                  e -> {
                    final var val = e.getValue();
                    return (val != null && !val.isBlank())
                        ? Stream.of(e.getKey(), val)
                        : Stream.of(e.getKey());
                  })
              .toList();
      store.putList("compilerArgs", argList);
    }

    store.store(latheModuleDir.resolve("lsp-params-" + outputBaseName + ".properties"));
  }
}
