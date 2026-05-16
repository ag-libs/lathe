package io.github.aglibs.lathe.core.schema;

import java.util.List;
import java.util.Objects;

public record ModuleConfigData(
    String sourceTree,
    String outputDir,
    String generatedSourcesDir,
    List<String> sourceRoots,
    List<String> classpath,
    List<String> modulepath,
    List<String> processorPath,
    String release,
    String encoding,
    boolean parameters,
    boolean enablePreview,
    String proc,
    List<String> compilerArgs) {

  public ModuleConfigData {
    encoding = Objects.requireNonNullElse(encoding, "UTF-8");
    compilerArgs = Objects.requireNonNullElse(compilerArgs, List.of());
  }
}
