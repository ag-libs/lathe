package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.lathe.core.LatheLayout;
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
    boolean externalOutput,
    // Whether the tree is test code, as the build tool counts it: Maven's test compile, a Gradle
    // source set by Gradle's own convention. Decides go-to-test and suggestions, never visibility.
    // A params file written before the flag existed has none: its test tree is test-classes.
    Boolean testCode) {

  public ModuleConfigData {
    encoding = Objects.requireNonNullElse(encoding, "UTF-8");
    analysisMode = Objects.requireNonNullElse(analysisMode, AnalysisMode.CLASSPATH);
    sourceRoots = sourceRoots != null ? List.copyOf(sourceRoots) : List.of();
    classpath = classpath != null ? List.copyOf(classpath) : List.of();
    modulepath = modulepath != null ? List.copyOf(modulepath) : List.of();
    processorPath = processorPath != null ? List.copyOf(processorPath) : List.of();
    compilerArgs = compilerArgs != null ? List.copyOf(compilerArgs) : List.of();
    ValidCheck.check().notNull(sourceTree, "sourceTree").notNull(outputDir, "outputDir").validate();
    testCode =
        Objects.requireNonNullElse(testCode, LatheLayout.TEST_CLASSES_DIR.equals(sourceTree));
  }
}
