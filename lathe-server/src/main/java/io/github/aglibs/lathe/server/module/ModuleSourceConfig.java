package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import io.github.aglibs.lathe.core.schema.AnalysisMode;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public record ModuleSourceConfig(
    Path moduleDir,
    String sourceTree,
    Path outputDir,
    Path originalGenSourcesDir,
    List<Path> sourceRoots,
    List<Path> classpath,
    List<Path> modulepath,
    List<Path> processorPath,
    String release,
    String encoding,
    boolean parameters,
    boolean enablePreview,
    String proc,
    List<String> compilerArgs,
    AnalysisMode analysisMode,
    // True when outputDir is the build's own compiled output read directly (OpenJDK), not a .lathe
    // mirror (Maven). Drives raw-classpath resolution and the type-index base.
    boolean externalOutput,
    // Maps sibling modules' build outputs on the paths above to their .lathe/ mirrors.
    ReactorRewrite reactorRewrite) {

  public ModuleSourceConfig {
    sourceRoots = sourceRoots != null ? List.copyOf(sourceRoots) : List.of();
    classpath = classpath != null ? List.copyOf(classpath) : List.of();
    modulepath = modulepath != null ? List.copyOf(modulepath) : List.of();
    processorPath = processorPath != null ? List.copyOf(processorPath) : List.of();
    compilerArgs = compilerArgs != null ? List.copyOf(compilerArgs) : List.of();
    analysisMode = analysisMode != null ? analysisMode : AnalysisMode.CLASSPATH;
    reactorRewrite = reactorRewrite != null ? reactorRewrite : ReactorRewrite.none();
  }

  public Path latheClassesDir() {
    return moduleDir.resolve(sourceTree);
  }

  public Path generatedSourcesDir() {
    return moduleDir.resolve(LatheLayout.generatedSourcesDir(sourceTree));
  }

  // Source roots plus the .lathe generated-sources mirror (when the module has generated sources).
  // Definition/reference resolution searches these, so a generated type resolves to the fresh copy
  // Lathe regenerates on save — not the stale Maven target/ copy.
  public List<Path> searchRoots() {
    if (originalGenSourcesDir == null) {
      return sourceRoots;
    }

    return Stream.concat(sourceRoots.stream(), Stream.of(generatedSourcesDir())).toList();
  }

  public boolean isTestTree() {
    return LatheLayout.TEST_CLASSES_DIR.equals(sourceTree);
  }

  public List<Path> remappedClasspath() {
    return remapped(classpath);
  }

  public List<Path> remappedModulepath() {
    return remapped(modulepath);
  }

  public List<Path> remappedProcessorPath() {
    return remapped(processorPath);
  }

  private List<Path> remapped(final List<Path> paths) {
    return paths.stream().map(reactorRewrite::toLathe).toList();
  }

  public static ModuleSourceConfig load(
      final Path paramsFile, final Path moduleDir, final ReactorRewrite reactorRewrite)
      throws IOException {
    final var config = Json.read(paramsFile, ModuleConfigData.class);
    return new ModuleSourceConfig(
        moduleDir,
        config.sourceTree(),
        Path.of(config.outputDir()),
        config.generatedSourcesDir() != null ? Path.of(config.generatedSourcesDir()) : null,
        toPaths(config.sourceRoots()),
        toPaths(config.classpath()),
        toPaths(config.modulepath()),
        toPaths(config.processorPath()),
        config.release(),
        config.encoding(),
        config.parameters(),
        config.enablePreview(),
        config.proc(),
        config.compilerArgs(),
        config.analysisMode(),
        config.externalOutput(),
        reactorRewrite);
  }

  private static List<Path> toPaths(final List<String> strings) {
    return strings != null ? strings.stream().map(Path::of).toList() : List.of();
  }
}
