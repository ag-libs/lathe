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
    List<String> compilerArgs,
    AnalysisMode analysisMode,
    // True when outputDir is the build's own compiled output, read directly (OpenJDK modules and
    // build tools); false when Lathe mirrors it into .lathe/ (Maven/Gradle).
    boolean externalOutput) {

  public ModuleConfigData {
    encoding = Objects.requireNonNullElse(encoding, "UTF-8");
    analysisMode = Objects.requireNonNullElse(analysisMode, AnalysisMode.CLASSPATH);
    sourceRoots = sourceRoots != null ? List.copyOf(sourceRoots) : List.of();
    classpath = classpath != null ? List.copyOf(classpath) : List.of();
    modulepath = modulepath != null ? List.copyOf(modulepath) : List.of();
    processorPath = processorPath != null ? List.copyOf(processorPath) : List.of();
    compilerArgs = compilerArgs != null ? List.copyOf(compilerArgs) : List.of();
    ValidCheck.check().notNull(sourceTree, "sourceTree").notNull(outputDir, "outputDir").validate();
  }

  // A compile Lathe reads in place (externalOutput): the OpenJDK build's modules and build tools,
  // with no generated sources, module path, processor path, release, parameters, preview or
  // processing setting of their own.
  public static ModuleConfigData external(
      final String sourceTree,
      final String outputDir,
      final List<String> sourceRoots,
      final List<String> classpath,
      final String encoding,
      final List<String> compilerArgs,
      final AnalysisMode analysisMode) {
    return new ModuleConfigData(
        sourceTree,
        outputDir,
        null,
        sourceRoots,
        classpath,
        List.of(),
        List.of(),
        "",
        encoding,
        false,
        false,
        null,
        compilerArgs,
        analysisMode,
        true);
  }
}
