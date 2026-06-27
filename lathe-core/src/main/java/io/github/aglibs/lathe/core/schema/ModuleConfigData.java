package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;
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
    sourceRoots = sourceRoots != null ? List.copyOf(sourceRoots) : List.of();
    classpath = classpath != null ? List.copyOf(classpath) : List.of();
    modulepath = modulepath != null ? List.copyOf(modulepath) : List.of();
    processorPath = processorPath != null ? List.copyOf(processorPath) : List.of();
    compilerArgs = compilerArgs != null ? List.copyOf(compilerArgs) : List.of();
    ValidCheck.check().notNull(sourceTree, "sourceTree").notNull(outputDir, "outputDir").validate();
  }
}
